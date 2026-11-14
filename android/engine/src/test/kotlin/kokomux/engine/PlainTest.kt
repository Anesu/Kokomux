package kokomux.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.UnknownHostException

/** What went wrong, as it's shown. */
class PlainTest {
    @Test
    fun aMessageWrittenForTheListenerIsKept() {
        assertEquals("That doesn't look like text, an EPUB or a PDF", ReadError("That doesn't look like text, an EPUB or a PDF").plain())
        assertEquals("Couldn't download voices-v1.0.bin. Try again: it picks up where it stopped.",
            IOException("Couldn't download voices-v1.0.bin. Try again: it picks up where it stopped.").plain())
        assertEquals("That voice is gone", IllegalStateException("That voice is gone").plain())
    }

    @Test
    fun aCommonCauseIsNamed() {
        assertEquals("No connection", UnknownHostException("freewebnovel.com").plain())
        assertEquals("That file isn't there any more", FileNotFoundException("/data/x/books/ab12.epub (No such file or directory)").plain())
        assertEquals("The phone ran out of memory", OutOfMemoryError().plain())
    }

    @Test
    fun theRestSaysOnlyItsKind() {
        assertEquals("Something went wrong (NullPointerException)", NullPointerException().plain())
        assertEquals("Something went wrong (IllegalStateException)",
            IllegalStateException("ai.onnxruntime.OrtException: Error code - ORT_FAIL - message: bad node").plain())
        assertEquals("Couldn't read or save a file", IOException("line one\nline two").plain())
    }
}
