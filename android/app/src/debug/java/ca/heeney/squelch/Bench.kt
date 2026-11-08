package ca.heeney.squelch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Debug builds only: time a decode of a 16 kHz mono 16-bit wav. */
class Bench : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val hold = intent.getIntExtra("hold", 0)
        if (hold > 0) {
            // A trigger key hold, minus the physical key.
            val svc = SquelchService.instance ?: run { Log.e(TAG, "no service"); return }
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            val t = android.os.SystemClock.uptimeMillis()
            fun ev(action: Int) = android.view.KeyEvent(t, android.os.SystemClock.uptimeMillis(), action, 0, 0, 0, 3, Prefs.ESSENTIAL_SCAN)
            main.post { Log.i(TAG, "consumed down: " + svc.onKeyEvent(ev(android.view.KeyEvent.ACTION_DOWN))) }
            main.postDelayed({ Log.i(TAG, "consumed up: " + svc.onKeyEvent(ev(android.view.KeyEvent.ACTION_UP))) }, hold.toLong())
            return
        }
        intent.getStringExtra("type")?.let { text ->
            val svc = SquelchService.instance ?: run { Log.e(TAG, "no service"); return }
            SquelchService.forcePaste = intent.getBooleanExtra("paste", false)
            android.os.Handler(android.os.Looper.getMainLooper()).post { svc.insert(text) }
            return
        }
        if (intent.getBooleanExtra("mic", false)) {
            // Background mic check: 3 s from the real mic, as a key hold would.
            var peak = 0f
            val off = Dictation.level.listen { peak = maxOf(peak, it) }
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            main.post { Dictation.start(ctx, { Log.i(TAG, "mic result: $it") }) }
            main.postDelayed({ off(); Log.i(TAG, "mic peak rms $peak"); Dictation.finish(ctx) }, 3000)
            return
        }
        val path = intent.getStringExtra("wav") ?: return
        val runs = intent.getIntExtra("runs", 3)
        val f = File(path).let { if (it.isAbsolute) it else File(ctx.filesDir.parentFile, path) }
        val pending = goAsync()
        Thread {
            try {
                val bytes = f.readBytes()
                val bb = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
                val pcm = FloatArray((bytes.size - 44) / 2) { bb.short / 32768f }
                if (intent.getBooleanExtra("stream", false)) {
                    // Real-time pacing through the dictation path, as if spoken into the mic.
                    Dictation.start(ctx, { Log.i(TAG, "stream result: $it"); SquelchService.instance?.insert(it) }, mic = false)
                    val frame = FloatArray(Recorder.FRAME)
                    var i = 0
                    while (i + Recorder.FRAME <= pcm.size) {
                        System.arraycopy(pcm, i, frame, 0, Recorder.FRAME)
                        Dictation.feed(ctx, frame, Recorder.FRAME)
                        i += Recorder.FRAME
                        Thread.sleep(30)
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post { Dictation.finish(ctx) }
                    Thread.sleep(5000)
                    return@Thread
                }
                val threads = intent.getIntExtra("threads", 0)
                if (threads > 0) Engine.reload(ctx, threads)
                var h = Heard.NONE
                repeat(runs) { h = Engine.transcribe(ctx, pcm).get() }
                Log.i(TAG, "tokens: " + h.tokens.indices.joinToString(" ") { "[${h.tokens[it]}]@%.2f".format(h.times[it]) })
                Log.i(TAG, "bench done: ${Stats.lastDecodeMs} ms for ${pcm.size / 16} ms of audio")
            } catch (t: Throwable) {
                Log.e(TAG, "bench", t)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
