package ca.heeney.squelch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Your dictionary, in the desktop's dictionary.json format. Cleanup reads it on every
 *  dictation; the editor and sync write it. */
object Words {
    data class Entry(val term: String, val heard: List<String>)

    const val HELP = "Your words. 'term' is the correct spelling. 'also_match' lists ways the engine mishears it, fixed automatically."

    /** bumped on every change, for the screen */
    val changed = Observable(0)

    fun file(ctx: Context) = Cleanup.dictFile(ctx)

    fun parse(arr: JSONArray?): List<Entry> {
        if (arr == null) return emptyList()
        val out = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val term = e.optString("term").trim()
            if (term.isEmpty()) continue
            val ms = e.optJSONArray("also_match")
            val heard = (0 until (ms?.length() ?: 0)).map { ms!!.optString(it).trim() }.filter { it.isNotEmpty() }
            out += Entry(term, heard)
        }
        return out
    }

    fun toJson(list: List<Entry>) = JSONArray().apply {
        for (e in list) put(JSONObject().put("term", e.term).put("also_match", JSONArray(e.heard)))
    }

    @Synchronized fun load(ctx: Context): List<Entry> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try { parse(JSONObject(f.readText()).optJSONArray("entries")) } catch (_: Exception) { emptyList() }
    }

    @Synchronized fun save(ctx: Context, list: List<Entry>) {
        val f = file(ctx)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(JSONObject().put("_help", HELP).put("entries", toJson(list)).toString(2))
        tmp.renameTo(f)
        changed.value = changed.value + 1
    }

    /** Add a word, or more ways of mishearing one you have. */
    @Synchronized fun add(ctx: Context, term: String, heard: List<String>) {
        val t = term.trim()
        if (t.isEmpty()) return
        val hs = heard.map { it.trim() }.filter { it.isNotEmpty() && !it.equals(t, true) }
        val list = load(ctx).toMutableList()
        val i = list.indexOfFirst { it.term.equals(t, true) }
        if (i < 0) list += Entry(t, hs.distinctBy { it.lowercase() })
        else {
            val old = list[i]
            list[i] = Entry(t, (old.heard + hs).distinctBy { it.lowercase() })
        }
        save(ctx, list)
        Sync.soon(ctx)
    }

    @Synchronized fun remove(ctx: Context, term: String) {
        save(ctx, load(ctx).filterNot { it.term == term })
        Sync.soon(ctx)
    }
}
