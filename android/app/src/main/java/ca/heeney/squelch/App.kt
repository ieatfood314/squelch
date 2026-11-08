package ca.heeney.squelch

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Engine.warm(this)
    }
}

object Prefs {
    private lateinit var app: Context
    private val sp get() = app.getSharedPreferences("squelch", Context.MODE_PRIVATE)

    fun init(ctx: Context) { app = ctx.applicationContext }

    /** The Essential key sends Linux code 250, which Android has no keycode for. */
    const val ESSENTIAL_SCAN = 250

    var keyCode: Int
        get() = sp.getInt("key_code", KeyEvent.KEYCODE_UNKNOWN)
        set(v) = sp.edit().putInt("key_code", v).apply()
    var scanCode: Int
        get() = sp.getInt("scan_code", ESSENTIAL_SCAN)
        set(v) = sp.edit().putInt("scan_code", v).apply()

    fun isTrigger(ev: KeyEvent): Boolean {
        val code = keyCode
        return if (code != KeyEvent.KEYCODE_UNKNOWN) ev.keyCode == code else ev.scanCode == scanCode
    }

    fun keyName(): String {
        val code = keyCode
        if (code == KeyEvent.KEYCODE_UNKNOWN) {
            return if (scanCode == ESSENTIAL_SCAN) "Essential key" else "Key $scanCode"
        }
        return KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").lowercase()
            .split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }
}

object Notice {
    private val main = Handler(Looper.getMainLooper())
    fun show(ctx: Context, text: String) {
        val app = ctx.applicationContext
        main.post { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }
    }
}
