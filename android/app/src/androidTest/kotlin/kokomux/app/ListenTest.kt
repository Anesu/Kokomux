package kokomux.app

import android.content.ComponentName
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kokomux.engine.Audio
import kokomux.engine.Books
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A short text, listened to through the real service: read and split by
 * engine.py, spoken by Kokoro, played by ExoPlayer, the bookmark following
 * along. Needs the model in the app's storage (the CI emulator job puts it
 * there; on the phone, the app downloads it).
 */
@RunWith(AndroidJUnit4::class)
class ListenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** [read] on the main thread, where the controller lives. */
    private fun <T> onMain(read: () -> T): T {
        var got: T? = null
        instrumentation.runOnMainSync { got = read() }
        @Suppress("UNCHECKED_CAST") return got as T
    }

    private fun until(what: String, minutes: Long, check: () -> Boolean) {
        val end = System.currentTimeMillis() + minutes * 60_000
        while (!check()) {
            if (System.currentTimeMillis() > end) throw AssertionError("Timed out waiting for $what")
            Thread.sleep(250)
        }
    }

    @Test
    fun aShortTextIsMadeAndPlayed() {
        val engine = context.engine
        assumeTrue("the model isn't on this device", engine.models.ready())
        engine.books.mkdirs()
        val file = File(engine.books, "listen-test.txt").apply { writeText("The road left the village. It climbed into the hills.") }
        val id = Books.id(file.path)
        engine.bookmarks.remove(id)
        val token = SessionToken(context, ComponentName(context, ListenService::class.java))
        val controller = MediaController.Builder(context, token).buildAsync().get(1, TimeUnit.MINUTES)
        try {
            instrumentation.runOnMainSync {
                controller.sendCustomCommand(SessionCommand(ListenService.OPEN, Bundle.EMPTY),
                    Request(id, "text", file.path, "listen test", "af_heart").toBundle())
            }
            until("the book to open", 5) { Listening.now != null || Listening.error.isNotEmpty() }
            assertEquals("", Listening.error)
            val parts = Listening.now!!.book.parts
            assertEquals(listOf("The road left the village.", "It climbed into the hills."), parts.map { it.text })

            // The first part plays, and the bookmark is on it.
            until("the first part to play", 15) { Listening.at >= 1 || Listening.error.isNotEmpty() }
            instrumentation.runOnMainSync { controller.pause() }
            assertEquals("", Listening.error)
            val mark = engine.bookmarks.get(id)
            assertEquals(2, mark?.parts)
            assertEquals(Listening.at, mark?.part)

            // Both parts are made, each with its word timings.
            until("both parts to be made", 15) { Listening.made.size == 2 || Listening.error.isNotEmpty() }
            assertEquals("", Listening.error)
            for (m in Listening.made) {
                assertTrue("${m.wav} is too short", Audio.wavSeconds(m.wav) > 0.5)
                assertTrue(m.words.readText().startsWith("confidence\t"))
                println("kokomux: part ${m.index}: ${"%.2f".format(m.speechSecs)} s of speech, " +
                    "misaki ${"%.2f".format(m.soundsSecs)} s, Kokoro ${"%.2f".format(m.kokoroSecs)} s")
            }

            // A new speed: this part carries on sped up, the next is made again at it, and the bookmark keeps it.
            instrumentation.runOnMainSync {
                controller.sendCustomCommand(SessionCommand(ListenService.SPEED, Bundle.EMPTY), Bundle().apply { putFloat("speed", 1.5f) })
            }
            until("the next part to be made at the new speed", 15) { Listening.made.size == 3 || Listening.error.isNotEmpty() }
            assertEquals("", Listening.error)
            assertEquals(1.5f, Listening.now!!.request.speed)
            assertEquals(1.5f, engine.bookmarks.get(id)?.speed)

            // The sleep timer: the voice fades and stops, keeping its place.
            instrumentation.runOnMainSync {
                controller.sendCustomCommand(SessionCommand(ListenService.SLEEP, Bundle.EMPTY),
                    Bundle().apply { putString("kind", "minutes"); putLong("ms", 1500) })
                controller.play()
            }
            until("the sleep timer to stop it", 2) { Listening.sleep == null && !onMain { controller.isPlaying } }
            assertTrue(Listening.now != null)

            // Heard to the end: the bookmark and the book's own copy go, as in Termux.
            instrumentation.runOnMainSync { controller.play() }
            until("the book to end", 2) { Listening.now == null }
            assertEquals(null, engine.bookmarks.get(id))
            assertTrue(!file.exists())
        } finally {
            instrumentation.runOnMainSync {
                controller.sendCustomCommand(SessionCommand(ListenService.STOP, Bundle.EMPTY), Bundle.EMPTY)
                controller.release()
            }
        }
    }
}
