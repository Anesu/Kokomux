package kokomux.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Word timings from the engine's .words rows (UTF-8 byte ranges) to the reader's character ranges. */
class WordSpansTest {
    @Test
    fun byteRangesBecomeCharacterRanges() {
        val text = "Café “au” lait, naïve."
        // As words.player_rows writes them: a confidence line, then start, end, and the word's UTF-8 bytes.
        fun bytes(word: String): Pair<Int, Int> {
            val at = text.indexOf(word)
            val a = text.substring(0, at).toByteArray().size
            return a to a + word.toByteArray().size
        }
        val rows = listOf("confidence\t0.9") + listOf("Café", "au", "lait", "naïve").mapIndexed { i, w ->
            val (a, b) = bytes(w)
            "${i * 300}\t${i * 300 + 250}\t$a\t$b"
        }
        val spans = wordSpans(text, rows)
        assertEquals(listOf("Café", "au", "lait", "naïve"), spans.map { text.substring(it.from, it.to) })
        assertEquals(listOf(0L, 300L, 600L, 900L), spans.map { it.start })
    }

    @Test
    fun aLineWithNoTimingsLightsNothing() {
        assertEquals(emptyList<WordSpan>(), wordSpans("Hello.", listOf("confidence\t0.1")))
        assertEquals(emptyList<WordSpan>(), wordSpans("Hello.", listOf("confidence\t0.9", "0\t100\t0\t99")))
    }
}
