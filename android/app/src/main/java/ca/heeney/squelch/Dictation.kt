package ca.heeney.squelch

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log

/** One dictation at a time: listen while the key is held, transcribe, hand the text to a sink.
 *
 *  Finished sentences are transcribed while you're still talking: at each pause the audio
 *  so far is decoded, and everything up to its last full stop is kept. Letting go then only
 *  has to decode the sentence you were on, so the wait stays short however long you talk. */
object Dictation {
    enum class Phase { Idle, Listening, Working }

    val phase = Observable(Phase.Idle)
    val level = Observable(0f)

    private val main = Handler(Looper.getMainLooper())
    private var recorder: Recorder? = null
    private var fromMic = true
    private var sink: ((String) -> Unit)? = null
    private var startedAt = 0L

    // Everything below is guarded by `lock`: the mic thread writes it, the engine thread
    // commits sentences into it, and the main thread finishes it.
    private val lock = Any()
    private var buf = FloatArray(16000 * 30)
    private var size = 0
    private var rms = FloatArray(1024)
    private var frames = 0
    private var floor = 1f
    private var speechFrames = 0
    private var heardSpeech = false
    private var quietFrames = 0
    private var commitAt = 0          // samples before this are already in `committed`
    private val committed = StringBuilder()
    private var aheadBusy = false
    private var aheadAt = 0           // buffer size when the last look-ahead started
    private var session = 0           // bumped per dictation so a stale look-ahead is ignored

    val listening get() = phase.value == Phase.Listening

    /** Start listening. sink gets the cleaned-up transcript (never empty).
     *  mic = false is for debug-build wav runs, which feed() the audio themselves. */
    fun start(ctx: Context, sink: (String) -> Unit, mic: Boolean = true) {
        if (phase.value != Phase.Idle) return
        val app = ctx.applicationContext
        if (mic && !Engine.installed(app)) { Notice.show(app, "Open Squelch to download the model"); return }
        Engine.warm(app)
        // Pick up dictionary changes from your other devices while you talk.
        if (mic) Sync.soon(app, minGapMs = 60_000)
        this.sink = sink
        fromMic = mic
        synchronized(lock) {
            size = 0; frames = 0; floor = 1f; speechFrames = 0; heardSpeech = false; quietFrames = 0
            commitAt = 0; committed.setLength(0); aheadBusy = false; aheadAt = 0; session++
        }
        startedAt = SystemClock.elapsedRealtime()
        phase.value = Phase.Listening
        if (!mic) return
        tick(app)
        recorder = Recorder { f, n -> feed(app, f, n) }.also { it.start() }
    }

    /** One frame of audio. */
    fun feed(app: Context, frame: FloatArray, n: Int) {
        var sum = 0f
        for (i in 0 until n) sum += frame[i] * frame[i]
        val r = kotlin.math.sqrt(sum / n)
        var lookAhead = -1
        synchronized(lock) {
            if (size + n > buf.size) buf = buf.copyOf(buf.size * 2)
            System.arraycopy(frame, 0, buf, size, n)
            size += n
            if (frames == rms.size) rms = rms.copyOf(rms.size * 2)
            rms[frames++] = r
            // Noise floor: the quietest frame so far, rising slowly so it can follow a noisier room.
            floor = if (frames <= 3) minOf(floor, r) else minOf(floor * 1.003f, maxOf(r, 1e-5f))
            if (r > threshold()) { speechFrames++; quietFrames = 0 } else { speechFrames = 0; quietFrames++ }
            if (speechFrames >= 3) heardSpeech = true
            if (heardSpeech && !aheadBusy && quietFrames == PAUSE_FRAMES &&
                size - commitAt >= AHEAD_MIN && size - aheadAt >= AHEAD_STEP) {
                aheadBusy = true
                aheadAt = size
                lookAhead = session
            }
        }
        level.value = r
        if (lookAhead >= 0) lookAhead(app, lookAhead)
    }

    private fun lookAhead(app: Context, forSession: Int) {
        var from = 0
        Engine.transcribe(app, {
            synchronized(lock) { from = commitAt; buf.copyOfRange(commitAt, aheadAt) }
        }) { heard ->
            synchronized(lock) {
                aheadBusy = false
                if (forSession != session || from != commitAt) return@synchronized
                val (text, cut) = lastSentence(heard) ?: return@synchronized
                if (committed.isNotEmpty()) committed.append(' ')
                committed.append(text)
                commitAt = from + (cut * 16000).toInt()
                Log.i(TAG, "kept a sentence ahead${said(text)}")
            }
        }
    }

