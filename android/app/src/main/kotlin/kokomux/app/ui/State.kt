package kokomux.app.ui

import kokomux.engine.Chapter
import kokomux.engine.VoiceId

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

/** How fast the voice made the parts of this listen: [engine] is its name ("Kokoro", "Ming 3.0"). */
data class Making(val parts: Int, val speech: Double, val took: Double, val voice: Double, val engine: String, val tuning: String) {
    val realTime get() = speech / took
    val voiceRealTime get() = speech / voice
}

/**
 * An engine's files on this phone: its id (kokomux.engine.VoiceId's), its name, how big
 * they are and how much is here; [done] is how far a download has got (-1: none going).
 */
data class EngineFiles(val id: String, val title: String, val total: Long, val have: Long = 0, val done: Long = -1,
                       val error: String = "") {
    val ready get() = have >= total
    val downloading get() = done >= 0
}

/** A clip to make a voice from: how long it is, and whether it's playing back. */
data class Clip(val seconds: Double, val playing: Boolean = false)

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
    /** Kokoro's, Ming's and Qwen's files. */
    val engines: List<EngineFiles> = emptyList(),
    /** An engine getting ready for its first listen on this phone, in words ("" when none is). */
    val preparing: String = "",
    /** Recording a clip for a new voice: how long so far, and how loud it is now (0 to 1); null when not. */
    val recording: Pair<Double, Float>? = null,
    /** The clip recorded or picked, for a new voice. */
    val clip: Clip? = null,
    /** A new voice is being made from the clip. */
    val cloning: Boolean = false,
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
    /** Download an engine's files (kokomux.engine.VoiceId's id), or let them go. */
    fun fetchEngine(id: String)
    fun removeEngine(id: String)
    /** A clip for a new voice: recorded from the microphone, or picked from the phone's files. */
    fun record()
    fun stopRecording()
    fun pickClip(uri: android.net.Uri)
    fun playClip()
    fun dropClip()
    /** A voice made from the clip, its [words] (blank if not known), [name] and language; it becomes the voice. */
    fun makeVoice(words: String, name: String, lang: String)
    fun forgetVoice(voice: String)
    fun prefs(prefs: Prefs)
    fun check()
    fun clearKept()
    fun dismissError()
}

/**
 * The voices, whichever engine they're in: Kokoro's (engine.py's LANGS: a
 * voice's first letter is its language), Ming's built-in ones ("ming:US_Emma")
 * and those cloned with Qwen ("qwen:en-us:Anna").
 */
object Voices {
    val languages = listOf(
        'a' to "American English", 'b' to "British English", 'e' to "Spanish", 'f' to "French",
        'h' to "Hindi", 'i' to "Italian", 'j' to "Japanese", 'p' to "Brazilian Portuguese", 'z' to "Mandarin Chinese",
    )

    /** Ming's built-in voices. */
    val ming = listOf("US_Emma", "US_James", "US_Stella", "UK_George", "UK_Charlotte", "灵小萌", "灵岩", "灵小雅").map { "ming:$it" }
    private val mingMale = setOf("US_James", "UK_George", "灵岩")

    /** The languages a cloned voice can read: kokomux's code and its name. */
    val cloneLanguages = listOf(
        "en-us" to "American English", "en-gb" to "British English", "es" to "Spanish", "fr-fr" to "French",
        "it" to "Italian", "pt-br" to "Brazilian Portuguese", "ja" to "Japanese", "cmn" to "Mandarin Chinese",
    )

    /** The engines, in the order they're shown: id and name. */
    val engines = listOf(VoiceId.KOKORO to "Kokoro", VoiceId.MING to "Ming 3.0", VoiceId.QWEN to "Qwen3-TTS")

    fun engine(voice: String) = VoiceId.engine(voice)
    fun engineTitle(voice: String) = engines.first { it.first == engine(voice) }.second

    fun language(voice: String): String = when (engine(voice)) {
        VoiceId.MING -> VoiceId.name(voice).let {
            if (it.startsWith("US_")) "American English" else if (it.startsWith("UK_")) "British English" else "Mandarin Chinese"
        }
        VoiceId.QWEN -> voice.split(':').getOrNull(1).let { l -> cloneLanguages.firstOrNull { it.first == l }?.second ?: "English" }
        else -> languages.firstOrNull { it.first == voice.firstOrNull() }?.second ?: "Other"
    }

    fun gender(voice: String) = when (engine(voice)) {
        VoiceId.MING -> if (VoiceId.name(voice) in mingMale) "male" else "female"
        VoiceId.QWEN -> "cloned"
        else -> if (voice.getOrNull(1) == 'm') "male" else "female"
    }

    /** "bf_emma" -> "Emma", "ming:UK_George" -> "George", "qwen:en-us:Anna" -> "Anna". */
    fun name(voice: String) = when (engine(voice)) {
        VoiceId.KOKORO -> voice.substringAfter('_').replaceFirstChar { it.uppercase() }
        else -> VoiceId.name(voice).substringAfter('_')
    }

    /** "American English" -> "American", "Brazilian Portuguese" -> "Portuguese": a language in a word. */
    fun shortLanguage(name: String) = when {
        name.endsWith(" English") -> name.substringBefore(' ')
        ' ' in name -> name.substringAfter(' ')
        else -> name
    }

    /** "British English, female" (tui.sh's voice_desc). */
    fun describe(voice: String) = "${language(voice)}, ${gender(voice)}"

    /** A line in the voice's own language, to hear it by (tui.sh's TUI_SAMPLE). */
    fun sample(voice: String) = when (kokomux.engine.Books.langOf(voice).take(2)) {
        "es" -> "¡Hola! Así sueno cuando leo para ti."
        "fr" -> "Bonjour ! Voici ma voix quand je lis pour vous."
        "it" -> "Ciao! Ecco come suono quando leggo per te."
        "pt" -> "Olá! É assim que eu soo lendo para você."
        "hi" -> "नमस्ते! मैं ऐसे पढ़कर सुनाती हूँ।"
        "ja" -> "こんにちは。私はこんな声で読みます。"
        "cm" -> "你好！我就是这样为你朗读的。"
        else -> "Hello! This is how I sound when I read to you."
    }

    /** What to read aloud for a new voice: a few seconds of plain, varied speech in its language. */
    fun passage(lang: String) = when (lang.take(2)) {
        "es" -> "La biblioteca olía a lluvia y a papel viejo, y cada estante guardaba una historia esperando a ser contada."
        "fr" -> "La bibliothèque sentait la pluie et le vieux papier, et chaque étagère gardait une histoire prête à être racontée."
        "it" -> "La biblioteca profumava di pioggia e di carta antica, e ogni scaffale custodiva una storia da raccontare."
        "pt" -> "A biblioteca cheirava a chuva e a papel antigo, e cada estante guardava uma história esperando para ser contada."
        "ja" -> "図書館は雨と古い紙の匂いがして、どの棚にも語られるのを待つ物語がありました。"
        "cm" -> "图书馆里弥漫着雨水和旧纸的气味，每一个书架上都藏着一个等待被讲述的故事。"
        else -> "The quiet library smelled of rain and old paper, and every shelf held a story waiting to be told."
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
