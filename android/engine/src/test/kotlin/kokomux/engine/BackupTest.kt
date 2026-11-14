package kokomux.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A backup saved from one phone and restored onto another: everything comes, and nothing here is lost. */
class BackupTest {
    private fun phone(): File = Files.createTempDirectory("kokomux-phone").toFile()

    private fun mark(id: String, part: Int, parts: Int = 10, kind: String = "url", source: String = "https://example.org/$id") =
        Bookmarks.Mark(id, part, parts, "Book $id", kind, source, "bf_emma", 1.1f, "en-gb")

    private fun listen(id: Long, minutes: Int) =
        Session(id, "road", "The Long Road", id * 1000, id * 1000 + minutes * 60_000L, minutes * 60.0, "Part 1 of 9", "Part 4 of 9", 900, 0, 5000)

    private fun backupOf(root: File, settings: Map<String, String> = emptyMap()): ByteArray =
        ByteArrayOutputStream().also { Backup.save(root, settings, "2.0.0", it) }.toByteArray()

    /** A phone with two books (one a file), a novel's kept chapter, two listens, a passage and a cloned voice. */
    private fun used(): File = phone().also { root ->
        val books = File(root, "books").apply { mkdirs() }
        File(books, "aaaa.epub").writeText("an epub")
        Bookmarks(books).save(mark("aaaa", 4, kind = "file", source = "/elsewhere/files/books/aaaa.epub"))
        Bookmarks(books).save(mark("bbbb", 7))
        File(books, "cccc").mkdirs()
        File(books, "cccc/12.json").writeText("{}")
        File(books, "opened.tmp").writeText("half a file")
        Sessions(File(root, "stats/listens.tsv")).apply { save(listen(1, 10)); save(listen(2, 20)) }
        File(root, "passages.tsv").writeText("100\troad\tThe Long Road\tOne\t3\tShe saw the sea.\n")
        File(root, "voices").mkdirs()
        File(root, "voices/ffff.qwen").writeText("a voice")
        File(root, "voices/ffff.voice").writeText("qwen:en-us:Anna")
        File(root, "kokomux-cache/aaaa").mkdirs()
        File(root, "kokomux-cache/aaaa/0001.wav").writeText("audio")
    }

    @Test
    fun aNewPhoneGetsEverything() {
        val backup = backupOf(used(), mapOf("KOKORO_VOICE" to "bf_emma", "KOKORO_SPEED" to "1.1"))
        val root = phone()
        val got = Backup.restore(root, ByteArrayInputStream(backup))
        assertEquals("2 books, 2 listens, 1 passage, 1 voice", got.inWords())
        assertEquals(mapOf("KOKORO_VOICE" to "bf_emma", "KOKORO_SPEED" to "1.1"), got.settings)
        val marks = Bookmarks(File(root, "books"))
        assertEquals(7, marks.get("bbbb")?.part)
        // The book's own copy came, and its bookmark points at it here.
        assertEquals("an epub", File(root, "books/aaaa.epub").readText())
        assertEquals(File(root, "books/aaaa.epub").path, marks.get("aaaa")?.source)
        assertTrue(File(root, "books/cccc/12.json").exists())
        assertEquals(2, Sessions(File(root, "stats/listens.tsv")).all().size)
        assertEquals("qwen:en-us:Anna", File(root, "voices/ffff.voice").readText())
        // What can be made again, and half-written files, stay out.
        assertFalse(File(root, "kokomux-cache").exists())
        assertFalse(File(root, "books/opened.tmp").exists())
        assertFalse(File(root, "restore.tmp").exists())
    }

    @Test
    fun restoringAddsAndKeepsWhatIsFurtherOn() {
        val backup = backupOf(used())
        val root = phone()
        val marks = Bookmarks(File(root, "books"))
        marks.save(mark("aaaa", 9, kind = "file", source = File(root, "books/aaaa.epub").path))   // further on here
        marks.save(mark("bbbb", 2))                                                                  // further on in the backup
        marks.save(mark("dddd", 5))                                                                  // only here
        Sessions(File(root, "stats/listens.tsv")).apply { save(listen(2, 45)); save(listen(3, 5)) }
        File(root, "passages.tsv").writeText("100\troad\tThe Long Road\tOne\t3\tShe saw the sea.\n200\troad\tThe Long Road\tTwo\t8\tA bell rang.\n")

        val got = Backup.restore(root, ByteArrayInputStream(backup))
        assertEquals("1 book, 1 listen, 1 voice", got.inWords())
        assertEquals(9, marks.get("aaaa")?.part)
        assertEquals(7, marks.get("bbbb")?.part)
        assertEquals(5, marks.get("dddd")?.part)
        val listens = Sessions(File(root, "stats/listens.tsv")).all()
        assertEquals(listOf(1L, 2L, 3L), listens.map { it.id })
        assertEquals(45 * 60.0, listens[1].seconds, 0.0)             // the longer telling of the listen in both
        assertEquals(2, File(root, "passages.tsv").readLines().size)

        // Restoring the same backup again brings nothing more.
        assertEquals("Nothing new", Backup.restore(root, ByteArrayInputStream(backup)).inWords())
    }

    @Test
    fun aNovelIsFurtherOnByItsChapter() {
        fun novel(number: Int, part: Int, done: Boolean = false) = mark("n", part, 20, kind = "novel").copy(number = number, done = done)
        assertTrue(Backup.further(novel(8, 1), novel(7, 19)))
        assertTrue(Backup.further(novel(7, 12), novel(7, 3)))
        assertTrue(Backup.further(novel(7, 20, done = true), novel(7, 20)))
        assertFalse(Backup.further(novel(7, 3), novel(7, 3)))
    }

    @Test
    fun anotherZipChangesNothing() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                for (name in listOf("books/eeee.mark", "../outside.txt", "notes.txt")) {
                    z.putNextEntry(ZipEntry(name)); z.write("part=3\n".toByteArray()); z.closeEntry()
                }
            }
        }.toByteArray()
        val root = phone()
        assertThrows(IOException::class.java) { Backup.restore(root, ByteArrayInputStream(zip)) }
        assertEquals(emptyList<String>(), root.list().orEmpty().toList())
        assertFalse(File(root.parentFile, "outside.txt").exists())
    }
}
