package kokomux.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** What's waiting to be heard: in the order it was added, once each. */
class UpNextTest {
    private fun item(id: String, title: String = "Page $id") = UpNext.Item(id, "url", "https://example.org/$id", title)

    @Test
    fun thingsWaitInTheOrderTheyWereAddedOnceEach() {
        val file = File(Files.createTempDirectory("kokomux-queue").toFile(), "queue.tsv")
        val queue = UpNext(file)
        assertEquals(emptyList<UpNext.Item>(), queue.all())
        assertTrue(queue.add(item("a")))
        assertTrue(queue.add(item("b", "A title\twith a tab")))
        assertTrue(queue.add(item("c")))
        assertFalse(queue.add(item("a")))
        assertEquals(listOf("a", "b", "c"), queue.all().map { it.id })
        assertEquals("A title with a tab", queue.all()[1].title)

        assertEquals("b", queue.remove("b")?.id)
        assertEquals(null, queue.remove("b"))
        assertEquals(listOf("a", "c"), UpNext(file).all().map { it.id })     // (kept in the file)
        queue.remove("a")
        queue.remove("c")
        assertEquals(emptyList<UpNext.Item>(), queue.all())
    }
}