    /** Everything up to the last sentence end that has a word after it, and where to cut the audio. */
    private fun lastSentence(h: Heard): Pair<String, Float>? {
        for (i in h.tokens.size - 2 downTo 0) {
            val t = h.tokens[i].trim()
            if ((t.endsWith('.') || t.endsWith('?') || t.endsWith('!')) && h.tokens[i + 1].startsWith(" ")) {
                val text = h.tokens.sliceArray(0..i).joinToString("").trim()
                val cut = maxOf(h.times[i] + 0.08f, h.times[i + 1] - 0.16f)
                return if (text.isEmpty()) null else text to cut
            }
        }
        return null
    }

    private fun threshold() = maxOf(floor * 4f, 0.0015f)

    /** Stop listening and transcribe. */
    fun finish(ctx: Context) {
        if (phase.value != Phase.Listening) return
        val app = ctx.applicationContext
        val rec = recorder
        recorder = null
        rec?.stop()
        if (rec?.failed == true) {
            phase.value = Phase.Idle
            Notice.show(app, "Mic unavailable")
            return
        }
        val heldMs = SystemClock.elapsedRealtime() - startedAt
        // A tap rather than a hold. Quiet audio still gets decoded: a soft voice
        // shouldn't be thrown away by a level guess, and silence decodes to nothing.
        if (fromMic && heldMs < 350) {
            synchronized(lock) { session++ }
            phase.value = Phase.Idle
            return
        }
        phase.value = Phase.Working
        val out = sink
        val t0 = SystemClock.elapsedRealtime()
        var audioMs = 0L
        // Runs after any look-ahead still in flight (one engine thread), so it sees its commit.
        Engine.transcribe(app, {
            synchronized(lock) { tail().also { audioMs = it.size / 16L } }
        }) { tailHeard ->
            val raw = synchronized(lock) {
                session++
                if (committed.isEmpty()) tailHeard.text else "$committed ${tailHeard.text}"
            }
            val text = Cleanup.tidy(app, raw)
            Stats.lastAudioMs = audioMs
            Stats.lastWaitMs = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "released to text in ${Stats.lastWaitMs} ms ($audioMs ms left to decode)")
            if (fromMic) {
                try {
                    Analytics.record(app, text, heldMs / 1000f, Stats.lastWaitMs / 1000f, Models.selected(app).key)
                } catch (t: Throwable) { Log.e(TAG, "stats", t) }
                Sync.soon(app)
            }
            main.post {
                if (text.isNotEmpty()) {
                    Stats.last.value = text
                    try { out?.invoke(text) } catch (t: Throwable) { Log.e(TAG, "insert failed", t) }
                } else if (Engine.state.value == Engine.State.Failed) {
                    Notice.show(app, "Model failed to load")
                }
                phase.value = Phase.Idle
            }
        }
    }

    /** The audio not yet transcribed, without the silence at either end. Call holding `lock`. */
    private fun tail(): FloatArray {
        val th = threshold()
        var last = frames - 1
        while (last >= 0 && rms[last] <= th) last--
        var from = commitAt
        if (commitAt == 0) {
            var first = 0
            while (first < frames && rms[first] <= th) first++
            from = maxOf(0, (first - 8) * Recorder.FRAME)
        }
        val to = minOf(size, (last + 12) * Recorder.FRAME)
        return if (to - from >= 4000) buf.copyOfRange(from, to) else buf.copyOfRange(commitAt, size)
    }

    fun cancel() {
        recorder?.stop()
        recorder = null
        synchronized(lock) { session++ }
        if (phase.value == Phase.Listening) phase.value = Phase.Idle
    }

    private fun tick(ctx: Context) {
        try {
            val vm = ctx.getSystemService(VibratorManager::class.java)
            vm?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } catch (_: Throwable) {}
    }

    /** a pause this long (in 30 ms frames) triggers a look-ahead */
    private const val PAUSE_FRAMES = 12
    /** only look ahead with at least this much untranscribed audio... */
    private const val AHEAD_MIN = 16000 * 5
    /** ...and this much new audio since the last try */
    private const val AHEAD_STEP = 16000 * 3
}
