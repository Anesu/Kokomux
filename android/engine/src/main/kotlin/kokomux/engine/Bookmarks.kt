package kokomux.engine

import java.io.File
import kotlin.math.roundToLong

/**
 * Where each book was left, kept as the Termux version keeps them: one
 * <book id>.mark file of key=value lines (part, parts, title, kind, source,
 * voice, speed, lang). A bookmark goes once a book has been heard to the end.
 */
class Bookmarks(private val dir: File) {
    /** A book's bookmark. [kind] is "file", "url" or "text"; [source] the file in the app's storage or the address. */
    data class Mark(
        val id: String, val part: Int, val parts: Int, val title: String, val kind: String,
        val source: String, val voice: String, val speed: Float, val lang: String,
        val lastHeard: Long = 0,
    ) {
        /** How far through, from 0 to 1. */
        val progress get() = if (parts > 1) (part - 1).toFloat() / (parts - 1) else 0f
    }

    /** Keep [mark], replacing the book's last one. */
    fun save(mark: Mark) {
        dir.mkdirs()
        val file = File(dir, "${mark.id}.mark")
        val tmp = File(dir, "${mark.id}.mark.tmp")
        tmp.writeText(
            listOf("part" to mark.part, "parts" to mark.parts, "title" to mark.title.replace("\n", " "),
                "kind" to mark.kind, "source" to mark.source, "voice" to mark.voice, "speed" to mark.speed,
                "lang" to mark.lang).joinToString("") { (k, v) -> "$k=$v\n" },
        )
        if (!tmp.renameTo(file)) error("Couldn't save the bookmark")
    }

    /** A book's bookmark, if it has one. */
    fun get(id: String): Mark? = read(File(dir, "$id.mark"))

    /** Every bookmark, the book heard most recently first. */
    fun all(): List<Mark> = dir.listFiles { f -> f.name.endsWith(".mark") }.orEmpty()
        .mapNotNull { read(it) }.sortedByDescending { it.lastHeard }

    /** Forget a book's bookmark. */
    fun remove(id: String) {
        File(dir, "$id.mark").delete()
    }

    private fun read(file: File): Mark? {
        if (!file.isFile) return null
        val kv = file.readLines().mapNotNull { line ->
            val at = line.indexOf('=')
            if (at > 0) line.substring(0, at) to line.substring(at + 1) else null
        }.toMap()
        return Mark(
            id = file.name.removeSuffix(".mark"),
            part = kv["part"]?.toIntOrNull() ?: 1, parts = kv["parts"]?.toIntOrNull() ?: 1,
            title = kv["title"].orEmpty(), kind = kv["kind"].orEmpty(), source = kv["source"].orEmpty(),
            voice = kv["voice"] ?: "af_heart", speed = kv["speed"]?.toFloatOrNull() ?: 1f,
            lang = kv["lang"] ?: "en-us", lastHeard = file.lastModified(),
        )
    }

    companion object {
        /**
         * The part to start at from a bookmark, as engine.py's run_job finds it: a
         * bookmark made when the book had another number of parts keeps the same
         * place as a share of the text, and one past the end starts again.
         */
        fun startAt(part: Int, was: Int, parts: Int): Int {
            var first = part
            if (was > 1 && was != parts) first = 1 + Math.rint((first - 1).toDouble() * parts / was).roundToLong().toInt()
            return if (first in 1..parts) first else 1
        }
    }
}
