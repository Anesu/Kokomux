package kokomux.app

import android.app.Application
import android.content.Context
import android.os.PowerManager
import kokomux.engine.Bookmarks
import kokomux.engine.Books
import kokomux.engine.FixFile
import kokomux.engine.FixedSpeaker
import kokomux.engine.Fixes
import kokomux.engine.Kokoro
import kokomux.engine.KokoroSpeaker
import kokomux.engine.Speakable
import kokomux.engine.Speaker
import kokomux.engine.TextSide
import kokomux.engine.Timing
import kokomux.engine.UpNext
import kokomux.engine.VoiceId
import kokomux.engine.ming.Ming
import kokomux.engine.ming.MingSpeaker
import kokomux.engine.qwen.Clone
import kokomux.engine.qwen.Qwen
import kokomux.engine.qwen.QwenSpeaker
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The app: one [Engine] for the screen and the listening service. */
class KokomuxApp : Application() {
    val engine by lazy { Engine(this) }

    override fun onCreate() {
        super.onCreate()
        Browser.init(this)
        NewChapters.schedule(this)
    }
}

/** The app's [Engine]. */
val Context.engine: Engine get() = (applicationContext as KokomuxApp).engine

/** A voice cloned with Qwen: its id ("qwen:en-us:Anna"), the name it was given, and what it's made of. */
class Cloned(val voice: String, val name: String, val file: File)

/**
 * What the phone needs once: each engine's files, the text side (Python),
 * Kokoro (loaded once, and again when how it runs changes), Ming and Qwen
 * (loaded when one of their voices is wanted; one of the two at a time, as
 * each takes gigabytes), the voices cloned with Qwen, the bookmarks, the books
 * and the kept parts. One thing at a time uses Python and a model
 * ([exclusive]), as engine.py's SYNTH_LOCK has it, with the CPU kept awake.
 */
class Engine(private val context: Context) {
    val kokoroFiles = Pack.kokoro(context.filesDir)
    val mingFiles = Pack.ming(context.filesDir)
    val qwenFiles = Pack.qwen(context.filesDir)
    val text = Text(context)
    /** Books opened or typed (<id>.epub, .pdf, .txt) and their bookmarks (<id>.mark), as Termux's $BOOKS. */
    val books = File(context.filesDir, "books")
    val bookmarks = Bookmarks(books)
    /** Made parts, kept per book and settings, as Termux's $PREFIX/tmp/kokomux-cache. */
    val cache = File(context.filesDir, "kokomux-cache")
    /** Passages saved while listening. */
    val passages = Passages(File(context.filesDir, "passages.tsv"))
    /** Your pronunciations. */
    val fixes = FixFile(File(context.filesDir, "fixes.tsv"))
    /** What's waiting to be heard after what's playing. */
    val upNext = UpNext(File(context.filesDir, "queue.tsv"))
    /** The listens, for the stats. */
    val stats = File(context.filesDir, "stats/listens.tsv")
    /** Web novels' chapters, kept as they're fetched. */
    val novels = Novels(books, text) { work -> exclusive(work) }
    /** Voices cloned with Qwen: <hash>.qwen (the voice) and <hash>.voice (its id). */
    val clones = File(context.filesDir, "voices")

    private val lock = ReentrantLock()
    private val wake = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kokomux:making").apply { setReferenceCounted(true) }

    @Volatile var kokoro: Kokoro? = null
        private set
    @Volatile private var ming: Ming? = null
    @Volatile private var qwen: Qwen? = null
    /** How onnxruntime runs Kokoro, as kokoro --benchmark tries it: threads (0: its own choice) and block. */
    @Volatile var threads = 0
        private set
    @Volatile var block = 0
        private set
    /** How long Kokoro took to load, the last time it was. */
    @Volatile var loadSeconds = 0.0
        private set

    /** Run [work] with Python and the models to itself, the CPU awake. */
    fun <T> exclusive(work: () -> T): T = lock.withLock {
        wake.acquire(10 * 60 * 1000L)
        try {
            work()
        } finally {
            wake.release()
        }
    }

    /** Kokoro, loaded if it isn't yet. */
    fun kokoro(): Kokoro = exclusive { kokoro ?: load(threads, block).let { kokoro!! } }

    /** (Re)load Kokoro to run this way; the seconds it took. */
    fun load(threads: Int, block: Int): Double = exclusive {
        kokoro?.close()
        kokoro = null
        this.threads = threads
        this.block = block
        val t0 = System.nanoTime()
        kokoro = Kokoro(kokoroFiles.file(Pack.KOKORO_MODEL), kokoroFiles.file(Pack.KOKORO_VOICES), threads, block)
        loadSeconds = (System.nanoTime() - t0) / 1e9
        loadSeconds
    }

