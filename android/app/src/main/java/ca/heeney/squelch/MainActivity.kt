package ca.heeney.squelch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver

private val Accent = Color(0xFF6F93B8)
private val Dim = Color(0xFF8A8A8A)
private val Line = Color(0xFF1F1F1F)
private val Inter = FontFamily(Font(R.font.inter_regular), Font(R.font.inter_medium, FontWeight.Medium))

class MainActivity : ComponentActivity() {
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    private var refresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Screen() }
    }

    private fun a11yOn(): Boolean {
        if (SquelchService.instance != null) return true
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return list.contains("$packageName/")
    }

    private fun micOn() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun open(action: String) {
        try { startActivity(Intent(action)) } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    @Composable
    private fun <T> Observable<T>.state(): State<T> {
        val s = remember { mutableStateOf(value) }
        DisposableEffect(this) {
            val off = listen { v -> runOnUiThread { s.value = v } }
            s.value = value
            onDispose { off() }
        }
        return s
    }

    @Composable
    private fun Screen() {
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs) }
        }
        val engine by Engine.state.state()
        val dl by Download.progress.state()
        val dlErr by Download.error.state()
        val last by Stats.last.state()
        val phase by Dictation.phase.state()
        var learning by remember { mutableStateOf(false) }
        var keyName by remember { mutableStateOf(Prefs.keyName()) }
        @Suppress("UNUSED_EXPRESSION") refresh
        LaunchedEffect(refresh) { Engine.warm(this@MainActivity) }

        Column(
            Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            Text("Squelch", color = Color.White, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 40.sp)
            Spacer(Modifier.height(32.dp))

            val a11y = a11yOn()
            SettingRow("Accessibility", if (a11y) "On" else null, "Turn on") { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            SettingRow("Microphone", if (micOn()) "Allowed" else null, "Allow") { askMic.launch(Manifest.permission.RECORD_AUDIO) }
            val installed = Engine.installed(this@MainActivity)
            val modelStatus = when {
                dl != null -> "${((dl ?: 0f) * 100).toInt()}%"
                !installed -> null
                engine == Engine.State.Ready || engine == Engine.State.Asleep -> "Ready"
                engine == Engine.State.Failed -> "Failed"
                else -> "Loading"
            }
            SettingRow("Model", modelStatus, dlErr?.let { "Retry" } ?: "Download 660 MB") { Download.start(this@MainActivity) }
            SettingRow("Key", if (learning) "Press a key" else keyName, null, action = if (learning || !a11y) null else "Change") {
                learning = true
                SquelchService.learner = { ev ->
                    Prefs.keyCode = ev.keyCode
                    Prefs.scanCode = ev.scanCode
                    runOnUiThread { learning = false; keyName = Prefs.keyName() }
                }
            }

            Spacer(Modifier.height(32.dp))
            var text by remember { mutableStateOf("") }
            BasicTextField(
                value = text, onValueChange = { text = it },
                textStyle = TextStyle(color = Color.White, fontFamily = Inter, fontSize = 18.sp),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
                    .border(1.dp, if (phase == Dictation.Phase.Listening) Accent else Line, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                decorationBox = { inner ->
                    if (text.isEmpty()) Text("Try it here", color = Dim, fontFamily = Inter, fontSize = 18.sp)
                    inner()
                },
            )
            if (last.isNotEmpty() && Stats.lastWaitMs >= 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "%.2f s".format(Stats.lastWaitMs / 1000f),
                    color = Dim, fontFamily = Inter, fontSize = 13.sp,
                )
            }
        }
    }

    /** label on the left; a status in grey when it's done, else the action in the accent */
    @Composable
    private fun SettingRow(label: String, status: String?, fix: String?, action: String? = null, onClick: () -> Unit) {
        val tap = action ?: if (status == null) fix else null
        Row(
            Modifier.fillMaxWidth().then(if (tap != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Color.White, fontFamily = Inter, fontSize = 18.sp, modifier = Modifier.weight(1f))
            if (status != null) Text(status, color = Dim, fontFamily = Inter, fontSize = 16.sp)
            if (tap != null) {
                if (status != null) Spacer(Modifier.width(16.dp))
                Text(tap, color = Accent, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }
}
