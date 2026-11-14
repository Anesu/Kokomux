package kokomux.app

import android.app.Application
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import java.io.File
import kokomux.app.ui.Actions
import kokomux.app.ui.Checked
import kokomux.app.ui.Clip
import kokomux.app.ui.EngineFiles
import kokomux.app.ui.Found
import kokomux.app.ui.TocEntry
import kokomux.app.ui.Making
import kokomux.app.ui.Playing
import kokomux.app.ui.Prefs
import kokomux.app.ui.Setup
import kokomux.app.ui.ShelfBook
import kokomux.app.ui.Sleep
import kokomux.app.ui.UiState
import kokomux.app.ui.Voices
import kokomux.app.ui.WordSpan
import kokomux.app.ui.wordSpans
import kokomux.engine.Audio
import kokomux.engine.Book
import kokomux.engine.Books
import kokomux.engine.VoiceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * What the screens show and what they can do: the screen's state made from
 * the listening service's (Listening), the settings and the bookshelf; each
 * action turned into a command to the service, or done here.
 */
class AppModel(app: Application) : AndroidViewModel(app), Actions {
    private val engine = app.engine
    private val store = Store(app)

    private var prefs by mutableStateOf(store.load())
    private var setup by mutableStateOf<Setup>(Setup.Missing)
    private var shelf by mutableStateOf(emptyList<ShelfBook>())
    private var voices by mutableStateOf(emptyList<String>())
    private var playing by mutableStateOf(false)
    private var loaded by mutableStateOf("")
    private var checked by mutableStateOf<Checked?>(null)
    private var checking by mutableStateOf(false)
    private var kept by mutableStateOf(0L)
    private var error by mutableStateOf("")
    private var hearing by mutableStateOf("")
    private var engineFiles by mutableStateOf(emptyMap<String, EngineFiles>())
    private var recording by mutableStateOf<Pair<Double, Float>?>(null)
    private var clip by mutableStateOf<Clip?>(null)
    private var clipSamples: FloatArray? = null
    private var recorder: Clips.Recorder? = null
    private var cloning by mutableStateOf(false)
    private var toc by mutableStateOf(emptyList<TocEntry>())
    private var tocLoading by mutableStateOf(false)
    private var found by mutableStateOf(emptyList<Found>())
    private var searching by mutableStateOf(false)
    /** The part playing's word timings: (part, words). */
    private var words by mutableStateOf(0 to emptyList<WordSpan>())
    private val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()

    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()   // asked for before the controller connected
    private var preview: ExoPlayer? = null

