package kokomux.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kokomux.engine.Timing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On the phone (or an emulator): engine.py reads books there (pypdf, the
 * EPUB reader), and espeak-ng speaks the other languages, as in the container.
 */
@RunWith(AndroidJUnit4::class)
class TextSideTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val text = context.engine.text

    /** A fixture from tests/fixtures, copied where the app can read it. */
    private fun fixture(name: String): File {
        val dest = File(context.cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        return dest
    }

    @Test
    fun aPdfIsReadAndSplitAsInTheContainer() {
        val book = text.read(fixture("caravan.pdf").path, "caravan", "en-us")
        assertEquals("The Caravan", book.title)
        assertEquals(1, book.chapters.size)
        assertEquals(1.06, book.parts.last().pause, 1e-9)        // a chapter's pause after the last part
        assertTrue(book.parts.joinToString(" ") { it.text }.contains("traveller"))
    }

    @Test
    fun otherLanguagesAreSpokenThroughEspeak() {
        for ((lang, line) in listOf("es" to "Hola, ¿cómo estás?", "fr-fr" to "Bonjour, comment ça va ?",
            "hi" to "नमस्ते, आप कैसे हैं?", "cmn" to "你好，你好吗？")) {
            val s = text.speakable(line, lang)
            assertTrue("$lang: '${s.sounds}'", s.sounds.length > 4)
            assertEquals("null", s.perWord)
        }
    }

    @Test
    fun wordTimingsComeBackInThePlayersForm() {
        val line = "Nobody in the village remembered who had built it, or why it ran inland."
        val s = text.speakable(line, "en-us")
        // Each sound a tenth of a second: the words take their sounds' times.
        val timings = s.sounds.mapIndexed { i, ch -> Timing(ch, i * 0.1, (i + 1) * 0.1) }
        val rows = text.wordRows(line, "en-us", s, timings).lines().filter { it.isNotEmpty() }
        assertTrue(rows.first(), rows.first().startsWith("confidence\t"))
        assertEquals(14, rows.size)                               // a row for each of the 13 words
    }
}
