package ca.heeney.squelch

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction

/** Holds the trigger key, shows the pill while listening, and puts the
 *  transcript into whatever text field has focus. */
class SquelchService : AccessibilityService() {
    private var pill: PillView? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var unlisten: (() -> Unit)? = null

    override fun onServiceConnected() {
        instance = this
        Engine.warm(this)
        unlisten = Dictation.phase.listen { p -> main.post { showPhase(p) } }
        Log.i(TAG, "accessibility service connected")
    }

    override fun onDestroy() {
        unlisten?.invoke()
        hidePill()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The model is most of this process; let it go before Android has to kill something.
        // (UI_HIDDEN and BACKGROUND, in between, only mean the settings screen closed.)
        if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= TRIM_MEMORY_MODERATE) Engine.unload()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    public override fun onKeyEvent(ev: KeyEvent): Boolean {
        learner?.let { learn ->
            if (ev.action == KeyEvent.ACTION_UP) { learner = null; learn(ev) }
            return true
        }
        if (!Prefs.isTrigger(ev)) return false
        when (ev.action) {
            KeyEvent.ACTION_DOWN -> if (ev.repeatCount == 0) {
                if (Dictation.listening) Dictation.finish(this)
                else Dictation.start(this, ::insert)
            }
            KeyEvent.ACTION_UP -> Dictation.finish(this)
        }
        return true
    }

    // ---- the pill ----

    private fun showPhase(p: Dictation.Phase) {
        when (p) {
            Dictation.Phase.Idle -> hidePill()
            else -> { showPill(); pill?.phase = p }
        }
    }

    fun showPill() {
        if (pill != null) return
        val wm = getSystemService(WindowManager::class.java)
        val v = PillView(this)
        val d = resources.displayMetrics.density
        val lp = WindowManager.LayoutParams(
            (132 * d).toInt(), (40 * d).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight() + (8 * d).toInt()
        }
        try { wm.addView(v, lp); pill = v } catch (t: Throwable) { Log.e(TAG, "pill", t) }
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    fun hidePill() {
        val v = pill ?: return
        pill = null
        try { getSystemService(WindowManager::class.java).removeView(v) } catch (_: Throwable) {}
    }

    // ---- typing it in ----

    private fun focusedField(): AccessibilityNodeInfo? {
        // Compose (and some other toolkits) report the host view as the input focus, with
        // the real text field as a virtual child, so look underneath it too.
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { f ->
            if (f.isEditable) return f
            focusedEditableIn(f)?.let { return it }
        }
        rootInActiveWindow?.let { r -> focusedEditableIn(r)?.let { return it } }
        for (w in windows) {
            val r = w.root ?: continue
            focusedEditableIn(r)?.let { return it }
        }
        return null
    }

    private fun focusedEditableIn(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 2000) {
            val n = queue.removeFirst()
            if (n.isEditable && n.isFocused) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue += it }
        }
        return null
    }

    fun insert(text: String) {
        val node = focusedField()
        if (node == null) {
            copy(text)
            Notice.show(this, "Copied")
            return
        }
        val current = if (node.isShowingHintText || node.isPassword) "" else node.text?.toString().orEmpty()
        var s = node.textSelectionStart
        var e = node.textSelectionEnd
        if (s < 0 || e < 0 || s > current.length || e > current.length) { s = current.length; e = s }
        if (s > e) s = e.also { e = s }
        val before = current.substring(0, s)
        val after = current.substring(e)
        var piece = text
        if (before.isNotEmpty() && !before.last().isWhitespace()) piece = " $piece"
        if (after.isNotEmpty() && !after.first().isWhitespace() && after.first() !in ".,!?;:") piece = "$piece "

        // Setting the text directly leaves the clipboard alone, so try that first. Some
        // fields (Compose, web) accept it and then ignore it, so wait to see it land
        // before moving the cursor, and paste instead if it never does.
        val canPaste = node.actionList.contains(AccessibilityAction.ACTION_PASTE)
        val wanted = before + piece + after
        // Rich web editors (contenteditable: Gmail, Docs...) would lose their formatting to a
        // whole-text replace, and report stale text; plain web inputs say chromeRole=textField.
        val role = node.extras?.getString("AccessibilityNodeInfo.chromeRole")
        val richWeb = role != null && role != "textField" && role != "searchBox" && role != "textFieldWithComboBox"
        val setOk = !node.isPassword && !richWeb && !forcePaste && node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, wanted) },
        )
        if (!setOk) { paste(node, piece, canPaste); return }
        val pos = before.length + piece.length
        val deadline = android.os.SystemClock.uptimeMillis() + 600
        fun check() {
            node.refresh()
            val now = node.text?.toString().orEmpty()
            when {
                now == wanted || (now != current && now.contains(piece.trim())) -> {
                    if (pos <= now.length) node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
                    })
                }
                android.os.SystemClock.uptimeMillis() < deadline -> main.postDelayed(::check, 25)
                else -> {
                    Log.i(TAG, "set text didn't stick in ${node.className}, pasting")
                    paste(node, piece, canPaste)
                }
            }
        }
        check()
    }

    private fun paste(node: AccessibilityNodeInfo, piece: String, canPaste: Boolean) {
        copy(piece)
        if (canPaste && node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return
        Notice.show(this, "Copied")
    }

    private fun copy(text: String) {
        val clip = ClipData.newPlainText("Squelch", text)
        // Keeps the "copied" preview from flashing the text on screen.
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    companion object {
        @Volatile var instance: SquelchService? = null
            private set
        /** Set by the key picker: the next key press goes here instead. */
        @Volatile var learner: ((KeyEvent) -> Unit)? = null
        @Volatile var forcePaste = false
    }
}