    init {
        if (engine.kokoroFiles.ready()) load()
        refreshEngines()
        val token = SessionToken(app, ComponentName(app, ListenService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            val c = future.get()
            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying
                    refreshShelf()
                }
            })
            playing = c.isPlaying
            controller = c
            pending.forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(app))
        refreshShelf()
        // The part playing's word timings, read when it changes or is made.
        viewModelScope.launch { snapshotFlow { Listening.at to Listening.made.size }.collect { readWords() } }
    }

    /** Everything the screens show (read while composing, so they follow every change). */
    fun state(): UiState {
        val now = Listening.now
        val made = Listening.made.toList()
        val fresh = made.filter { !it.kept }
        return UiState(
            setup = setup, prefs = prefs, shelf = shelf, opening = Listening.opening,
            playing = now?.let { n ->
                Playing(
                    id = n.request.id, title = n.book.title, parts = n.book.parts.map { it.text }, chapters = n.book.chapters,
                    at = Listening.at, playing = playing, madeTo = made.maxOfOrNull { it.index } ?: 0, making = Listening.making,
                    voice = n.request.voice, speed = n.request.speed, sleep = Listening.sleep,
                    words = if (words.first == Listening.at) words.second else emptyList(),
                    left = left(n.book, Listening.at, n.request.speed), novel = n.book.novel != null,
                )
            },
            voices = voices,
            making = if (fresh.isEmpty()) null else Making(fresh.size, fresh.sumOf { it.speechSecs },
                fresh.sumOf { it.prepSecs + it.voiceSecs }, fresh.sumOf { it.voiceSecs },
                Voices.engineTitle(now?.request?.voice ?: prefs.voice),
                if (VoiceId.engine(now?.request?.voice ?: prefs.voice) == VoiceId.KOKORO) Listening.madeWith else ""),
            checked = checked, checking = checking, loaded = loaded, kept = kept,
            error = error.ifEmpty { Listening.error }, note = Listening.note, hearing = hearing, version = version,
            engines = Voices.engines.mapNotNull { engineFiles[it.first] }, preparing = Listening.preparing,
            recording = recording, clip = clip, cloning = cloning,
            toc = toc.map { e -> e.copy(now = now?.book?.chapterOf(Listening.at.coerceAtLeast(1))?.url == e.url) },
            tocLoading = tocLoading, found = found, searching = searching,
        )
    }

    /** Letters in the book from each part to the end (for the time left), worked out once a book. */
    private var lettersFrom: Pair<Book?, LongArray> = null to LongArray(0)

    /** Seconds of listening from part [at] to the end, at [speed], at the pace the parts made so far were said. */
    private fun left(book: Book, at: Int, speed: Float): Double {
        if (Listening.perLetter <= 0 || at < 1) return 0.0
        if (lettersFrom.first !== book) {
            val from = LongArray(book.parts.size + 1)
            for (i in book.parts.indices.reversed()) from[i] = from[i + 1] + book.parts[i].text.length
            lettersFrom = book to from
        }
        return lettersFrom.second[(at - 1).coerceAtMost(book.parts.size)] * Listening.perLetter / speed
    }

    /** How far into the part playing the player is, in ms (for lighting the word being said). */
    fun position(): Long = controller?.currentPosition ?: 0L

    /** A web address on the clipboard, if there is one (to fill in "Read a web page"). */
    fun link(): String = clipboard().takeIf { it.matches(URL) }.orEmpty()

    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                setup = Setup.Loading("Kokoro")
                // (if a book is playing, Kokoro is already loaded: it isn't loaded again under it)
                if (engine.kokoro == null) engine.load(prefs.threads, prefs.block)
                voices = engine.kokoro().voices.names + Voices.ming + engine.cloned().map { it.voice }
                setup = Setup.Loading("misaki")
                val misaki = engine.exclusive { engine.text.warm("en-us") + engine.text.warm("en-gb") }
                loaded = "Kokoro loaded in ${"%.2f".format(engine.loadSeconds)} s, misaki and spaCy in ${"%.2f".format(misaki)} s"
                setup = Setup.Ready
            } catch (e: Throwable) {
                setup = Setup.Failed(e.message ?: e.toString())
            }
        }
    }

    override fun download() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    var shown = -1L
                    engine.kokoroFiles.download { done, total ->
                        if (done - shown >= 1 shl 20 || done == total) {
                            shown = done
                            setup = Setup.Downloading("Kokoro", done, total)
                        }
                    }
                }
                load()
            } catch (e: Exception) {
                setup = Setup.Failed(e.message ?: e.toString())
            }
        }
    }

    // ------------------------------------------------------------ opening

    private fun open(id: String, kind: String, source: String, name: String) =
        command(ListenService.OPEN, Request(id, kind, source, name, prefs.voice, prefs.speed).toBundle())

    override fun openFile(uri: Uri) {
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
                withContext(Dispatchers.Main) { open(id, "file", file.path, name.substringBeforeLast('.')) }
            } catch (e: Exception) {
                error = "Couldn't open that file (${e.message})"
            }
        }
    }

    override fun openPage(address: String) = open(Books.id(address), "url", address, address)

    override fun readClipboard() {
        val text = clipboard().trim()
        when {
            text.isEmpty() -> error = "The clipboard is empty"
            text.matches(URL) -> openPage(text)                          // a copied link reads that page
            else -> readTyped(text)
        }
    }

    override fun readTyped(text: String) {
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
            withContext(Dispatchers.Main) { open(id, "text", file.path, name) }
        }
    }

    override fun resume(id: String) {
        val m = engine.bookmarks.get(id) ?: return
        command(ListenService.OPEN, Request(m.id, m.kind, m.source, m.title, m.voice, m.speed).toBundle())
    }

    override fun remove(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            engine.bookmarks.remove(id)
            File(engine.cache, id).deleteRecursively()
            engine.books.listFiles { f -> f.name.startsWith("$id.") }?.forEach { it.delete() }
            refreshShelf()
        }
    }

    // ------------------------------------------------------------ listening

    override fun playPause() = withController { if (it.isPlaying) it.pause() else it.play() }
    override fun previous() = withController {
        val at = Listening.at
        if (it.currentPosition < 3000 && at > 1) jump(at - 1) else it.seekTo(0)
    }
    override fun again() = withController { it.seekTo(0) }
    override fun next() = withController { it.seekToNextMediaItem() }
    override fun stop() = command(ListenService.STOP)
    override fun jump(part: Int) = command(ListenService.JUMP, Bundle().apply { putInt("part", part) })

    override fun speed(speed: Float) {
        prefs(prefs.copy(speed = speed))
        if (Listening.now != null) command(ListenService.SPEED, Bundle().apply { putFloat("speed", speed) })
    }

    override fun sleep(choice: Sleep?) = command(ListenService.SLEEP, Bundle().apply {
        when (choice) {
            is Sleep.Minutes -> { putString("kind", "minutes"); putLong("ms", choice.leftMs) }
            Sleep.EndOfChapter -> putString("kind", "chapter")
            null -> putString("kind", "off")
        }
    })

    override fun voice(voice: String) {
        prefs(prefs.copy(voice = voice))
        refreshVoices()
        if (Listening.now != null) command(ListenService.VOICE, Bundle().apply { putString("voice", voice) })
    }

    /** A voice saying a line in its own language, the book paused meanwhile (tui.sh's voice_sample). */
    override fun hear(voice: String) {
        if (hearing.isNotEmpty() || setup != Setup.Ready) return
        hearing = voice
        val file = File(getApplication<Application>().cacheDir, "sample.wav")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val said = engine.speaker(voice, prefs.speed, Books.langOf(voice)).say(Voices.sample(voice))
                Audio.writeWav(file, said.samples, said.rate)
                withContext(Dispatchers.Main) {
                    val resume = controller?.isPlaying == true
                    controller?.pause()
                    val p = preview ?: ExoPlayer.Builder(getApplication()).build().also { preview = it }
                    p.clearMediaItems()
                    p.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state != Player.STATE_ENDED) return
                            p.removeListener(this)
                            hearing = ""
                            if (resume) controller?.play()
                        }
                    })
                    p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    p.setPlaybackSpeed(if (VoiceId.makesAtSpeed(voice)) 1f else prefs.speed)
                    p.prepare()
                    p.play()
                }
            } catch (e: Throwable) {
                hearing = ""
                error = e.message ?: e.toString()
            }
        }
    }

    // ------------------------------------------------------------ engines and new voices

    private fun pack(id: String) = when (id) {
        VoiceId.MING -> engine.mingFiles
        VoiceId.QWEN -> engine.qwenFiles
        else -> engine.kokoroFiles
    }

    /** What each engine has on the phone. */
    private fun refreshEngines() {
        viewModelScope.launch(Dispatchers.IO) {
            engineFiles = Voices.engines.associate { (id, title) ->
                val p = pack(id)
                id to (engineFiles[id]?.takeIf { it.downloading } ?: EngineFiles(id, title, p.total,
                    if (p.ready()) p.total else p.size().coerceAtMost(p.total - 1)))
            }
        }
    }

    override fun fetchEngine(id: String) {
        if (engineFiles[id]?.downloading == true) return
        val p = pack(id)
        engineFiles = engineFiles + (id to EngineFiles(id, p.title, p.total, done = 0))
        viewModelScope.launch(Dispatchers.IO) {
            var shown = -1L
            try {
                p.download { done, total ->
                    if (done - shown >= 4 shl 20 || done == total) {
                        shown = done
                        engineFiles = engineFiles + (id to EngineFiles(id, p.title, total, done = done))
                    }
                }
                engineFiles = engineFiles + (id to EngineFiles(id, p.title, p.total, p.total))
            } catch (e: Exception) {
                engineFiles = engineFiles + (id to EngineFiles(id, p.title, p.total, p.size(), error = e.message ?: e.toString()))
            }
        }
    }

    override fun removeEngine(id: String) {
        if (id == VoiceId.KOKORO) return
        if (VoiceId.engine(prefs.voice) == id) prefs(prefs.copy(voice = Prefs().voice))
        viewModelScope.launch(Dispatchers.IO) {
            engine.unload(id)
            pack(id).remove()
            refreshEngines()
        }
    }

    override fun record() {
        if (recorder != null) return
        clip = null
        clipSamples = null
        recording = 0.0 to 0f
        recorder = Clips.Recorder { secs, level -> viewModelScope.launch { if (recorder != null) recording = secs to level } }
            .also { it.start() }
    }

    override fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        viewModelScope.launch(Dispatchers.IO) {
            val pcm = r.stop()
            recording = null
            useClip(pcm)
        }
    }

    override fun pickClip(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                useClip(Clips.decode(getApplication(), uri))
            } catch (e: Exception) {
                error = "Couldn't read that clip (${e.message})"
            }
        }
    }

    private fun useClip(pcm: FloatArray) {
        if (pcm.size < Clips.RATE) {
            error = "That clip is too short: a few seconds of speaking is right"
            return
        }
        clipSamples = pcm
        clip = Clip(pcm.size.toDouble() / Clips.RATE)
        Audio.writeWav(File(getApplication<Application>().cacheDir, "clip.wav"), pcm, Clips.RATE)
    }

    override fun playClip() {
        val c = clip ?: return
        val p = preview ?: ExoPlayer.Builder(getApplication()).build().also { preview = it }
        if (c.playing) {
            p.stop()
            clip = c.copy(playing = false)
            return
        }
        controller?.pause()
        p.clearMediaItems()
        p.setPlaybackSpeed(1f)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED && state != Player.STATE_IDLE) return
                p.removeListener(this)
                clip = clip?.copy(playing = false)
            }
        })
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(getApplication<Application>().cacheDir, "clip.wav"))))
        p.prepare()
        p.play()
        clip = c.copy(playing = true)
    }

    override fun dropClip() {
        recorder?.stop()
        recorder = null
        recording = null
        clip = null
        clipSamples = null
    }

    override fun makeVoice(words: String, name: String, lang: String) {
        val pcm = clipSamples ?: return
        if (cloning) return
        cloning = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val voice = engine.clone(pcm, words, name, lang)
                withContext(Dispatchers.Main) {
                    dropClip()
                    voice(voice)
                }
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            } finally {
                cloning = false
            }
        }
    }

    override fun forgetVoice(voice: String) {
        if (prefs.voice == voice) prefs(prefs.copy(voice = Prefs().voice))
        viewModelScope.launch(Dispatchers.IO) {
            engine.forget(voice)
            refreshVoices()
        }
    }

    /** Every voice there is: Kokoro's, Ming's, and those cloned with Qwen. */
    private fun refreshVoices() {
        viewModelScope.launch(Dispatchers.IO) {
            val kokoro = engine.kokoro?.voices?.names ?: voices.filter { VoiceId.engine(it) == VoiceId.KOKORO }
            voices = kokoro + Voices.ming + engine.cloned().map { it.voice }
        }
    }

    // ------------------------------------------------------------ web novels

    override fun loadToc() {
        val b = Listening.now?.book ?: return
        val c = b.chapters.lastOrNull()?.takeIf { b.novel != null } ?: return
        tocLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val got = engine.novels.chapter(c.url)?.first ?: return@launch
                val playing = b.chapterOf(Listening.at.coerceAtLeast(1))?.number ?: c.number
                toc = engine.novels.toc(got).map { (n, label, url) -> TocEntry(n, label, url, n < playing, false) }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                tocLoading = false
            }
        }
    }

    override fun chapter(url: String) {
        val b = Listening.now?.book ?: return
        val inListen = b.chapters.firstOrNull { it.url == url }
        if (inListen != null) jump(inListen.first)
        else command(ListenService.CHAPTER, Bundle().apply { putString("url", url) })
    }

    override fun search(query: String) {
        if (query.isBlank()) return
        searching = true
        found = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                found = engine.exclusive { engine.text.search(query) }.map { (title, url, latest) -> Found(title, url, latest) }
                if (found.isEmpty()) error = "No novel called that"
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                searching = false
            }
        }
    }

    override fun novelToc(found: Found) {
        tocLoading = true
        toc = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val heard = engine.bookmarks.get(Books.id(found.url))?.number ?: 0
                val list = engine.exclusive { engine.text.toc(found.url, "") }
                toc = list.map { (n, label, url) -> TocEntry(n, label, url, n < heard, n == heard) }
                if (toc.isEmpty()) error = "Couldn't find that novel's chapters"
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                tocLoading = false
            }
        }
    }

    // ------------------------------------------------------------ settings and checks

    override fun prefs(prefs: Prefs) {
        val before = this.prefs
        this.prefs = prefs
        store.save(prefs)
        if (prefs.threads != before.threads || prefs.block != before.block) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val secs = engine.load(prefs.threads, prefs.block)
                    loaded = "Kokoro loaded in ${"%.2f".format(secs)} s (${engine.tuning()})"
                } catch (e: Throwable) {
                    error = e.message ?: e.toString()
                }
            }
        }
    }

    override fun check() {
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

    override fun clearKept() {
        viewModelScope.launch(Dispatchers.IO) {
            engine.cache.listFiles()?.forEach { it.deleteRecursively() }
            refreshShelf()
        }
    }

    override fun dismissError() {
        error = ""
        Listening.error = ""
        Listening.note = ""
    }

    /** The bookshelf, from the bookmarks, and the kept audio's size. */
    fun refreshShelf() {
        viewModelScope.launch(Dispatchers.IO) {
            shelf = engine.bookmarks.all().map {
                val latest = if (it.kind == "novel") engine.novels.known(it.novel)?.latest ?: it.number else 0
                ShelfBook(it.id, it.title, it.part, it.parts, it.voice, it.chapter, it.number, maxOf(latest, it.number), it.done)
            }
            kept = engine.cache.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    }

    /** The part playing's word timings, from its .words file (byte ranges in its text, as the Termux player reads them). */
    private fun readWords() {
        val at = Listening.at
        val text = Listening.now?.book?.parts?.getOrNull(at - 1)?.text ?: return
        val file = Listening.made.lastOrNull { it.index == at }?.words ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val spans = wordSpans(text, runCatching { file.readLines() }.getOrDefault(emptyList()))
            words = at to spans
        }
    }

    // ------------------------------------------------------------ the service

    private fun command(action: String, args: Bundle = Bundle.EMPTY) = withController {
        error = ""
        it.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
    }

    private fun withController(then: (MediaController) -> Unit) {
        val c = controller
        if (c != null) then(c) else pending += then
    }

    private fun clipboard(): String {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(getApplication())?.toString().orEmpty()
    }

    override fun onCleared() {
        controller?.release()
        preview?.release()
    }

    companion object {
        private val URL = Regex("^https?://\\S+$")
    }
}
