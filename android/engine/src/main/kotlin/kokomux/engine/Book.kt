package kokomux.engine

import java.io.File
import java.security.MessageDigest

/** A part of a book: its text, and the pause after it in seconds (engine.py's PAUSE). */
data class Part(val text: String, val pause: Double)

/** Where a chapter starts: its first part (counting from 1) and its title. */
data class Chapter(val first: Int, val title: String)

/** A book, file or page, read and split into parts as engine.py's run_job splits it. */
data class Book(val title: String, val parts: List<Part>, val chapters: List<Chapter>) {
    /** The chapter part [i] is in. */
    fun chapterOf(i: Int): Chapter? = chapters.lastOrNull { it.first <= i }
}

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

    /** The language a voice speaks ("bf_emma" -> "en-gb"), engine.py's LANGS. */
    fun langOf(voice: String): String = when (voice.firstOrNull()) {
        'a' -> "en-us"; 'b' -> "en-gb"; 'e' -> "es"; 'f' -> "fr-fr"; 'h' -> "hi"
        'i' -> "it"; 'j' -> "ja"; 'p' -> "pt-br"; 'z' -> "cmn"
        else -> "en-us"
    }

    internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
