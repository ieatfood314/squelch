package ca.heeney.squelch

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Fetches a model from Hugging Face, then switches to it. Resumes a partial file
 *  instead of starting over. One download at a time. */
object Download {
    /** null when not downloading, else 0..1 */
    val progress = Observable<Float?>(null)
    /** key of the model being fetched */
    val which = Observable<String?>(null)
    val error = Observable<String?>(null)

    fun start(ctx: Context, model: Model) = run(ctx, model.key) { model }

    /** A Hugging Face repo you typed in: look it up first. */
    fun custom(ctx: Context, repo: String) = run(ctx, Models.keyFor(repo)) { Models.resolve(it, repo) }

    private fun run(ctx: Context, key: String, model: (Context) -> Model) {
        if (progress.value != null) return
        val app = ctx.applicationContext
        which.value = key
        progress.value = 0f
        error.value = null
        Thread({
            try {
                val m = model(app)
                val dir = m.dir(app).apply { mkdirs() }
                var done = 0L
                for ((name, size) in m.files) {
                    val f = File(dir, name)
                    if (f.length() == size) { done += size; continue }
                    f.parentFile?.mkdirs()
                    fetch(m.url(name), f, size) { got -> progress.value = (done + got).toFloat() / m.bytes }
                    done += size
                }
                progress.value = null
                which.value = null
                Engine.select(app, m)
            } catch (t: Throwable) {
                Log.e(TAG, "download failed", t)
                progress.value = null
                errorText = t.message?.takeIf { it.length < 60 && "http" !in it.lowercase() } ?: "Download failed"
                error.value = key
                which.value = null
            }
        }, "squelch-download").start()
    }

    /** what went wrong with the model in `error` */
    @Volatile var errorText = ""

    private fun fetch(url: String, dest: File, size: Long, onBytes: (Long) -> Unit) {
        val part = File(dest.path + ".part")
        var have = if (part.exists()) part.length() else 0L
        if (have > size) { part.delete(); have = 0 }
        var u = URL(url)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = u.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) {
                u = URL(u, conn.getHeaderField("Location"))
                conn.disconnect()
                continue
            }
            if (code == 200) have = 0 else if (code != 206) error("HTTP $code for $url")
            break
        }
        conn.inputStream.use { input ->
            FileOutputStream(part, have > 0).use { out ->
                val buf = ByteArray(1 shl 16)
                var got = have
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    got += n
                    if (got - lastReport > 1 shl 20) { onBytes(got); lastReport = got }
                }
            }
        }
        check(part.length() == size) { "size mismatch for ${dest.name}: ${part.length()} != $size" }
        check(part.renameTo(dest))
    }
}
