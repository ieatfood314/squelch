package ca.heeney.squelch

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps the dictionary and analytics in step with your other devices through a
 *  squelch-sync-server hub (the desktop package has it). The hub does the merging:
 *  we send the dictionary as it is and as it was at the last sync, plus new
 *  analytics, and get back the merged dictionary and everyone else's analytics. */
object Sync {
    enum class Status { Off, Syncing, Synced, Failed }
    val status = Observable(Status.Off)
    @Volatile var lastOk = 0L          // wall clock, ms
        private set
    @Volatile var lastError = ""
        private set

    private val busy = AtomicBoolean(false)
    @Volatile private var again = false
    @Volatile private var lastTry = 0L  // uptime, ms

    fun url(): String? {
        val u = Prefs.syncUrl.trim().trimEnd('/')
        if (u.isEmpty()) return null
        return if ("://" in u) u else "https://$u"
    }

    fun deviceId(ctx: Context): String {
        val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "0"
        return "android-" + id.take(8)
    }

    fun deviceName(ctx: Context): String =
        Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    private fun stateFile(ctx: Context) = File(ctx.filesDir, "sync-state.json")
    private fun state(ctx: Context) = try { JSONObject(stateFile(ctx).readText()) } catch (_: Exception) { JSONObject() }

    /** device id to name, for everyone we've had analytics from */
    fun names(ctx: Context): Map<String, String> {
        val n = state(ctx).optJSONObject("names") ?: return emptyMap()
        return n.keys().asSequence().associateWith { n.optString(it) }
    }

    fun init(ctx: Context) {
        val s = state(ctx)
        lastOk = s.optLong("synced_ms")
        status.value = when {
            url() == null -> Status.Off
            lastOk > 0 -> Status.Synced
            else -> Status.Off
        }
    }

    /** Sync in the background, unless one ran less than minGapMs ago. */
    fun soon(ctx: Context, minGapMs: Long = 0) {
        if (url() == null) { status.value = Status.Off; return }
        if (SystemClock.uptimeMillis() - lastTry < minGapMs) return
        if (!busy.compareAndSet(false, true)) { again = true; return }
        lastTry = SystemClock.uptimeMillis()
        val app = ctx.applicationContext
        Thread({
            try {
                status.value = Status.Syncing
                run(app)
                lastOk = System.currentTimeMillis()
                status.value = Status.Synced
            } catch (t: Throwable) {
                Log.w(TAG, "sync failed: $t")
                lastError = t.message ?: t.toString()
                status.value = Status.Failed
            } finally {
                busy.set(false)
                // Something changed mid-sync (a dictation just finished): go again.
                if (again) { again = false; soon(app) }
            }
        }, "squelch-sync").start()
    }

    private fun lines(f: File): List<JSONObject> {
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { try { JSONObject(it) } catch (_: Exception) { null } }
    }

    private fun run(ctx: Context) {
        val url = url() ?: return
        val st = state(ctx)
        val pushed = st.optDouble("pushed", 0.0)
        val base = st.optJSONArray("base")
        val dict = Words.file(ctx)
        val mtime = if (dict.exists()) dict.lastModified() else 0L
        val mine = Words.load(ctx)

        val events = JSONArray()
        synchronized(Analytics) { lines(Analytics.file(ctx)) }
            .filter { it.optDouble("ts", 0.0) > pushed }.forEach { events.put(it) }
        val have = JSONObject()
        val dir = Analytics.others(ctx)
        dir.listFiles()?.filter { it.name.endsWith(".jsonl") }?.forEach { f ->
            val last = lines(f).maxOfOrNull { it.optDouble("ts", 0.0) } ?: 0.0
            have.put(f.name.removeSuffix(".jsonl"), last)
        }
        val body = JSONObject()
            .put("device", deviceId(ctx))
            .put("name", deviceName(ctx))
            .put("dictionary", JSONObject().put("base", base ?: JSONObject.NULL).put("mine", Words.toJson(mine)))
            .put("stats", JSONObject().put("events", events).put("have", have))

        val conn = URL("$url/v1/sync").openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 30000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode != 200) error("hub said ${conn.responseCode}")
        val resp = JSONObject(conn.inputStream.bufferedReader().readText())

        resp.optJSONArray("dictionary")?.let { arr ->
            val merged = Words.parse(arr)
            synchronized(Words) {
                // Edited while we were away: keep the edit and the old base, so the next sync sends it.
                if ((if (dict.exists()) dict.lastModified() else 0L) == mtime) {
                    if (merged != mine) Words.save(ctx, merged)
                    st.put("base", arr)
                }
            }
        }
        resp.optJSONObject("stats")?.let { s ->
            if (s.has("pushed")) st.put("pushed", s.getDouble("pushed"))
            val names = st.optJSONObject("names") ?: JSONObject()
            val devs = s.optJSONObject("devices") ?: JSONObject()
            var got = 0
            for (id in devs.keys()) {
                val safe = id.replace(Regex("[^A-Za-z0-9._-]"), "-")
                val d = devs.getJSONObject(id)
                names.put(safe, d.optString("name", safe))
                val after = have.optDouble(safe, 0.0)
                val evs = d.optJSONArray("events") ?: continue
                val sb = StringBuilder()
                for (i in 0 until evs.length()) {
                    val e = evs.optJSONObject(i) ?: continue
                    if (e.optDouble("ts", 0.0) > after) { sb.append(e.toString()).append('\n'); got++ }
                }
                if (sb.isNotEmpty()) { dir.mkdirs(); File(dir, "$safe.jsonl").appendText(sb.toString()) }
            }
            st.put("names", names)
            if (got > 0 || events.length() > 0) Analytics.changed.value = Analytics.changed.value + 1
        }
        st.put("synced_ms", System.currentTimeMillis())
        val tmp = File(stateFile(ctx).path + ".tmp")
        tmp.writeText(st.toString())
        tmp.renameTo(stateFile(ctx))
        Log.i(TAG, "synced: sent ${events.length()} dictations, ${mine.size} words")
    }
}
