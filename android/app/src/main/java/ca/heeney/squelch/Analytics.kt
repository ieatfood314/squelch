package ca.heeney.squelch

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One line per dictation in stats.jsonl, the same fields as the desktop's, so the two
 *  can share a dashboard. Other devices' lines arrive through Sync in stats.d/. */
object Analytics {
    val changed = Observable(0)

    fun file(ctx: Context) = File(ctx.filesDir, "stats.jsonl")
    fun others(ctx: Context) = File(ctx.filesDir, "stats.d")

    fun record(ctx: Context, text: String, audioS: Float, waitS: Float, model: String) {
        val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
        val j = JSONObject()
            .put("outcome", if (words > 0) "ok" else "empty")
            .put("engine", model)
            .put("audio_s", Math.round(audioS * 100) / 100.0)
            .put("words", words)
            .put("transcribe_s", Math.round(waitS * 1000) / 1000.0)
            .put("ts", System.currentTimeMillis() / 1000.0)
        synchronized(this) { file(ctx).appendText(j.toString() + "\n") }
        changed.value = changed.value + 1
    }

    class Row(val device: String, val ts: Double, val words: Int, val audio: Double, val lat: Double)

    private fun read(f: File, device: String, into: MutableList<Row>) {
        if (!f.exists()) return
        f.forEachLine { line ->
            try {
                val j = JSONObject(line)
                if (j.optString("outcome") == "ok") into += Row(
                    device, j.optDouble("ts", 0.0), j.optInt("words"),
                    j.optDouble("audio_s", 0.0), j.optDouble("transcribe_s", 0.0),
                )
            } catch (_: Exception) {}
        }
    }

    /** Every device's successful dictations, oldest first. */
    fun load(ctx: Context): List<Row> {
        val rows = ArrayList<Row>()
        read(file(ctx), Sync.deviceName(ctx), rows)
        val names = Sync.names(ctx)
        others(ctx).listFiles()?.filter { it.name.endsWith(".jsonl") }?.forEach {
            val id = it.name.removeSuffix(".jsonl")
            read(it, names[id] ?: id, rows)
        }
        rows.sortBy { it.ts }
        return rows
    }

    class Summary(
        val words: Int, val dictations: Int, val wpm: Double, val avgLat: Double,
        val recentWpm: Double, val recentWords: Int, val recentN: Int, val recentLat: Double,
        /** newest first: day, words, dictations, minutes */
        val days: List<Day>,
        /** most words first */
        val devices: List<Pair<String, Int>>,
    )
    class Day(val date: LocalDate, val words: Int, val dictations: Int, val minutes: Double)

    /** The desktop dashboard's numbers. */
    fun summarize(rows: List<Row>, now: Double = System.currentTimeMillis() / 1000.0): Summary {
        val words = rows.sumOf { it.words }
        val audio = rows.sumOf { it.audio }
        val recent = rows.filter { it.ts >= now - 3600 }
        val rw = recent.sumOf { it.words }
        val ra = recent.sumOf { it.audio }
        val last10 = rows.takeLast(10)
        val zone = ZoneId.systemDefault()
        val days = rows.groupBy { Instant.ofEpochMilli((it.ts * 1000).toLong()).atZone(zone).toLocalDate() }
            .map { (d, rs) -> Day(d, rs.sumOf { it.words }, rs.size, rs.sumOf { it.audio } / 60) }
            .sortedByDescending { it.date }.take(7)
        val devices = rows.groupBy { it.device }.map { (d, rs) -> d to rs.sumOf { it.words } }
            .sortedByDescending { it.second }
        return Summary(
            words, rows.size,
            if (audio > 0) words * 60 / audio else 0.0,
            if (rows.isNotEmpty()) rows.sumOf { it.lat } / rows.size else 0.0,
            if (ra > 0) rw * 60 / ra else 0.0, rw, recent.size,
            if (last10.isNotEmpty()) last10.sumOf { it.lat } / last10.size else 0.0,
            days, devices,
        )
    }
}
