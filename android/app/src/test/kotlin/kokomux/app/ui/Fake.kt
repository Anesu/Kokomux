package kokomux.app.ui

import android.net.Uri
import kokomux.engine.Chapter
import kokomux.engine.Session
import kokomux.engine.Stats
import java.time.LocalDate
import java.time.ZoneId

/** Made-up books and listening, to draw the screens with. */
object Fake {
    val story = listOf(
        "The Long Road",
        "Chapter One: Departure.",
        "The road left the village at first light and climbed into the hills, where the fields gave way to heather.",
        "For three days she walked without meeting anyone, sleeping in the lee of stone walls and waking to the sound of sheep.",
        "On the fourth morning the mist lifted, and she saw the sea for the first time: grey, enormous, and close enough to hear.",
        "She sat on a rock and ate the last of her bread, and watched the boats come in on the evening tide.",
        "Chapter Two: The Harbour.",
        "The harbour town was smaller than she had imagined, its roofs slate and its walls white.",
        "Smoke rose from every chimney into the still air, and somewhere a bell was ringing for the end of the day.",
        "Nobody asked her where she had come from, which was just as well, because she was no longer sure.",
        "She found a room above the bakery, with a window that looked out over the water.",
        "That night she slept without dreaming, for the first time since she had left.",
    )
    val chapters = listOf(Chapter(1, "Chapter One: Departure"), Chapter(7, "Chapter Two: The Harbour"))

    /** Words of [text] spread over its reading, 300 ms apart. */
    fun words(text: String): List<WordSpan> =
        Regex("\\S+").findAll(text).mapIndexed { i, m ->
            val core = m.value.trimEnd(',', '.', ':', ';')
            WordSpan(i * 300L, i * 300L + 280, m.range.first, m.range.first + core.length)
        }.toList()

    fun playing(at: Int = 4, playing: Boolean = true, sleep: Sleep? = null) = Playing(
        id = "0123456789abcdef", title = "The Long Road", parts = story, chapters = chapters, at = at,
        playing = playing, madeTo = 9, making = true, voice = "bf_emma", speed = 1.1f, sleep = sleep,
        words = if (at > 0) words(story[at - 1]) else emptyList(), left = if (at > 0) 8040.0 else 0.0,
    )

    val shelf = listOf(
        ShelfBook("0123456789abcdef", "The Long Road", 4, 12, "bf_emma"),
        ShelfBook("2", "Starting with an SSS-Rank Goddess Summon", 12, 34, "af_heart", "Chapter 8: The Summoning", 8, 11),
        ShelfBook("3", "A Short History of Nearly Everything", 89, 1597, "bm_george"),
    )

    val voices = listOf("af_alloy", "af_bella", "af_heart", "af_nicole", "am_adam", "am_michael", "bf_emma", "bf_isabella",
        "bm_george", "bm_lewis", "ef_dora", "em_alex", "ff_siwis", "hf_alpha", "if_sara", "jf_alpha", "pf_dora", "zf_xiaobei")

    /** Three weeks of listening to two books, evenings mostly, as of Saturday 14 November 2026. */
    val stats: Stats by lazy {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.of(2026, 11, 14)
        val minutes = listOf(34, 0, 55, 21, 89, 13, 0, 0, 34, 47, 21, 8, 61, 34, 0, 21, 55, 13, 89, 34, 21, 72)
        val sessions = minutes.mapIndexedNotNull { i, m ->
            if (m == 0) return@mapIndexedNotNull null
            val daysAgo = minutes.size - 1 - i
            val start = today.minusDays(daysAgo.toLong()).atTime(if (i % 3 == 0) 7 else 21, 4).atZone(zone).toInstant().toEpochMilli()
            val novel = i % 2 == 1
            Session(i.toLong(), if (novel) "2" else "0123456789abcdef",
                if (novel) "Starting with an SSS-Rank Goddess Summon" else "The Long Road", start, start + m * 60_000L, m * 60.0,
                if (novel) "Chapter ${i / 2 + 1}" else "Chapter One: Departure", if (novel) "Chapter ${i / 2 + 2}" else "Chapter Two: The Harbour",
                m * 150, if (novel) 1 else 0, 40_000 - i * 1000, if (novel) 11 - i / 2 else -1)
        }
        Stats(sessions, zone, today)
    }

