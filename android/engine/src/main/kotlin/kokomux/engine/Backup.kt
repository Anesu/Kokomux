package kokomux.engine

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A backup: one zip file of what can't be made again. In it, as they lie in
 * the app's storage ([root]): books/ (the bookmarks, the books' own copies and
 * the novels' kept chapters), stats/listens.tsv, passages.tsv and voices/ (the
 * cloned voices); and `settings`, the settings as KOKORO_NAME=value lines. The
 * kept audio and the engines' files stay out: they're made or fetched again.
 *
 * Restoring adds to what's on the phone and takes nothing away: a book in
 * both keeps the bookmark that's further on, the listens and passages of both
 * are kept once each, and a file already here is left as it is.
 */
object Backup {
    /** What a restore brought: the backup's settings, and how many books, listens, passages and voices were new or further on. */
    class Restored(val settings: Map<String, String>, val books: Int, val listens: Int, val passages: Int, val voices: Int) {
        /** "3 books, 12 listens, 1 voice" ("Nothing new" when it's all here already). */
        fun inWords(): String = listOf(books to "book", listens to "listen", passages to "passage", voices to "voice")
            .filter { it.first > 0 }.joinToString(", ") { (n, what) -> "$n $what${if (n == 1) "" else "s"}" }.ifEmpty { "Nothing new" }
    }

    private const val MARKER = "kokomux-backup"
    private const val SETTINGS = "settings"
    private const val STATS = "stats/listens.tsv"
    private const val PASSAGES = "passages.tsv"
    private val FOLDERS = listOf("books", "stats", "voices")

    /** Write a backup of [root] and [settings] to [out] (closed when done); [version] is the app's. */
    fun save(root: File, settings: Map<String, String>, version: String, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            fun entry(name: String, changed: Long, bytes: () -> InputStream) {
                zip.putNextEntry(ZipEntry(name).apply { time = changed })
                bytes().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            val now = System.currentTimeMillis()
            entry(MARKER, now) { "version=1\napp=$version\n".byteInputStream() }
            entry(SETTINGS, now) { settings.entries.joinToString("") { (k, v) -> "$k=${v.replace("\n", " ")}\n" }.byteInputStream() }
            val files = FOLDERS.flatMap { File(root, it).walkTopDown().filter { f -> f.isFile } } + File(root, PASSAGES)
            for (f in files) {
                if (!f.isFile || f.name.endsWith(".tmp")) continue
                entry(f.relativeTo(root).invariantSeparatorsPath, f.lastModified()) { f.inputStream() }
            }
        }
    }

    /**
     * Add the backup read from [input] (closed when done) to what's in [root]. It's unpacked beside
     * [root]'s folders first, so a file that isn't a backup changes nothing.
     */
    fun restore(root: File, input: InputStream): Restored {
        val tmp = File(root, "restore.tmp")
        tmp.deleteRecursively()
        try {
            unpack(input, tmp)
            if (!File(tmp, MARKER).isFile) throw IOException("That isn't a kokomux backup")
            val settings = File(tmp, SETTINGS).takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { line ->
                val at = line.indexOf('=')
                if (at > 0) line.substring(0, at) to line.substring(at + 1) else null
            }.toMap()
            return Restored(settings, books(File(tmp, "books"), File(root, "books")), listens(File(tmp, STATS), File(root, STATS)),
                lines(File(tmp, PASSAGES), File(root, PASSAGES)), voices(File(tmp, "voices"), File(root, "voices")))
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Unpack a zip into [dir]: only the names a backup has, none leading out of it. */
    private fun unpack(input: InputStream, dir: File) {
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                val ours = name == MARKER || name == SETTINGS || name == PASSAGES || FOLDERS.any { name.startsWith("$it/") }
                if (e.isDirectory || !ours || name.split('/').any { it == ".." || it.isEmpty() }) continue
                val dest = File(dir, name)
                dest.parentFile.mkdirs()
                dest.outputStream().use { zip.copyTo(it) }
                if (e.time > 0) dest.setLastModified(e.time)
            }
        }
    }

    /** Copy [from] to [to], with its time (which is when a book was last heard). */
    private fun copy(from: File, to: File) {
        to.parentFile.mkdirs()
        from.copyTo(to, overwrite = true)
        to.setLastModified(from.lastModified())
    }

    /**
     * The backup's books into the phone's: every file that isn't here yet (a book's copy, a novel's
     * kept chapters), and a bookmark that's new or further on. A book's own copy is found where this
     * phone keeps it. Returns how many bookmarks came.
     */
    private fun books(from: File, to: File): Int {
        var came = 0
        val theirs = Bookmarks(from)
        val ours = Bookmarks(to)
        for (f in from.walkTopDown().filter { it.isFile }) {
            val dest = File(to, f.relativeTo(from).path)
            if (!f.name.endsWith(".mark") || f.parentFile != from) {
                if (!dest.exists()) copy(f, dest)
                continue
            }
            val mark = theirs.get(f.name.removeSuffix(".mark")) ?: continue
            val here = ours.get(mark.id)
            if (here != null && !further(mark, here)) continue
            val source = if (mark.kind == "file" || mark.kind == "text") File(to, File(mark.source).name).path else mark.source
            ours.save(mark.copy(source = source))
            dest.setLastModified(f.lastModified())
            came++
        }
        return came
    }

    /** Is bookmark [a] further on than [b] (of the same book)? */
    internal fun further(a: Bookmarks.Mark, b: Bookmarks.Mark): Boolean {
        if (a.kind == "novel" || b.kind == "novel") {
            return compareValuesBy(a, b, { it.number }, { it.part.toFloat() / it.parts.coerceAtLeast(1) }, { it.done }) > 0
        }
        return a.progress > b.progress
    }

    /** The backup's listens into the phone's: each kept once, the longer telling of one in both. Returns how many were new. */
    private fun listens(from: File, to: File): Int {
        if (!from.isFile) return 0
        val ours = Sessions(to)
        val here = ours.all().associateBy { it.id }
        var came = 0
        for (s in Sessions(from).all()) {
            val had = here[s.id]
            if (had == null) came++
            if (had == null || s.end > had.end) ours.save(s)
        }
        return came
    }

    /** The lines of [from] that [to] doesn't have (by their first field: when a passage was saved), added to it. Returns how many. */
    private fun lines(from: File, to: File): Int {
        if (!from.isFile) return 0
        fun key(line: String) = line.substringBefore('\t')
        val here = if (to.isFile) to.readLines().map(::key).toSet() else emptySet()
        val fresh = from.readLines().filter { it.isNotBlank() && key(it) !in here }
        if (fresh.isNotEmpty()) {
            to.parentFile?.mkdirs()
            to.appendText(fresh.joinToString("") { "$it\n" })
        }
        return fresh.size
    }

    /** The backup's cloned voices that aren't here (a voice is two files, <hash>.qwen and <hash>.voice). Returns how many. */
    private fun voices(from: File, to: File): Int {
        var came = 0
        for (f in from.listFiles().orEmpty().filter { it.isFile }) {
            val dest = File(to, f.name)
            if (dest.exists()) continue
            copy(f, dest)
            if (f.name.endsWith(".voice")) came++
        }
        return came
    }
}
