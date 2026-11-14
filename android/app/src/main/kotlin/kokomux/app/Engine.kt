package kokomux.app

import android.app.Application
import android.content.Context
import android.os.PowerManager
import kokomux.engine.Bookmarks
import kokomux.engine.Kokoro
import kokomux.engine.Speakable
import kokomux.engine.TextSide
import kokomux.engine.Timing
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The app: one [Engine] for the screen and the listening service. */
class KokomuxApp : Application() {
    val engine by lazy { Engine(this) }
}

/** The app's [Engine]. */
val Context.engine: Engine get() = (applicationContext as KokomuxApp).engine

/**
 * What the phone needs once: the model files, the text side (Python),
 * Kokoro (loaded once, and again when how it runs changes), the bookmarks,
 * the books and the kept parts. One thing at a time uses Python and the model
 * ([exclusive]), as engine.py's SYNTH_LOCK has it, with the CPU kept awake.
 */
class Engine(private val context: Context) {
    val models = Models(File(context.filesDir, "models"))
    val text = Text(context)
    /** Books opened or typed (<id>.epub, .pdf, .txt) and their bookmarks (<id>.mark), as Termux's $BOOKS. */
    val books = File(context.filesDir, "books")
    val bookmarks = Bookmarks(books)
    /** Made parts, kept per book and settings, as Termux's $PREFIX/tmp/kokomux-cache. */
    val cache = File(context.filesDir, "kokomux-cache")

    private val lock = ReentrantLock()
    private val wake = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kokomux:making").apply { setReferenceCounted(true) }

    @Volatile var kokoro: Kokoro? = null
        private set
    /** How onnxruntime runs Kokoro, as kokoro --benchmark tries it: threads (0: its own choice) and block. */
    @Volatile var threads = 0
        private set
    @Volatile var block = 0
        private set
    /** How long Kokoro took to load, the last time it was. */
    @Volatile var loadSeconds = 0.0
        private set

    /** Run [work] with Python and the model to itself, the CPU awake. */
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
        kokoro = Kokoro(models.model, models.voices, threads, block)
        loadSeconds = (System.nanoTime() - t0) / 1e9
        loadSeconds
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
}
