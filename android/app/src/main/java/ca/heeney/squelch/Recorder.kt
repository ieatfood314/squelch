package ca.heeney.squelch

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/** 16 kHz mono float capture on its own thread, handed over 30 ms at a time. */
class Recorder(private val onFrame: (FloatArray, Int) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var failed = false
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        running = true
        thread = Thread({
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
                    maxOf(min, FRAME * 4 * 8),
                )
            } catch (t: Throwable) {
                Log.e(TAG, "AudioRecord failed", t); failed = true; return@Thread
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized"); failed = true; rec.release(); return@Thread
            }
            rec.startRecording()
            val frame = FloatArray(FRAME)
            try {
                while (running) {
                    val n = rec.read(frame, 0, FRAME, AudioRecord.READ_BLOCKING)
                    if (n < 0) { failed = true; break }
                    if (n > 0) onFrame(frame, n)
                }
            } finally {
                rec.stop()
                rec.release()
            }
        }, "squelch-mic").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Stop; returns once the last frame has been handed over. */
    fun stop() {
        running = false
        thread?.join(500)
    }

    companion object {
        const val RATE = 16000
        const val FRAME = 480 // 30 ms
    }
}
