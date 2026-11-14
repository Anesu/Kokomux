package kokomux.app.ui

import kokomux.engine.Chapter

/** Getting ready on first run: Kokoro's files to fetch, fetching, loading, ready, or stuck. */
sealed interface Setup {
    data object Missing : Setup
    data class Downloading(val label: String, val done: Long, val total: Long) : Setup
    data class Loading(val what: String) : Setup
    data object Ready : Setup
    data class Failed(val message: String) : Setup
}

/** A book in progress, from its bookmark. */
data class ShelfBook(val id: String, val title: String, val part: Int, val parts: Int, val voice: String) {
    val progress get() = if (parts > 1) (part - 1).toFloat() / (parts - 1) else 0f
}

/** A word the voice is saying: when (ms into the part) and where (characters in the part's text). */
data class WordSpan(val start: Long, val end: Long, val from: Int, val to: Int)

/** The sleep timer: minutes left (while playing), or the end of the chapter. */
sealed interface Sleep {
    data class Minutes(val leftMs: Long) : Sleep
    data object EndOfChapter : Sleep
}

/** What's being listened to, as the reader and the mini player show it. */
data class Playing(
    val id: String,
    val title: String,
    val parts: List<String>,
    val chapters: List<Chapter>,
    /** The part playing, counting from 1 (0 before it starts). */
    val at: Int,
    val playing: Boolean,
    /** The last part made so far, and whether more are still to be made. */
    val madeTo: Int,
    val making: Boolean,
    val voice: String,
    val speed: Float,
    val sleep: Sleep? = null,
    /** The part playing's word timings, once they're read (empty: nothing to light). */
    val words: List<WordSpan> = emptyList(),
    /** Seconds of listening left at this speed, from how long the parts made so far took to say (0: not known yet). */
    val left: Double = 0.0,
) {
    val chapter: Chapter? get() = chapters.lastOrNull { it.first <= at.coerceAtLeast(1) }
}

/** The settings, as the Termux version names them (KOKORO_*). */
data class Prefs(
    val voice: String = "af_heart",
    val speed: Float = 1f,
    val theme: ThemeMode = ThemeMode.AUTO,
    val focus: Boolean = true,
    val words: Boolean = true,
    val offsetMs: Int = 0,
    val threads: Int = 0,
    val block: Int = 0,
)

/** How fast Kokoro made the parts of this listen. */
data class Making(val parts: Int, val speech: Double, val took: Double, val kokoro: Double, val tuning: String) {
    val realTime get() = speech / took
    val kokoroRealTime get() = speech / kokoro
}

/** A pronunciation check: how many parts, and which sound different from engine.py's. */
data class Checked(val cases: Int, val seconds: Double, val differ: List<Triple<String, String, String>>)

/** Everything the screens show. */
data class UiState(
    val setup: Setup = Setup.Missing,
    val prefs: Prefs = Prefs(),
    val shelf: List<ShelfBook> = emptyList(),
    val playing: Playing? = null,
    /** Opening a book or page (reading it, loading Kokoro). */
    val opening: Boolean = false,
    val voices: List<String> = emptyList(),
    val making: Making? = null,
    val checked: Checked? = null,
    val checking: Boolean = false,
    /** Kokoro's and misaki's load times. */
    val loaded: String = "",
    /** The kept audio's size, in bytes. */
    val kept: Long = 0,
    /** Something went wrong, in words to show. */
    val error: String = "",
    /** The voice whose sample is playing. */
    val hearing: String = "",
    /** The app's version. */
    val version: String = "",
)

/** What the screens can ask for. */
interface Actions {
    fun download()
    fun openFile(uri: android.net.Uri)
    fun openPage(address: String)
    fun readClipboard()
    fun readTyped(text: String)
    fun resume(id: String)
    fun remove(id: String)
    fun playPause()
    /** Back to the start of the part, or to the one before if it has only just started (any player's ⏮). */
    fun previous()
    /** This part again, from its start. */
    fun again()
    fun next()
    fun stop()
    fun jump(part: Int)
    fun speed(speed: Float)
    fun sleep(choice: Sleep?)
    fun voice(voice: String)
    fun hear(voice: String)
    fun prefs(prefs: Prefs)
    fun check()
    fun clearKept()
    fun dismissError()
}

/** engine.py's LANGS: a voice's first letter is its language. */
object Voices {
    val languages = listOf(
        'a' to "American English", 'b' to "British English", 'e' to "Spanish", 'f' to "French",
        'h' to "Hindi", 'i' to "Italian", 'j' to "Japanese", 'p' to "Brazilian Portuguese", 'z' to "Mandarin Chinese",
    )

    fun language(voice: String) = languages.firstOrNull { it.first == voice.firstOrNull() }?.second ?: "Other"
    fun gender(voice: String) = if (voice.getOrNull(1) == 'm') "male" else "female"
    /** "bf_emma" -> "Emma". */
    fun name(voice: String) = voice.substringAfter('_').replaceFirstChar { it.uppercase() }
    /** "American English" -> "American", "Brazilian Portuguese" -> "Portuguese": a language in a word. */
    fun shortLanguage(name: String) = when {
        name.endsWith(" English") -> name.substringBefore(' ')
        ' ' in name -> name.substringAfter(' ')
        else -> name
    }

    /** "British English, female" (tui.sh's voice_desc). */
    fun describe(voice: String) = "${language(voice)}, ${gender(voice)}"

    /** A line in the voice's own language, to hear it by (tui.sh's TUI_SAMPLE). */
    fun sample(voice: String) = when (voice.firstOrNull()) {
        'e' -> "¡Hola! Así sueno cuando leo para ti."
        'f' -> "Bonjour ! Voici ma voix quand je lis pour vous."
        'i' -> "Ciao! Ecco come suono quando leggo per te."
        'p' -> "Olá! É assim que eu soo lendo para você."
        'h' -> "नमस्ते! मैं ऐसे पढ़कर सुनाती हूँ।"
        'j' -> "こんにちは。私はこんな声で読みます。"
        'z' -> "你好！我就是这样为你朗读的。"
        else -> "Hello! This is how I sound when I read to you."
    }
}

/**
 * A part's word timings from its .words file (words.player_rows: a confidence
 * line, then "start end a b" per word, a..b the word's UTF-8 bytes in the
 * part's text, as the Termux player slices by), as character ranges.
 */
fun wordSpans(text: String, rows: List<String>): List<WordSpan> {
    val chars = IntArray(text.toByteArray().size + 1)          // byte offset -> character offset
    var byte = 0
    text.forEachIndexed { i, ch ->
        val n = ch.toString().toByteArray().size
        for (k in 0 until n) chars[byte + k] = i
        byte += n
    }
    chars[byte] = text.length
    return rows.drop(1).mapNotNull { row ->
        val f = row.split('\t')
        if (f.size < 4) return@mapNotNull null
        val a = f[2].toIntOrNull()?.takeIf { it in chars.indices } ?: return@mapNotNull null
        val b = f[3].toIntOrNull()?.takeIf { it in chars.indices } ?: return@mapNotNull null
        WordSpan(f[0].toLongOrNull() ?: return@mapNotNull null, f[1].toLongOrNull() ?: return@mapNotNull null, chars[a], chars[b])
    }
}
