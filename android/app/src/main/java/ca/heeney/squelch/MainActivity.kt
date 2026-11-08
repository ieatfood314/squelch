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
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) { refresh++; Sync.soon(this@MainActivity, minGapMs = 10_000) }
            }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs) }
        }
        val engine by Engine.state.state()
        val last by Stats.last.state()
        val phase by Dictation.phase.state()
        var learning by remember { mutableStateOf(false) }
        var keyName by remember { mutableStateOf(Prefs.keyName()) }
        var open by remember { mutableStateOf("") }
        fun toggle(s: String) { open = if (open == s) "" else s }
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
            ModelRow(engine, open == "model") { toggle("model") }
            SettingRow("Key", if (learning) "Press a key" else keyName, null, action = if (learning || !a11y) null else "Change") {
                learning = true
                SquelchService.learner = { ev ->
                    Prefs.keyCode = ev.keyCode
                    Prefs.scanCode = ev.scanCode
                    runOnUiThread { learning = false; keyName = Prefs.keyName() }
                }
            }
            SyncRow(open == "sync") { toggle("sync") }
            WordsRow(open == "words") { toggle("words") }

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
            AnalyticsSection()
        }
    }

    // ---- model ----

    @Composable
    private fun ModelRow(engine: Engine.State, expanded: Boolean, onToggle: () -> Unit) {
        val dl by Download.progress.state()
        val dlKey by Download.which.state()
        val dlErr by Download.error.state()
        val cur = Models.selected(this)
        val installed = cur.installed(this)
        val status = when {
            !installed && dl != null && dlKey == cur.key -> "${((dl ?: 0f) * 100).toInt()}%"
            !installed -> null
            engine == Engine.State.Failed -> "${cur.name} · Failed"
            engine == Engine.State.Loading -> "${cur.name} · Loading"
            else -> cur.name
        }
        // Nothing downloaded yet: the list stays open so you can pick one.
        val show = expanded || !installed
        SettingRow("Model", status ?: "Pick one", null, action = if (!installed) null else if (expanded) "Done" else "Change",
            divider = !show, onClick = onToggle)
        if (!show) return
        var tick by remember { mutableIntStateOf(0) }
        @Suppress("UNUSED_EXPRESSION") tick
        for (m in Models.all(this)) {
            val here = m.installed(this)
            val busy = dl != null && dlKey == m.key
            val right = when {
                busy -> "${((dl ?: 0f) * 100).toInt()}%"
                dlErr == m.key -> Download.errorText
                m.key == cur.key && here -> "In use"
                here -> "Ready"
                else -> m.size()
            }
            Row(
                Modifier.fillMaxWidth().clickable(enabled = dl == null && (m.key != cur.key || !here)) {
                    if (here) Engine.select(this, m) else Download.start(this, m)
                    tick++
                }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(if (m.key == cur.key) Accent else Color.Transparent, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.name, color = Color.White, fontFamily = Inter, fontSize = 16.sp)
                    Text(m.about, color = Dim, fontFamily = Inter, fontSize = 13.sp)
                }
                Spacer(Modifier.width(12.dp))
                Text(right, color = Dim, fontFamily = Inter, fontSize = 14.sp)
                if (here && m.key != cur.key && dl == null) {
                    Spacer(Modifier.width(12.dp))
                    Text("Remove", color = Accent, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                        modifier = Modifier.clickable { Models.remove(this@MainActivity, m); tick++ })
                }
            }
        }
        var repo by remember { mutableStateOf("") }
        Spacer(Modifier.height(4.dp))
        InputLine(repo, { repo = it }, "Hugging Face model, user/name", "Get",
            enabled = dl == null && Models.parseRepo(repo) != null) {
            Models.parseRepo(repo)?.let { Download.custom(this, it); repo = "" }
        }
        if (dlErr != null && Models.BUILT_IN.none { it.key == dlErr }) {
            Text(Download.errorText, color = Dim, fontFamily = Inter, fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }

    // ---- sync ----

    @Composable
    private fun SyncRow(expanded: Boolean, onToggle: () -> Unit) {
        val st by Sync.status.state()
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(st) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(30_000) } }
        val status = when (st) {
            Sync.Status.Off -> if (Sync.url() == null) "Off" else "Not yet"
            Sync.Status.Syncing -> "Syncing"
            Sync.Status.Failed -> "Can't reach hub"
            Sync.Status.Synced -> ago(now - Sync.lastOk)
        }
        SettingRow("Sync", status, null, action = if (expanded) "Done" else if (Sync.url() == null) "Set up" else "Change",
            divider = !expanded, onClick = onToggle)
        if (!expanded) return
        var url by remember { mutableStateOf(Prefs.syncUrl) }
        InputLine(url, { url = it }, "Hub address", "Save", enabled = url.trim() != Prefs.syncUrl) {
            Prefs.syncUrl = url.trim()
            if (url.isBlank()) Sync.status.value = Sync.Status.Off else Sync.soon(this)
        }
        Text(
            if (st == Sync.Status.Failed) Sync.lastError
            else "Shares your words and analytics with Squelch on your computers, through squelch-sync-server.",
            color = Dim, fontFamily = Inter, fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }

    private fun ago(ms: Long): String = when {
        ms < 60_000 -> "Synced just now"
        ms < 3_600_000 -> "Synced ${ms / 60_000} min ago"
        ms < 86_400_000 -> "Synced ${ms / 3_600_000} h ago"
        else -> "Synced ${ms / 86_400_000} d ago"
    }

    // ---- words ----

    @Composable
    private fun WordsRow(expanded: Boolean, onToggle: () -> Unit) {
        val ver by Words.changed.state()
        val words = remember(ver, refresh) { Words.load(this) }
        SettingRow("Words", "${words.size}", null, action = if (expanded) "Done" else "Edit", divider = !expanded, onClick = onToggle)
        if (!expanded) return
        var term by remember { mutableStateOf("") }
        var heard by remember { mutableStateOf("") }
        InputLine(term, { term = it }, "Word, spelled right", null, enabled = false) {}
        InputLine(heard, { heard = it }, "Heard as, comma separated", "Add", enabled = term.isNotBlank()) {
            Words.add(this, term, heard.split(','))
            term = ""; heard = ""
        }
        Spacer(Modifier.height(8.dp))
        for (w in words.asReversed()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(w.term, color = Color.White, fontFamily = Inter, fontSize = 16.sp)
                    if (w.heard.isNotEmpty()) Text(w.heard.joinToString(", "), color = Dim, fontFamily = Inter, fontSize = 13.sp)
                }
                Spacer(Modifier.width(12.dp))
                Text("Remove", color = Accent, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                    modifier = Modifier.clickable { Words.remove(this@MainActivity, w.term) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }

    // ---- analytics ----

    @Composable
    private fun AnalyticsSection() {
        val ver by Analytics.changed.state()
        val s = remember(ver, refresh) { Analytics.summarize(Analytics.load(this)) }
        if (s.dictations == 0) return
        Spacer(Modifier.height(40.dp))
        Text("Analytics", color = Color.White, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 22.sp)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            Tile("%,d".format(s.words), "words")
            Tile("%.0f".format(s.wpm), "WPM")
            Tile("%.2f s".format(s.avgLat), "transcription")
            Tile("%,d".format(s.dictations), "dictations")
        }
        Spacer(Modifier.height(16.dp))
        Text(
            if (s.recentN > 0) "Last hour: %.0f WPM · %,d words · last 10 avg %.2f s".format(s.recentWpm, s.recentWords, s.recentLat)
            else "Nothing in the last hour",
            color = Dim, fontFamily = Inter, fontSize = 13.sp,
        )
        if (s.devices.size > 1) {
            Spacer(Modifier.height(4.dp))
            Text(s.devices.joinToString(" · ") { (d, w) -> "$d %,d".format(w) }, color = Dim, fontFamily = Inter, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
        val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")
        TableRow("Day", "Words", "Dictations", "Minutes", Dim)
        for (d in s.days) {
            TableRow(d.date.format(fmt), "%,d".format(d.words), "%,d".format(d.dictations), "%.1f".format(d.minutes), Color.White)
        }
    }

    @Composable
    private fun RowScope.Tile(value: String, caption: String) {
        Column(Modifier.weight(1f)) {
            Text(value, color = Color.White, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 20.sp, maxLines = 1)
            Text(caption, color = Dim, fontFamily = Inter, fontSize = 12.sp, maxLines = 1)
        }
    }

    @Composable
    private fun TableRow(a: String, b: String, c: String, d: String, color: Color) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(a, color = color, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.weight(1.4f))
            for (v in listOf(b, c, d)) {
                Text(v, color = color, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }

    /** a one-line field with an optional action in the accent on its right */
    @Composable
    private fun InputLine(value: String, onChange: (String) -> Unit, hint: String, action: String?, enabled: Boolean, onAction: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).border(1.dp, Line, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontFamily = Inter, fontSize = 16.sp),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text(hint, color = Dim, fontFamily = Inter, fontSize = 16.sp)
                    inner()
                },
            )
            if (action != null) {
                Spacer(Modifier.width(12.dp))
                Text(action, color = if (enabled) Accent else Dim, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp,
                    modifier = Modifier.clickable(enabled = enabled, onClick = onAction))
            }
        }
    }

    /** label on the left; a status in grey when it's done, else the action in the accent */
    @Composable
    private fun SettingRow(label: String, status: String?, fix: String?, action: String? = null, divider: Boolean = true, onClick: () -> Unit) {
        val tap = action ?: if (status == null) fix else null
        Row(
            Modifier.fillMaxWidth().then(if (tap != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Color.White, fontFamily = Inter, fontSize = 18.sp, maxLines = 1, softWrap = false)
            Spacer(Modifier.width(16.dp))
            Text(status ?: "", color = Dim, fontFamily = Inter, fontSize = 16.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f))
            if (tap != null) {
                if (status != null) Spacer(Modifier.width(16.dp))
                Text(tap, color = Accent, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp)
            }
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }
}
