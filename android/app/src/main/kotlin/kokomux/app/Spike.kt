package kokomux.app

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import kokomux.engine.Bookmarks
import kokomux.engine.Books
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Where the app is: models to fetch, fetching, loading, ready, or stuck. */
sealed interface Stage {
    data object Missing : Stage
    data class Downloading(val label: String, val done: Long, val total: Long) : Stage
    data class Loading(val what: String) : Stage
    data object Ready : Stage
    data class Failed(val message: String) : Stage
}

/** A pronunciation check: how many parts, how many sound different, and which. */
data class Checked(val cases: Int, val seconds: Double, val differ: List<Triple<String, String, String>>)

/**
 * The one screen's state: open something to hear (a typed text, a file, a web
 * page, or a book on the shelf), the controls, and the checks. Listening
 * itself is [ListenService]'s; this talks to it through a MediaController.
 */
class Spike(app: Application) : AndroidViewModel(app) {
    private val engine = app.engine
    var stage by mutableStateOf<Stage>(Stage.Missing)
        private set
    var text by mutableStateOf(SAMPLE)
    var address by mutableStateOf("")
    var voice by mutableStateOf("af_heart")
    var voices by mutableStateOf(listOf("af_heart"))
        private set
    var threads by mutableStateOf(engine.threads)
        private set
    var block by mutableStateOf(engine.block)
        private set
    var playing by mutableStateOf(false)
        private set
    var loaded by mutableStateOf("")
        private set
    var checked by mutableStateOf<Checked?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    var error by mutableStateOf("")
        private set
    var shelf by mutableStateOf(emptyList<Bookmarks.Mark>())
        private set

    private var controller: MediaController? = null
    private var pending: Request? = null          // asked for before the controller was connected

    init {
        if (engine.models.ready()) load()
        val token = SessionToken(app, ComponentName(app, ListenService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            controller = future.get().apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playing = isPlaying
                        refreshShelf()
                    }
                })
                playing = isPlaying
            }
            pending?.let { pending = null; send(it) }
        }, ContextCompat.getMainExecutor(app))
        refreshShelf()
    }

    /** Fetch the model and voices (about 354 MB), then load them. */
    fun download() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    var shown = -1L
                    engine.models.download { label, done, total ->
                        if (done - shown >= 1 shl 20 || done == total) {
                            shown = done
                            stage = Stage.Downloading(label, done, total)
                        }
                    }
                }
                load()
            } catch (e: Exception) {
                stage = Stage.Failed(e.message ?: e.toString())
            }
        }
    }

    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                stage = Stage.Loading("Kokoro")
                voices = engine.kokoro().voices.names          // (already loaded if a book is playing)
                stage = Stage.Loading("misaki")
                val misakiSecs = engine.exclusive { engine.text.warm("en-us") + engine.text.warm("en-gb") }
                loaded = "Kokoro loaded in ${fmt(engine.loadSeconds)} s, misaki and spaCy in ${fmt(misakiSecs)} s"
                stage = Stage.Ready
            } catch (e: Throwable) {
                stage = Stage.Failed(e.message ?: e.toString())
            }
        }
    }

    /** Run Kokoro another way: [threads] (0: onnxruntime's own choice) and [block] (0: off). */
    fun tune(threads: Int, block: Int) {
        if (stage != Stage.Ready || (threads == this.threads && block == this.block)) return
        stopListening()
        this.threads = threads
        this.block = block
        viewModelScope.launch(Dispatchers.IO) {
            try {
                stage = Stage.Loading("Kokoro")
                val secs = engine.load(threads, block)
                loaded = "Kokoro loaded in ${fmt(secs)} s (${engine.tuning()})"
                stage = Stage.Ready
            } catch (e: Throwable) {
                stage = Stage.Failed(e.message ?: e.toString())
            }
        }
    }

    /** Hear the typed text: it's kept as a book of its own, as Termux keeps a long text. */
    fun playText() {
        val t = text.trim()
        if (t.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            engine.books.mkdirs()
            val tmp = File(engine.books, "typed.tmp").apply { writeText(t) }
            val id = Books.id(tmp.path)
            val file = File(engine.books, "$id.txt")
            tmp.renameTo(file)
            val words = t.split(Regex("\\s+"))
            val name = words.take(6).joinToString(" ") + if (words.size > 6) "…" else ""
            send(Request(id, "text", file.path, name, voice))
        }
    }

    /** Hear a file the system's picker gave (an EPUB, a PDF or text): a copy is kept as the book. */
    fun openFile(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "book"
                engine.books.mkdirs()
                val tmp = File(engine.books, "opened.tmp")
                resolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val id = Books.id(tmp.path)
                val ext = name.substringAfterLast('.', "txt").lowercase().takeIf { it in setOf("epub", "pdf", "txt") } ?: "txt"
                val file = File(engine.books, "$id.$ext")
                tmp.renameTo(file)
                send(Request(id, "file", file.path, name.substringBeforeLast('.'), voice))
            } catch (e: Exception) {
                error = "Couldn't open that file (${e.message})"
            }
        }
    }

    /** Hear the web page at [address]. */
    fun openAddress() {
        val url = address.trim()
        if (!url.matches(Regex("^https?://\\S+$"))) {
            error = "That isn't a web address (https://…)"
            return
        }
        send(Request(Books.id(url), "url", url, url, voice))
    }

    /** Carry on with a book on the shelf, in the voice it was in. */
    fun resume(mark: Bookmarks.Mark) =
        send(Request(mark.id, mark.kind, mark.source, mark.title, mark.voice, mark.speed))

    private fun send(request: Request) {
        viewModelScope.launch(Dispatchers.Main) {
            error = ""
            val c = controller ?: run { pending = request; return@launch }
            c.sendCustomCommand(SessionCommand(ListenService.OPEN, Bundle.EMPTY), request.toBundle())
        }
    }

    /** Pause, or carry on. */
    fun pauseOrPlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    /** Skip to the next part. */
    fun next() {
        controller?.seekToNextMediaItem()
    }

    /** Hear this part again. */
    fun again() {
        controller?.seekTo(0)
    }

    /** Stop, keeping the bookmark. */
    fun stopListening() {
        controller?.sendCustomCommand(SessionCommand(ListenService.STOP, Bundle.EMPTY), Bundle.EMPTY)
        refreshShelf()
    }

    /** The books in progress, from their bookmarks. */
    fun refreshShelf() {
        viewModelScope.launch(Dispatchers.IO) { shelf = engine.bookmarks.all() }
    }

    /** Run pronunciation.json's sentences through misaki here and compare with engine.py's sounds. */
    fun check() {
        if (checking) return
        checking = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val r = JSONObject(engine.exclusive { engine.text.check() })
                val differ = r.getJSONArray("differ")
                checked = Checked(r.getInt("cases"), r.getDouble("seconds"), (0 until differ.length()).map {
                    val d = differ.getJSONObject(it)
                    Triple("${d.getString("lang")}: ${d.getString("text")}", d.getString("want"), d.getString("got"))
                })
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            } finally {
                checking = false
            }
        }
    }

    override fun onCleared() {
        controller?.release()
    }

    companion object {
        // engine.py's BENCH_TEXT, so the numbers compare with kokoro --benchmark.
        const val SAMPLE = "The road left the village at first light and climbed into the hills, where the fields " +
            "gave way to heather. For three days she walked without meeting anyone, sleeping in the " +
            "lee of stone walls and waking to the sound of sheep."

        fun fmt(s: Double) = "%.2f".format(s)
    }
}