    /** Passages saved from two books. */
    val passages = listOf(
        Saved(3, "0123456789abcdef", "The Long Road", "Chapter One: Departure",
            5, "She sat on a rock and ate the last of her bread, and watched the boats come in on the evening tide."),
        Saved(2, "2", "Starting with an SSS-Rank Goddess Summon", "Chapter 8: The Summoning", 12,
            "“An SSS-rank summon,” the examiner whispered, “has not been seen in three hundred years.”"),
        Saved(1, "0123456789abcdef", "The Long Road", "Chapter Two: The Harbour", 9,
            "Nobody asked her where she had come from, which was just as well, because she was no longer sure."),
    )

    val state = UiState(
        setup = Setup.Ready, prefs = Prefs(voice = "bf_emma", speed = 1.1f), shelf = shelf, playing = playing(),
        voices = voices + Voices.ming + listOf("qwen:en-us:Anesu", "qwen:en-gb:Narrator"),
        making = Making(37, 412.0, 98.0, 91.0, "Kokoro", "6 threads, block 4"),
        loaded = "Kokoro loaded in 2.14 s, misaki and spaCy in 3.60 s", kept = 1_234_000_000, stats = stats,
        passages = passages,
        engines = listOf(
            EngineFiles("kokoro", "Kokoro", 353_746_569, 353_746_569),
            EngineFiles("ming", "Ming 3.0", 2_839_549_649, done = 1_123_000_000),
            EngineFiles("qwen", "Qwen3-TTS", 1_689_175_579, 1_689_175_579),
        ),
    )

    /** A web novel being heard, at chapter 7, and its list of chapters. */
    val toc = (1..21).map { n -> TocEntry(n, if (n % 5 == 0) "Chapter $n" else "Chapter $n: ${listOf("The Summoning", "S-Rank", "The Goddess", "First Blood")[n % 4]}",
        "https://freewebnovel.com/novel/starting-with-an-sss-rank-goddess-summon/chapter-$n", n < 7, n == 7) }

    val found = listOf(
        Found("Starting with an SSS-Rank Goddess Summon", "https://freewebnovel.com/novel/starting-with-an-sss-rank-goddess-summon", "Chapter 412"),
        Found("The Goddess Summoner's Guild", "https://freewebnovel.com/novel/the-goddess-summoners-guild", "Chapter 88"),
    )

    /** Actions that do nothing. */
    val actions = object : Actions {
        override fun download() {}
        override fun openFile(uri: Uri) {}
        override fun openPage(address: String) {}
        override fun readClipboard() {}
        override fun readTyped(text: String) {}
        override fun resume(id: String) {}
        override fun remove(id: String) {}
        override fun playPause() {}
        override fun previous() {}
        override fun again() {}
        override fun next() {}
        override fun stop() {}
        override fun jump(part: Int) {}
        override fun speed(speed: Float) {}
        override fun sleep(choice: Sleep?) {}
        override fun voice(voice: String) {}
        override fun hear(voice: String) {}
        override fun fetchEngine(id: String) {}
        override fun removeEngine(id: String) {}
        override fun record() {}
        override fun stopRecording() {}
        override fun pickClip(uri: Uri) {}
        override fun playClip() {}
        override fun dropClip() {}
        override fun makeVoice(words: String, name: String, lang: String) {}
        override fun forgetVoice(voice: String) {}
        override fun loadToc() {}
        override fun loadStats() {}
        override fun saveAudiobook(uri: Uri, from: Int, to: Int) {}
        override fun savePassage() {}
        override fun removePassage(saved: Long) {}
        override fun goToPassage(p: Saved) {}
        override fun chapter(url: String) {}
        override fun search(query: String) {}
        override fun novelToc(found: Found) {}
        override fun prefs(prefs: Prefs) {}
        override fun check() {}
        override fun clearKept() {}
        override fun dismissError() {}
    }
}
