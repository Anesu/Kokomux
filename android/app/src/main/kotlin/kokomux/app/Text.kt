package kokomux.app

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kokomux.engine.Book
import kokomux.engine.Chapter
import kokomux.engine.Novel
import kokomux.engine.NovelChapter
import kokomux.engine.Part
import kokomux.engine.ReadError
import kokomux.engine.Speakable
import kokomux.engine.TextSide
import kokomux.engine.Timing
import kokomux.engine.Vocab
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The text side, in Python (app/src/main/python/kokomux_android.py, over
 * engine.py and words.py): reading books and pages, tidying and splitting
 * them, misaki's sounds (espeak-ng's for other languages) and word timings.
 * Calls block; make them on the engine's worker thread.
 */
class Text(private val context: Context) : TextSide {
    private val module: PyObject by lazy {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        val data = espeakData()
        val lib = File(context.applicationInfo.nativeLibraryDir, "libespeak-ng.so")
        Python.getInstance().getModule("kokomux_android").also {
            it.callAttr("setup", lib.path, data.path, context.cacheDir.path, Vocab.symbols)
        }
    }

    override fun read(source: String, name: String, lang: String): Book {
        val got = JSONObject(module.callAttr("read", source, name, lang).toString())
        if (got.has("error")) throw ReadError(got.getString("error"))
        val parts = got.getJSONArray("parts")
        val chapters = got.getJSONArray("chapters")
        val novel = got.optJSONObject("novel")?.let(::novelChapter)
        return Book(
            got.getString("title"),
            (0 until parts.length()).map { parts.getJSONArray(it).let { p -> Part(p.getString(0), p.getDouble(1)) } },
            (0 until chapters.length()).map { chapters.getJSONArray(it).let { c ->
                Chapter(c.getInt(0), c.getString(1), novel?.url.orEmpty(), novel?.number ?: 0) } },
            novel?.let { Novel(it.novelUrl, it.novel, it.next) },
        )
    }

    /** A novel's chapter as read (its details and its text, the label first); null if it isn't out (yet). */
    fun chapter(url: String): Pair<NovelChapter, String>? {
        val got = JSONObject(module.callAttr("chapter", url).toString())
        if (got.has("error")) throw ReadError(got.getString("error"))
        if (got.has("none")) return null
        return novelChapter(got.getJSONObject("novel")) to got.getString("text")
    }

    /** A novel's chapters, from its own page: (number, label, address), in order. */
    fun toc(novelUrl: String, anyChapter: String): List<Triple<Int, String, String>> {
        val got = module.callAttr("toc", novelUrl, anyChapter).toString()
        if (got.startsWith("{")) throw ReadError(JSONObject(got).getString("error"))
        val a = JSONArray(got)
        return (0 until a.length()).map { a.getJSONArray(it).let { c -> Triple(c.getInt(0), c.getString(1), c.getString(2)) } }
    }

    /** Novels found by name: (title, address, latest chapter). */
    fun search(query: String): List<Triple<String, String, String>> {
        val got = module.callAttr("search", query).toString()
        if (got.startsWith("{")) throw ReadError(JSONObject(got).getString("error"))
        val a = JSONArray(got)
        return (0 until a.length()).map { a.getJSONArray(it).let { r -> Triple(r.getString(0), r.getString(1), r.getString(2)) } }
    }

    private fun novelChapter(o: JSONObject) = NovelChapter(
        o.getString("novel"), o.getString("novel_url"), o.getString("url"), o.getString("label"), o.getInt("number"),
        o.optString("next").takeIf { !o.isNull("next") && it.isNotEmpty() },
        o.optString("prev").takeIf { !o.isNull("prev") && it.isNotEmpty() },
    )

    override fun speakable(text: String, lang: String): Speakable {
        val got = JSONObject(module.callAttr("speakable", text, lang).toString())
        return Speakable(got.getString("ps"), if (got.isNull("per")) "null" else got.getJSONArray("per").toString())
    }

    override fun wordRows(text: String, lang: String, speakable: Speakable, timings: List<Timing>): String =
        module.callAttr(
            "word_rows", text, lang, speakable.perWord,
            String(timings.map { it.phoneme }.toCharArray()),
            timings.map { it.start }.toDoubleArray(), timings.map { it.end }.toDoubleArray(),
        ).toString()

    /** The parts [text] is read in (engine.py's normalise and split_text). */
    fun parts(text: String, lang: String): List<Part> {
        val list = JSONArray(module.callAttr("parts", text, lang).toString())
        return (0 until list.length()).map { list.getJSONArray(it).let { p -> Part(p.getString(0), p.getDouble(1)) } }
    }

    /** A part's sounds, as engine.py makes them. */
    fun sounds(part: String, lang: String): String = module.callAttr("sounds", part, lang).toString()

    /** Load misaki and spaCy for [lang]; the seconds it took. */
    fun warm(lang: String): Double = module.callAttr("warm", lang).toDouble()

    /** pronunciation.json's sentences through misaki here (see kokomux_android.check). */
    fun check(): String {
        val expected = context.assets.open("pronunciation.json").use { it.readBytes().toString(Charsets.UTF_8) }
        return module.callAttr("check", expected).toString()
    }

    /** espeak-ng's data, copied out of the app once per install (espeak reads it from a folder). */
    private fun espeakData(): File {
        val dir = File(context.filesDir, "espeak-ng-data")
        val stamp = File(dir, ".installed")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (stamp.exists() && stamp.readText() == version) return dir
        dir.deleteRecursively()
        copyAssets("espeak-ng-data", dir)
        stamp.writeText(version)
        return dir
    }

    private fun copyAssets(path: String, dest: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (child in children) copyAssets("$path/$child", File(dest, child))
    }
}
