package kokomux.engine

import java.io.File
import java.security.MessageDigest

/** A part of a book: its text, and the pause after it in seconds (engine.py's PAUSE). */
data class Part(val text: String, val pause: Double)

/** Where a chapter starts: its first part (counting from 1) and its title; a web novel's also its address and number. */
data class Chapter(val first: Int, val title: String, val url: String = "", val number: Int = 0)

/**
 * A book, file or page, read and split into parts as engine.py's run_job splits it. A web
 * novel's chapters arrive as it's heard, each added on the end ([plus]).
 */
data class Book(val title: String, val parts: List<Part>, val chapters: List<Chapter>, val novel: Novel? = null) {
    /** The chapter part [i] is in. */
    fun chapterOf(i: Int): Chapter? = chapters.lastOrNull { it.first <= i }

    /** The parts of chapter [c] (counting from 1, as the book does). */
    fun partsOf(c: Chapter): IntRange = c.first until (chapters.firstOrNull { it.first > c.first }?.first ?: (parts.size + 1))

    /** A novel's next chapter, added on the end. */
    fun plus(chapter: NovelChapter, parts: List<Part>) = copy(
        parts = this.parts + parts,
        chapters = chapters + Chapter(this.parts.size + 1, chapter.label, chapter.url, chapter.number),
        novel = novel?.copy(next = chapter.next),
    )
}

/** A web novel being heard: its own address (its bookmark's) and title, and the next chapter's address (null: none yet). */
data class Novel(val url: String, val title: String, val next: String?)

/** A chapter of a web novel (engine.py's novel_chapter): which novel, which chapter, and those either side. */
data class NovelChapter(val novel: String, val novelUrl: String, val url: String, val label: String, val number: Int,
                        val next: String?, val prev: String?)

/** A part's sounds as engine.synthesize gets them, and each word's own sounds (misaki's, as JSON; null: espeak's). */
data class Speakable(val sounds: String, val perWord: String)

/** Reading a book or page failed, in words to show (engine.py's JobError). */
class ReadError(message: String) : Exception(message)

/** The text side of the engine: engine.py, run by Chaquopy on the phone. */
interface TextSide {
    /** A file or web page, read and split. Throws [ReadError]. */
    fun read(source: String, name: String, lang: String): Book

    /** A part's sounds. */
    fun speakable(text: String, lang: String): Speakable

    /** A part's word timings in the player's form (words.player_rows), from what Kokoro spoke. */
    fun wordRows(text: String, lang: String, speakable: Speakable, timings: List<Timing>): String
}

/** Names for books, as the Termux version names them. */
object Books {
    /** A book's id: the first 16 hex digits of the SHA-256 of a page's address or a file's bytes. */
    fun id(source: String): String =
        if (source.matches(Regex("^https?://.*"))) hex(sha256(source.toByteArray())).take(16)
        else File(source).inputStream().use { input ->
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
            hex(md.digest()).take(16)
        }

    /**
     * The language a voice reads ("bf_emma" -> "en-gb", engine.py's LANGS; "ming:UK_George" -> "en-gb";
     * "qwen:en-us:anna" -> "en-us"): what a book is tidied and split for.
     */
    fun langOf(voice: String): String = when (VoiceId.engine(voice)) {
        VoiceId.MING -> VoiceId.name(voice).let { if (it.startsWith("US_")) "en-us" else if (it.startsWith("UK_")) "en-gb" else "cmn" }
        VoiceId.QWEN -> voice.split(':').getOrElse(1) { "en-us" }
        else -> when (voice.firstOrNull()) {
            'a' -> "en-us"; 'b' -> "en-gb"; 'e' -> "es"; 'f' -> "fr-fr"; 'h' -> "hi"
            'i' -> "it"; 'j' -> "ja"; 'p' -> "pt-br"; 'z' -> "cmn"
            else -> "en-us"
        }
    }

    internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Voices by engine: Kokoro's by their own names ("bf_emma"), Ming's built-in
 * ones as "ming:US_Emma", and voices cloned with Qwen as "qwen:<language>:<id>".
 */
object VoiceId {
    const val KOKORO = "kokoro"
    const val MING = "ming"
    const val QWEN = "qwen"

    fun engine(voice: String): String = when {
        voice.startsWith("ming:") -> MING
        voice.startsWith("qwen:") -> QWEN
        else -> KOKORO
    }

    /** The voice's own name in its engine ("US_Emma", or a cloned voice's id). */
    fun name(voice: String): String = voice.substringAfterLast(':')

    /** Kokoro makes speech at the speed asked for; the others make it at 1× and the player speeds it up. */
    fun makesAtSpeed(voice: String) = engine(voice) == KOKORO
}
