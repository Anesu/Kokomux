package kokomux.engine

import java.io.File
import java.security.MessageDigest

/**
 * Making a book's parts to listen to, as engine.py's run_job makes them for
 * the Termux player: from a part on, each part said by the [speaker] (its
 * speech with softened edges and the pause after it, and its word timings).
 * Parts are kept per book and settings, so listening again reuses them, and
 * only [ahead] seconds of speech are made ahead of the part the player is on,
 * so a long book doesn't keep the phone busy for hours.
 */
class Maker(
    private val book: Book,
    private val cache: File,
    private val speaker: Speaker,
    private val ahead: Double = AHEAD,
) {
    /**
     * A part ready to play: its files, its length, how long getting it ready and the voice took (0 if
     * it was kept), and its speech's length without the pause.
     */
    class Made(val index: Int, val wav: File, val words: File, val seconds: Double,
               val prepSecs: Double, val voiceSecs: Double, val speechSecs: Double, val kept: Boolean)

    /**
     * Make the parts from [first] to [last] (the end), handing each to [ready] as it's done.
     * [at] is the part the player is on (0 before it starts); [stop] ends it early.
     */
    fun run(first: Int, at: () -> Int, stop: () -> Boolean, last: Int = book.parts.size, ready: (Made) -> Unit) {
        cache.mkdirs()
        val made = HashMap<Int, Double>()               // part -> seconds of speech, for keeping just ahead
        for (i in first..minOf(last, book.parts.size)) {
            if (stop()) return
            val (chunk, pause) = book.parts[i - 1]
            val name = "%04d".format(i)
            val wav = File(cache, "$name.wav")
            val words = File(cache, "$name.words")
            var said: Said? = null
            val kept = kept(name, chunk)
            if (!kept) {
                if (!keepAhead(made, at, stop)) return
                said = speaker.say(chunk)
                // The text and word timings first, the audio last: its presence means the part is ready.
                write(File(cache, "$name.txt"), chunk)
                write(words, said.rows)
                Audio.writeWav(wav, said.withPause(pause), said.rate)
            }
            val seconds = Audio.wavSeconds(wav)
            made[i] = seconds
            ready(Made(i, wav, words, seconds, said?.prepSecs ?: 0.0, said?.voiceSecs ?: 0.0, said?.seconds ?: 0.0, kept))
        }
    }

    /** A kept part, made from exactly this text (engine.py's cached_part). */
    private fun kept(name: String, chunk: String): Boolean {
        val txt = File(cache, "$name.txt")
        return File(cache, "$name.wav").exists() && txt.exists() && txt.readText() == chunk &&
            File(cache, "$name.words").exists()
    }

    /** Wait until less than [ahead] seconds of speech is ready ahead of the player; false if stopped. */
    private fun keepAhead(made: Map<Int, Double>, at: () -> Int, stop: () -> Boolean): Boolean {
        while (true) {
            if (stop()) return false
            val now = at()
            if (made.filterKeys { it >= now }.values.sum() < ahead) return true
            Thread.sleep(500)
        }
    }

    private fun write(file: File, text: String) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) error("Couldn't save $file")
    }

    companion object {
        /** About ten minutes (a Fibonacci number of seconds), as engine.py's AHEAD. */
        const val AHEAD = 610.0
        const val MODEL_NAME = "kokoro-v1.0-timestamped.fp32.onnx"

        /** Where a book's parts are kept for these settings: engine.py's cache_dir, under [root]. */
        fun cacheDir(root: File, book: String, voice: String, speed: Float, lang: String): File {
            val key = "$voice|${"%.2f".format(java.util.Locale.ROOT, speed)}|$lang|$MODEL_NAME"
            val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            return File(File(root, book), Books.hex(digest).take(12))
        }

        /**
         * Keep the cache under [limit] bytes, letting go of the books heard least
         * recently first, never [keep] (the one being listened to).
         */
        fun trim(root: File, keep: String, limit: Long) {
            val books = root.listFiles { f -> f.isDirectory }.orEmpty()
            var total = books.sumOf { size(it) }
            for (b in books.filter { it.name != keep }.sortedBy { it.lastModified() }) {
                if (total <= limit) break
                total -= size(b)
                b.deleteRecursively()
            }
        }

        private fun size(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
