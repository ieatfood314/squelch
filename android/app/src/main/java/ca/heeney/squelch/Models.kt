package ca.heeney.squelch

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A speech model: a Hugging Face repo with an encoder, decoder, joiner and tokens.txt
 *  (what sherpa-onnx calls a transducer). files maps each file to its size in bytes,
 *  so a half-finished download never counts as installed. */
class Model(
    val key: String,
    val name: String,
    val about: String,
    val repo: String,
    val files: Map<String, Long>,
    val custom: Boolean = false,
) {
    val bytes get() = files.values.sum()
    fun dir(ctx: Context) = File(ctx.filesDir, key)
    fun installed(ctx: Context) = files.all { (n, size) -> File(dir(ctx), n).length() == size }
    fun url(file: String) = "https://huggingface.co/$repo/resolve/main/$file"

    private fun pick(prefix: String) =
        files.keys.filter { it.startsWith(prefix) && it.endsWith(".onnx") }
            .minByOrNull { if ("int8" in it) 0 else 1 }
    val encoder get() = pick("encoder")
    val decoder get() = pick("decoder")
    val joiner get() = pick("joiner")
    /** NeMo models (Parakeet) need saying so; others are told apart by sherpa-onnx itself. */
    val modelType get() = if (Regex("nemo|parakeet", RegexOption.IGNORE_CASE).containsMatchIn(repo)) "nemo_transducer" else ""

    fun size() = "%.0f MB".format(bytes / 1e6)
}

object Models {
    val ENGLISH = Model(
        "parakeet-tdt-0.6b-v2-int8", "English", "Parakeet v2. The most accurate in English.",
        "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
        linkedMapOf(
            "encoder.int8.onnx" to 652_282_309L,
            "decoder.int8.onnx" to 7_257_753L,
            "joiner.int8.onnx" to 1_739_080L,
            "tokens.txt" to 9_384L,
        ),
    )
    val MULTILINGUAL = Model(
        "parakeet-tdt-0.6b-v3-int8", "Multilingual", "Parakeet v3. English and 24 other European languages.",
        "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        linkedMapOf(
            "encoder.int8.onnx" to 652_282_294L,
            "decoder.int8.onnx" to 11_845_274L,
            "joiner.int8.onnx" to 6_355_277L,
            "tokens.txt" to 93_939L,
        ),
    )
    val FAST = Model(
        "parakeet-tdt-110m-en", "English fast", "Parakeet 110M. About five times quicker, less accurate.",
        "csukuangfj/sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000",
        linkedMapOf(
            "encoder.onnx" to 456_050_698L,
            "decoder.onnx" to 15_753_086L,
            "joiner.onnx" to 5_596_854L,
            "tokens.txt" to 9_953L,
        ),
    )
    val BUILT_IN = listOf(ENGLISH, MULTILINGUAL, FAST)

    private const val MANIFEST = "squelch-model.json"

    /** Built-ins plus any Hugging Face models you've added. */
    fun all(ctx: Context): List<Model> = BUILT_IN + custom(ctx)

    private fun custom(ctx: Context): List<Model> =
        (ctx.filesDir.listFiles() ?: emptyArray()).filter { it.isDirectory }.sortedBy { it.name }
            .mapNotNull { d -> File(d, MANIFEST).takeIf { it.exists() }?.let { read(d.name, it) } }

    private fun read(key: String, f: File): Model? = try {
        val j = JSONObject(f.readText())
        val files = linkedMapOf<String, Long>()
        val fj = j.getJSONObject("files")
        for (n in fj.keys()) files[n] = fj.getLong(n)
        val repo = j.getString("repo")
        Model(key, repo.substringAfter('/').removePrefix("sherpa-onnx-"), repo, repo, files, custom = true)
    } catch (_: Exception) { null }

    fun selected(ctx: Context): Model {
        val k = Prefs.model
        return all(ctx).firstOrNull { it.key == k } ?: ENGLISH
    }

    fun keyFor(repo: String) = "hf-" + repo.replace('/', '-').replace(Regex("[^A-Za-z0-9._-]"), "")

    /** "user/name", or a huggingface.co link to it */
    fun parseRepo(input: String): String? {
        val s = input.trim().removePrefix("https://").removePrefix("http://").removePrefix("huggingface.co/")
            .removeSuffix("/").split('/')
        if (s.size < 2 || s[0].isBlank() || s[1].isBlank()) return null
        return "${s[0]}/${s[1]}"
    }

    /** Look the repo up on Hugging Face and remember which files it needs. Network; not on main. */
    fun resolve(ctx: Context, repo: String): Model {
        val conn = URL("https://huggingface.co/api/models/$repo/tree/main").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 30000
        if (conn.responseCode != 200) error("Not found on Hugging Face")
        val arr = org.json.JSONArray(conn.inputStream.bufferedReader().readText())
        val sizes = linkedMapOf<String, Long>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("type") == "file") sizes[o.getString("path")] = o.optLong("size")
        }
        fun best(prefix: String) = sizes.keys.filter { it.startsWith(prefix) && it.endsWith(".onnx") }
            .minByOrNull { if ("int8" in it) 0 else 1 }
        val parts = listOf(best("encoder"), best("decoder"), best("joiner"), "tokens.txt".takeIf { it in sizes })
        if (parts.any { it == null }) error("Needs encoder, decoder, joiner and tokens.txt")
        val files = linkedMapOf<String, Long>()
        for (p in parts) files[p!!] = sizes.getValue(p)
        // Big fp32 exports keep their weights in side files next to the graph.
        for ((n, s) in sizes) if (n.endsWith(".weights") || n.endsWith(".onnx.data")) files[n] = s
        val key = keyFor(repo)
        val dir = File(ctx.filesDir, key).apply { mkdirs() }
        val fj = JSONObject()
        files.forEach { (n, s) -> fj.put(n, s) }
        File(dir, MANIFEST).writeText(JSONObject().put("repo", repo).put("files", fj).toString())
        return read(key, File(dir, MANIFEST))!!
    }

    fun remove(ctx: Context, m: Model) {
        if (m.key == selected(ctx).key) return
        m.dir(ctx).deleteRecursively()
    }
}
