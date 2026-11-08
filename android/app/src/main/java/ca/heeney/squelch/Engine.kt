package ca.heeney.squelch

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

const val TAG = "Squelch"

/** Parakeet TDT 0.6B v2 (English), int8, on the CPU through sherpa-onnx. It
 *  stays loaded once it's up, so a dictation only pays for decoding. */
object Engine {
    const val MODEL = "parakeet-tdt-0.6b-v2-int8"
    private const val BASE =
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main/"

    /** name to size in bytes, so a half-finished download never counts as ready */
    val FILES = linkedMapOf(
        "encoder.int8.onnx" to 652_282_309L,
        "decoder.int8.onnx" to 7_257_753L,
        "joiner.int8.onnx" to 1_739_080L,
        "tokens.txt" to 9_384L,
    )
    val TOTAL_BYTES = FILES.values.sum()

    fun url(name: String) = BASE + name

    enum class State {
        Missing, Loading, Ready, Failed,
        /** installed, unloaded under memory pressure; loads on the next dictation */
        Asleep,
    }

    val state = Observable(State.Missing)

    /** One thread owns the recognizer: loading and decoding never overlap. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "squelch-engine") }
    @Volatile private var recognizer: OfflineRecognizer? = null

    fun dir(ctx: Context) = File(ctx.filesDir, MODEL)

    fun installed(ctx: Context) =
        FILES.all { (name, size) -> File(dir(ctx), name).length() == size }

    /** Load in the background if the files are there and nothing's loaded. */
    fun warm(ctx: Context) {
        val app = ctx.applicationContext
        if (recognizer != null || state.value == State.Loading) return
        if (!installed(app)) { state.value = State.Missing; return }
        state.value = State.Loading
        worker.execute { loadNow(app) }
    }

    private fun loadNow(ctx: Context) {
        if (recognizer != null) return
        if (!installed(ctx)) { state.value = State.Missing; return }
        val d = dir(ctx)
        val t0 = SystemClock.elapsedRealtime()
        try {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = File(d, "encoder.int8.onnx").path,
                        decoder = File(d, "decoder.int8.onnx").path,
                        joiner = File(d, "joiner.int8.onnx").path,
                    ),
                    tokens = File(d, "tokens.txt").path,
                    modelType = "nemo_transducer",
                    numThreads = threads,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            )
            val r = OfflineRecognizer(null, config)
            val t1 = SystemClock.elapsedRealtime()
            // First run allocates everything; do it now instead of on your first dictation.
            decodeWith(r, FloatArray(16000))
            recognizer = r
            state.value = State.Ready
            Log.i(TAG, "model loaded in ${t1 - t0} ms, warm-up ${SystemClock.elapsedRealtime() - t1} ms")
        } catch (t: Throwable) {
            Log.e(TAG, "model load failed", t)
            state.value = State.Failed
        }
    }

    /** Transcribe 16 kHz mono samples. Loads the model first if it has to. */
    fun transcribe(ctx: Context, samples: FloatArray): Future<Heard> {
        val app = ctx.applicationContext
        return worker.submit<Heard> {
            loadNow(app)
            val r = recognizer ?: return@submit Heard.NONE
            val t0 = SystemClock.elapsedRealtime()
            val heard = decodeWith(r, samples)
            val ms = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "decoded %.1f s of audio in %d ms%s".format(samples.size / 16000f, ms, said(heard.text)))
            Stats.lastDecodeMs = ms
            heard
        }
    }

    /** Decode on the engine thread: clip() is called there, then done() with the result.
     *  Jobs run in order, so a clip can depend on what an earlier job's done() changed. */
    fun transcribe(ctx: Context, clip: () -> FloatArray, done: (Heard) -> Unit) {
        val app = ctx.applicationContext
        worker.execute {
            val heard = try {
                loadNow(app)
                val r = recognizer
                val samples = clip()
                if (r == null || samples.isEmpty()) Heard.NONE else {
                    val t0 = SystemClock.elapsedRealtime()
                    decodeWith(r, samples).also {
                        val ms = SystemClock.elapsedRealtime() - t0
                        Stats.lastDecodeMs = ms
                        Log.i(TAG, "decoded %.1f s of audio in %d ms%s".format(samples.size / 16000f, ms, said(it.text)))
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "decode failed", t); Heard.NONE
            }
            done(heard)
        }
    }

    private fun decodeWith(r: OfflineRecognizer, samples: FloatArray): Heard {
        val s = r.createStream()
        try {
            s.acceptWaveform(samples, 16000)
            r.decode(s)
            val res = r.getResult(s)
            return Heard(res.text.trim(), res.tokens, res.timestamps)
        } finally {
            s.release()
        }
    }

    /** Free the ~1 GB the model holds. The next dictation loads it again while you talk. */
    fun unload() {
        worker.execute {
            if (Dictation.phase.value != Dictation.Phase.Idle) return@execute
            val r = recognizer ?: return@execute
            recognizer = null
            r.release()
            state.value = State.Asleep
            Log.i(TAG, "model unloaded to free memory")
        }
    }

    /** Debug builds: try another thread count. */
    fun reload(ctx: Context, n: Int) {
        worker.execute {
            recognizer?.release(); recognizer = null
            threads = n
            loadNow(ctx.applicationContext)
        }
    }

    /** The Dimensity 7300 has four big cores; more threads land on little ones and slow it down. */
    @Volatile private var threads = 4
}

/** A transcript, with each token's start time in seconds from the start of the clip. */
class Heard(val text: String, val tokens: Array<String>, val times: FloatArray) {
    companion object { val NONE = Heard("", emptyArray(), FloatArray(0)) }
}

/** What you said only goes to the log in debug builds. */
fun said(text: String) = if (BuildConfig.DEBUG) ": $text" else ""

object Stats {
    @Volatile var lastDecodeMs = -1L
    @Volatile var lastAudioMs = -1L
    @Volatile var lastWaitMs = -1L
    val last = Observable("")
}

/** A value with change listeners, called on whichever thread set it. */
class Observable<T>(initial: T) {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(T) -> Unit>()
    @Volatile var value: T = initial
        set(v) { field = v; listeners.forEach { it(v) } }

    fun listen(l: (T) -> Unit): () -> Unit {
        listeners += l
        return { listeners -= l }
    }
}
