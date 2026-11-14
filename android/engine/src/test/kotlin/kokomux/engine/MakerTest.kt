package kokomux.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The job runner with a stand-in text side and voice: what it writes, what it
 * keeps and reuses, how far ahead of the player it makes, and bookmarks.
 */
class MakerTest {
    /** Says each part as 0.1 s of sound, and counts what it was asked. */
    private class Fake : TextSide {
        val said = Collections.synchronizedList(mutableListOf<String>())
        override fun read(source: String, name: String, lang: String) = error("not read here")
        override fun speakable(text: String, lang: String) = Speakable("sounds of $text", "null")
        override fun wordRows(text: String, lang: String, speakable: Speakable, timings: List<Timing>) =
            "confidence\t1.0\n0\t100\t0\t${text.length}\n"
        fun speak(sounds: String): Speech {
            said += sounds
            return Speech(FloatArray(Audio.RATE / 10) { 0.1f }, listOf(Timing('a', 0.0, 0.1)))
        }
    }

    private fun book(n: Int, pause: Double = 0.0) =
        Book("A book", (1..n).map { Part("Part $it.", pause) }, listOf(Chapter(1, "One"), Chapter(3, "Two")))

    private fun temp(): File = Files.createTempDirectory("kokomux-maker").toFile()

    private fun until(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!check()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun makesEachPartWithItsTextWordsAndPause() {
        val fake = Fake()
        val cache = temp()
        val made = mutableListOf<Maker.Made>()
        Maker(book(3, pause = 0.25), cache, KokoroSpeaker(fake, "en-us", fake::speak)).run(1, { 0 }, { false }) { made += it }
        assertEquals(listOf(1, 2, 3), made.map { it.index })
        assertEquals("Part 2.", File(cache, "0002.txt").readText())
        assertTrue(File(cache, "0002.words").readText().startsWith("confidence\t1.0\n"))
        assertEquals(0.35, made[1].seconds, 1e-6)                    // 0.1 s of speech, 0.25 s of pause
        assertTrue(made.none { it.kept } && made.all { it.voiceSecs >= 0 })
    }

    @Test
    fun keptPartsAreReusedUnlessTheirTextChanged() {
        val cache = temp()
        Fake().let { f -> Maker(book(3), cache, KokoroSpeaker(f, "en-us", f::speak)).run(1, { 0 }, { false }) {} }
        val again = Fake()
        val changed = Book("A book", listOf(Part("Part 1.", 0.0), Part("Part two, rewritten.", 0.0), Part("Part 3.", 0.0)),
            emptyList())
        val made = mutableListOf<Maker.Made>()
        Maker(changed, cache, KokoroSpeaker(again, "en-us", again::speak)).run(1, { 0 }, { false }) { made += it }
        assertEquals(listOf(true, false, true), made.map { it.kept })
        assertEquals(listOf("sounds of Part two, rewritten."), again.said)
    }

    @Test
    fun staysOnlySoFarAheadOfThePlayer() {
        val fake = Fake()
        val at = AtomicInteger(0)
        val stop = AtomicBoolean(false)
        val made = Collections.synchronizedList(mutableListOf<Int>())
        val maker = Maker(book(10), temp(), KokoroSpeaker(fake, "en-us", fake::speak), ahead = 0.35)
        val t = Thread { maker.run(1, at::get, stop::get) { made += it.index } }.apply { start() }
        until("four parts") { made.size == 4 }                  // 0.1 + 0.1 + 0.1 < 0.35, then the fourth
        Thread.sleep(700)
        assertEquals(4, made.size)                               // and no more while the player hasn't moved
        at.set(3)                                                // the player reaches part 3: two are left ahead
        until("six parts") { made.size == 6 }
        stop.set(true)
        t.join(5000)
        assertFalse(t.isAlive)
        assertEquals((1..6).toList(), made.toList())
    }

    @Test
    fun startsWhereItsToldAndKeepsEachSettingApart() {
        val fake = Fake()
        val made = mutableListOf<Int>()
        Maker(book(5), temp(), KokoroSpeaker(fake, "en-us", fake::speak)).run(4, { 0 }, { false }) { made += it.index }
        assertEquals(listOf(4, 5), made)
        val root = temp()
        val a = Maker.cacheDir(root, "0123456789abcdef", "af_heart", 1.0f, "en-us")
        assertEquals(File(File(root, "0123456789abcdef"), a.name), a)
        assertEquals(12, a.name.length)
        assertFalse(a == Maker.cacheDir(root, "0123456789abcdef", "af_heart", 1.1f, "en-us"))
        assertFalse(a == Maker.cacheDir(root, "0123456789abcdef", "bf_emma", 1.0f, "en-gb"))
    }

    @Test
    fun theCacheKeyIsEnginePys() {
        // engine.cache_dir("0123456789abcdef", "af_heart", 1.0, "en-us") keeps parts in this folder.
        val dir = Maker.cacheDir(File("/c"), "0123456789abcdef", "af_heart", 1.0f, "en-us")
        assertEquals(ENGINE_KEY, dir.name)
    }

    @Test
    fun trimmingLetsGoOfTheBooksHeardLeastRecently() {
        val root = temp()
        for ((i, name) in listOf("old", "middle", "now").withIndex()) {
            File(root, "$name/k").mkdirs()
            File(root, "$name/k/0001.wav").writeBytes(ByteArray(1000))
            File(root, name).setLastModified(1_000_000L * (i + 1))
        }
        Maker.trim(root, keep = "old", limit = 2000)
        assertEquals(setOf("old", "now"), root.list()!!.toSet())   // the one being heard stays
    }

    @Test
    fun bookmarksRoundTripAndPickUpInTheSamePlace() {
        val marks = Bookmarks(temp())
        val m = Bookmarks.Mark("0123456789abcdef", 7, 20, "A book", "file", "/books/a.epub", "bf_emma", 1.2f, "en-gb")
        marks.save(m)
        assertEquals(m.copy(lastHeard = marks.get(m.id)!!.lastHeard), marks.get(m.id))
        assertEquals(listOf(m.id), marks.all().map { it.id })
        marks.remove(m.id)
        assertEquals(null, marks.get(m.id))
        assertEquals(7, Bookmarks.startAt(7, 20, 20))
        assertEquals(13, Bookmarks.startAt(7, 20, 40))              // the same share of the text
        assertEquals(1, Bookmarks.startAt(30, 0, 20))               // past the end: start again
    }

    @Test
    fun bookIdsAndLanguagesAreTermuxs() {
        // printf '%s' https://example.com/a | sha256sum | cut -c1-16
        assertEquals(URL_ID, Books.id("https://example.com/a"))
        assertEquals("en-gb", Books.langOf("bm_george"))
        assertEquals("pt-br", Books.langOf("pf_dora"))
        // Ming's voices read the language of their name; a cloned voice the one it was made for.
        assertEquals("en-gb", Books.langOf("ming:UK_George"))
        assertEquals("cmn", Books.langOf("ming:灵小萌"))
        assertEquals("fr-fr", Books.langOf("qwen:fr-fr:Amélie"))
        assertEquals(listOf("kokoro", "ming", "qwen"), listOf("af_heart", "ming:US_Emma", "qwen:en-us:A").map(VoiceId::engine))
        assertEquals("Anna 2", VoiceId.name("qwen:en-us:Anna 2"))
        assertTrue(VoiceId.makesAtSpeed("bf_emma") && !VoiceId.makesAtSpeed("ming:US_Emma"))
    }

    companion object {
        // From engine.py and sha256sum on this machine (see the tests' names).
        const val ENGINE_KEY = "844f750f32ef"
        const val URL_ID = "2dce0a4c50441bfc"
    }
}
