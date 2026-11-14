package kokomux.app

import java.io.File
import kokomux.engine.Books
import kokomux.engine.NovelChapter
import kokomux.engine.ReadError
import org.json.JSONArray
import org.json.JSONObject

/**
 * Web novels' chapters, kept as they're fetched: books/<novel id>/ has each
 * chapter read (<number>.json: its details and text), and novel.json what's
 * known of the novel: its title and address, its chapters (number, label,
 * address), the latest chapter out, and the chapters already said to be new.
 * Chapters are fetched ahead of the listener ([ahead]) so the next few can be
 * heard offline, and checked for daily ([check]).
 */
class Novels(private val books: File, private val text: Text, private val exclusive: (() -> Any?) -> Any?) {
    /** What's known of a novel. */
    data class Known(val url: String, val title: String, val toc: List<Triple<Int, String, String>>, val latest: Int,
                     val told: Int)

    private fun dir(novelUrl: String) = File(books, Books.id(novelUrl))

    @Synchronized
    fun known(novelUrl: String): Known? {
        val f = File(dir(novelUrl), "novel.json").takeIf { it.exists() } ?: return null
        val o = JSONObject(f.readText())
        val toc = o.optJSONArray("toc") ?: JSONArray()
        return Known(o.getString("url"), o.getString("title"),
            (0 until toc.length()).map { toc.getJSONArray(it).let { c -> Triple(c.getInt(0), c.getString(1), c.getString(2)) } },
            o.optInt("latest"), o.optInt("told"))
    }

    @Synchronized
    private fun save(k: Known) {
        val d = dir(k.url).apply { mkdirs() }
        val toc = JSONArray().apply { k.toc.forEach { (n, l, u) -> put(JSONArray().put(n).put(l).put(u)) } }
        File(d, "novel.json.tmp").writeText(JSONObject().put("url", k.url).put("title", k.title).put("toc", toc)
            .put("latest", k.latest).put("told", k.told).toString())
        File(d, "novel.json.tmp").renameTo(File(d, "novel.json"))
    }

    /** Note [c] as read: the novel, the chapter in its list, and the latest out so far. */
    private fun note(c: NovelChapter) {
        val k = known(c.novelUrl) ?: Known(c.novelUrl, c.novel, emptyList(), 0, 0)
        val toc = if (k.toc.any { it.first == c.number }) k.toc else (k.toc + Triple(c.number, c.label, c.url)).sortedBy { it.first }
        save(k.copy(toc = toc, latest = maxOf(k.latest, c.number)))
    }

    private fun kept(novelUrl: String, number: Int) = File(dir(novelUrl), "$number.json")

    /** A chapter kept here (by its address), if it is. */
    private fun keptAt(url: String): Pair<NovelChapter, String>? {
        val novelUrl = NovelUrl.of(url)
        val k = known(novelUrl) ?: return null
        val n = k.toc.firstOrNull { it.third == url }?.first ?: return null
        val f = kept(novelUrl, n).takeIf { it.exists() } ?: return null
        val o = JSONObject(f.readText())
        val c = o.getJSONObject("chapter")
        return NovelChapter(c.getString("novel"), c.getString("novelUrl"), c.getString("url"), c.getString("label"),
            c.getInt("number"), c.optString("next").ifEmpty { null }, c.optString("prev").ifEmpty { null }) to o.getString("text")
    }

    /** The chapter at [url]: kept, or fetched (and kept); null if it isn't out yet. */
    fun chapter(url: String): Pair<NovelChapter, String>? {
        keptAt(url)?.let { return it }
        @Suppress("UNCHECKED_CAST")
        val got = exclusive { text.chapter(url) } as Pair<NovelChapter, String>? ?: return null
        val (c, body) = got
        dir(c.novelUrl).mkdirs()
        val o = JSONObject().put("chapter", JSONObject().put("novel", c.novel).put("novelUrl", c.novelUrl).put("url", c.url)
            .put("label", c.label).put("number", c.number).put("next", c.next.orEmpty()).put("prev", c.prev.orEmpty()))
            .put("text", body)
        kept(c.novelUrl, c.number).writeText(o.toString())
        note(c)
        return got
    }

    /** The [count] chapters after [c], fetched and kept so they can be heard offline; quietly stops where there's no more. */
    fun ahead(c: NovelChapter, count: Int = AHEAD) {
        var next = c.next
        repeat(count) {
            val url = next ?: return
            next = try {
                chapter(url)?.first?.next ?: return
            } catch (e: ReadError) {
                return
            }
        }
    }

    /** The novel's list of chapters, from its own page (kept); the last list kept if the page can't be read. */
    fun toc(c: NovelChapter): List<Triple<Int, String, String>> {
        val k = known(c.novelUrl) ?: Known(c.novelUrl, c.novel, emptyList(), 0, 0)
        return try {
            @Suppress("UNCHECKED_CAST")
            val list = exclusive { text.toc(c.novelUrl, c.url) } as List<Triple<Int, String, String>>
            val merged = (list + k.toc.filter { t -> list.none { it.first == t.first } }).sortedBy { it.first }
            save(k.copy(toc = merged, latest = maxOf(k.latest, merged.lastOrNull()?.first ?: 0)))
            merged
        } catch (e: ReadError) {
            k.toc
        }
    }

    /**
     * The chapters out after [number] (the last one heard or kept) for the novel [c] is in, each
     * fetched and kept; returns how many are new since last [told], and notes them told.
     */
    fun check(c: NovelChapter): Int {
        var at = c
        while (true) {
            val url = at.next ?: break
            at = try {
                chapter(url)?.first ?: break
            } catch (e: ReadError) {
                break
            }
        }
        val k = known(c.novelUrl) ?: return 0
        val new = k.latest - maxOf(k.told, c.number)
        save(k.copy(told = k.latest))
        return maxOf(0, new)
    }

    /** Forget a novel's kept chapters. */
    fun forget(novelUrl: String) {
        dir(novelUrl).deleteRecursively()
    }

    companion object {
        /** Chapters kept ahead of the one being heard. */
        const val AHEAD = 3
    }
}

/** A novel's own address from one of its chapters' (engine.py's novel_url_of). */
object NovelUrl {
    fun of(chapterUrl: String): String {
        val u = java.net.URI(chapterUrl)
        val parts = u.path.split('/').filter { it.isNotEmpty() }
        val k = parts.indexOfFirst { it.lowercase().let { p -> p.startsWith("chapter") || p.startsWith("chap-") || p.startsWith("ch-") } }
        val keep = if (k >= 0) parts.take(k) else parts.dropLast(1)
        return "${u.scheme}://${u.rawAuthority}/" + keep.joinToString("/")
    }
}
