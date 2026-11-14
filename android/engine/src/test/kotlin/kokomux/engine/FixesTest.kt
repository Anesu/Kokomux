package kokomux.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Your own pronunciations: what the voice is given, and the book's words lit all the same. */
class FixesTest {
    private val fixes = Fixes(listOf("Xiao Yan" to "Shao Yen", "Xiao" to "Shao", "café" to "kaffay", " " to "nothing"))

    @Test
    fun aWordIsRespeltWholeInAnyCapitals() {
        assertEquals("Shao Yen met Shao at the kaffay.", fixes.spoken("Xiao Yan met XIAO at the Café.").text)
        // Not inside another word, and the book's text is given as it is when nothing in it is yours.
        assertEquals("Xiaolong and the cafés.", fixes.spoken("Xiaolong and the cafés.").text)
        assertTrue(Fixes(emptyList()).isEmpty)
        assertFalse(fixes.isEmpty)
    }

    /** Each whitespace-separated word of [text] said for 100 ms, as words.player_rows writes them. */
    private fun rowsOf(text: String): String {
        val rows = StringBuilder("confidence\t1.0\n")
        Regex("\\S+").findAll(text).forEachIndexed { i, m ->
            val a = text.substring(0, m.range.first).toByteArray().size
            val word = m.value.trimEnd('.', ',')
            rows.append("${i * 100}\t${i * 100 + 100}\t$a\t${a + word.toByteArray().size}\n")
        }
        return rows.toString()
    }

    /** The words a part's rows light, cut from [text] by their bytes, with when. */
    private fun lit(text: String, rows: String): List<String> {
        val bytes = text.toByteArray()
        return rows.lines().drop(1).filter { it.isNotEmpty() }.map { row ->
            val f = row.split('\t')
            "${f[0]}-${f[1]} ${String(bytes, f[2].toInt(), f[3].toInt() - f[2].toInt())}"
        }
    }

    @Test
    fun theBooksOwnWordsAreLitWhileTheirRespellingIsSpoken() {
        val text = "At the café, Xiao Yan met Élise."
        val spoken = fixes.spoken(text)
        assertEquals("At the kaffay, Shao Yen met Élise.", spoken.text)
        val rows = fixes.rows(rowsOf(spoken.text), spoken)
        assertTrue(rows.startsWith("confidence\t1.0\n"))
        assertEquals(listOf("0-100 At", "100-200 the", "200-300 café", "300-500 Xiao Yan", "500-600 met", "600-700 Élise"), lit(text, rows))
    }

    @Test
    fun aVoiceWithFixesSaysTheRespellingAndKeepsTheTimesOnTheBook() {
        val heard = mutableListOf<String>()
        val voice = Speaker { t -> heard += t; Said(FloatArray(240), Audio.RATE, rowsOf(t)) }
        val fixed = FixedSpeaker(voice, fixes)
        val said = fixed.say("Xiao Yan left.")
        assertEquals(listOf("Shao Yen left."), heard)
        assertEquals(listOf("0-200 Xiao Yan", "200-300 left"), lit("Xiao Yan left.", said.rows))
        assertEquals("Shao Yen left.", fixed.spoken("Xiao Yan left."))
        assertEquals("No names here.", voice.spoken("No names here."))
    }

    @Test
    fun aKeptPartIsMadeAgainWhenHowItIsSaidChanges() {
        val cache = Files.createTempDirectory("kokomux-fixes").toFile()
        val book = Book("A book", listOf(Part("Xiao Yan left.", 0.0), Part("Nobody saw.", 0.0)), listOf(Chapter(1, "One")))
        val heard = mutableListOf<String>()
        val voice = Speaker { t -> heard += t; Said(FloatArray(2400), Audio.RATE, rowsOf(t)) }
        fun make(speaker: Speaker) = mutableListOf<Maker.Made>().also { made -> Maker(book, cache, speaker).run(1, { 0 }, { false }) { made += it } }

        assertEquals(listOf(false, false), make(voice).map { it.kept })
        // With a fix, only the part it changes is made again; and again once more when the fix goes.
        assertEquals(listOf(false, true), make(FixedSpeaker(voice, fixes)).map { it.kept })
        assertEquals(listOf(true, true), make(FixedSpeaker(voice, fixes)).map { it.kept })
        assertEquals("Xiao Yan left.", File(cache, "0001.txt").readText())
        assertEquals(listOf(false, true), make(voice).map { it.kept })
        assertEquals(listOf("Xiao Yan left.", "Nobody saw.", "Shao Yen left.", "Xiao Yan left."), heard)
    }

    @Test
    fun pronunciationsAreKeptOneAWord() {
        val file = File(Files.createTempDirectory("kokomux-fixes").toFile(), "fixes.tsv")
        val kept = FixFile(file)
        kept.set("Xiao Yan", "Shao Yan")
        kept.set("café", "kaffay")
        kept.set("xiao  yan", "Shao Yen")
        assertEquals(listOf("café" to "kaffay", "xiao yan" to "Shao Yen"), FixFile(file).all())
        kept.remove("CAFÉ")
        assertEquals(listOf("xiao yan" to "Shao Yen"), kept.all())
    }
}
