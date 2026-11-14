package kokomux.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/** Saved passages: the sentence just heard, and keeping them. */
class PassagesTest {
    private val text = "The road left at first light. “Is that the sea?” she asked. Nobody answered her."

    @Test
    fun theSentenceJustHeardIsTheOneTheWordIsIn() {
        assertEquals("The road left at first light.", Passages.sentence(text, 4))
        assertEquals("“Is that the sea?” she asked.", Passages.sentence(text, text.indexOf("sea")))
        assertEquals("Nobody answered her.", Passages.sentence(text, text.length - 1))
        assertEquals("One line, no full stop", Passages.sentence("One line, no full stop", 3))
    }

    @Test
    fun passagesAreKeptNewestFirstAndRemoved() {
        val kept = Passages(Files.createTempFile("passages", ".tsv").toFile().apply { delete() })
        kept.save(Passages.Passage(1, "a", "A book", "Chapter 1", 3, "First.\tWith a tab"))
        kept.save(Passages.Passage(2, "a", "A book", "Chapter 2", 9, "Second."))
        assertEquals(listOf(2L, 1L), kept.all().map { it.saved })
        assertEquals("First. With a tab", kept.all()[1].text)
        kept.remove(2)
        assertEquals(listOf(1L), kept.all().map { it.saved })
    }
}