    /** Ming on the NPU, loaded if it isn't yet (Qwen let go first); [preparing] hears of a first load's compiling. */
    fun ming(preparing: (Int, Int) -> Unit = { _, _ -> }): Ming = exclusive {
        ming ?: run {
            qwen?.close()
            qwen = null
            Ming(mingFiles.dir, MingNpu.graphs(context, mingFiles.dir, preparing)).also { ming = it }
        }
    }

    /** Qwen, loaded if it isn't yet (Ming let go first). */
    fun qwen(): Qwen = exclusive {
        qwen ?: run {
            ming?.close()
            ming = null
            Qwen(qwenFiles.dir).also { qwen = it }
        }
    }

    /** Let go of an engine whose files are being removed. */
    fun unload(engine: String) = exclusive {
        if (engine == VoiceId.MING) { ming?.close(); ming = null }
        if (engine == VoiceId.QWEN) { qwen?.close(); qwen = null }
    }

    /** "its own choice", "6 threads, block 4": kokoro --benchmark's words. */
    fun tuning() = (if (threads == 0) "its own choice" else "$threads threads") + (if (block > 0) ", block $block" else "")

    /** The text side with each call made [exclusive]. */
    val textSide: TextSide = object : TextSide {
        override fun read(source: String, name: String, lang: String) = exclusive { text.read(source, name, lang) }
        override fun speakable(text: String, lang: String) = exclusive { this@Engine.text.speakable(text, lang) }
        override fun wordRows(text: String, lang: String, speakable: Speakable, timings: List<Timing>) =
            exclusive { this@Engine.text.wordRows(text, lang, speakable, timings) }
    }

    /**
     * The speaker for [voice] reading text in [lang] at [speed] (Kokoro's: the others are made at
     * 1× and sped up as they play), your pronunciations given to it as you spelt them. Loads the
     * engine it needs; call it off the main thread.
     */
    fun speaker(voice: String, speed: Float, lang: String, preparing: (Int, Int) -> Unit = { _, _ -> }): Speaker {
        val plain = plainSpeaker(voice, speed, lang, preparing)
        val yours = Fixes(fixes.all())
        return if (yours.isEmpty) plain else FixedSpeaker(plain, yours)
    }

    private fun plainSpeaker(voice: String, speed: Float, lang: String, preparing: (Int, Int) -> Unit): Speaker =
        when (VoiceId.engine(voice)) {
            VoiceId.MING -> {
                val m = ming(preparing)
                val s = MingSpeaker(m, VoiceId.name(voice), if (lang == "cmn") "zh" else "en")
                Speaker { t -> exclusive { s.say(t) } }
            }
            VoiceId.QWEN -> {
                val q = qwen()
                val clone = cloned(voice) ?: error("That voice is gone")
                val s = QwenSpeaker(q, Clone.load(clone.file), QwenSpeaker.language(lang) ?: "auto")
                Speaker { t -> exclusive { s.say(t) } }
            }
            else -> {
                val k = kokoro()
                KokoroSpeaker(textSide, lang) { sounds -> exclusive { k.speak(sounds, voice, speed) } }
            }
        }

    /** Every voice cloned with Qwen, in the order they were made. */
    fun cloned(): List<Cloned> = clones.listFiles { f -> f.name.endsWith(".qwen") }.orEmpty().sortedBy { it.lastModified() }
        .mapNotNull { f ->
            val voice = File(clones, f.name.removeSuffix(".qwen") + ".voice").takeIf { it.exists() }?.readText()?.trim()
            voice?.let { Cloned(it, VoiceId.name(it), f) }
        }

    fun cloned(voice: String): Cloned? = File(clones, "${fileOf(voice)}.qwen").takeIf { it.exists() }
        ?.let { Cloned(voice, VoiceId.name(voice), it) }

    /** A cloned voice's files are named by its id's hash (its name can be anything). */
    private fun fileOf(voice: String) = kokomux.engine.Books.id(voice.replace("qwen:", "https://qwen/"))

    /**
     * A new voice from [clip] (24 kHz mono) and its [words] (blank if they aren't known), named
     * [name] (made unique), reading [lang]; returns its id ("qwen:en-us:Anna").
     */
    fun clone(clip: FloatArray, words: String, name: String, lang: String): String {
        val made = exclusive { qwen().clone(clip, words) }
        clones.mkdirs()
        val base = name.replace(':', ' ').replace('\n', ' ').trim().ifEmpty { "My voice" }
        val taken = cloned().map { VoiceId.name(it.voice) }.toSet()
        val unique = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in taken }
        val voice = "qwen:$lang:$unique"
        File(clones, "${fileOf(voice)}.voice").writeText(voice)
        made.save(File(clones, "${fileOf(voice)}.qwen"))
        return voice
    }

    /** Forget a cloned voice. */
    fun forget(voice: String) {
        File(clones, "${fileOf(voice)}.qwen").delete()
        File(clones, "${fileOf(voice)}.voice").delete()
    }

    /** The language [voice] reads. */
    fun langOf(voice: String) = Books.langOf(voice)
}
