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
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import java.io.File
import kokomux.app.ui.Actions
import kokomux.app.ui.Checked
import kokomux.app.ui.Fix
import kokomux.app.ui.Found
import kokomux.app.ui.Saved
import kokomux.app.ui.Making
import kokomux.app.ui.Playing
import kokomux.app.ui.Prefs
import kokomux.app.ui.Queued
import kokomux.app.ui.Setup
import kokomux.app.ui.ShelfBook
import kokomux.app.ui.Sleep
import kokomux.app.ui.UiState
import kokomux.app.ui.Voices
import kokomux.app.ui.WordSpan
import kokomux.app.ui.wordSpans
import kokomux.engine.Backup
import kokomux.engine.Book
import kokomux.engine.Books
import kokomux.engine.UpNext
import kokomux.engine.VoiceId
import kokomux.engine.plain
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
    private var playing by mutableStateOf(false)
    private var loaded by mutableStateOf("")
    private var checked by mutableStateOf<Checked?>(null)
    private var checking by mutableStateOf(false)
    private var kept by mutableStateOf(0L)
    private var error by mutableStateOf("")
    private var stats by mutableStateOf<kokomux.engine.Stats?>(null)
    private var passages by mutableStateOf(emptyList<Saved>())
    private var upNext by mutableStateOf(emptyList<Queued>())
    private var fixes by mutableStateOf(emptyList<Fix>())
    private var added by mutableStateOf<Queued?>(null)
    /** The part playing's word timings: (part, words). */
    private var words by mutableStateOf(0 to emptyList<WordSpan>())
    private val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()

    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()   // asked for before the controller connected

    /** The voices and the novels' lists: their own parts of the model. */
    private val studio = VoiceStudio(app, viewModelScope, { prefs }, { controller }, ::voice) { error = it }
    private val novels = NovelLists(engine, viewModelScope) { error = it }

    init {
        if (engine.kokoroFiles.ready()) load()
        val token = SessionToken(app, ComponentName(app, ListenService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            val c = future.get()
            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying
                    refreshShelf()
                    loadStats()
                }
            })
            playing = c.isPlaying
            controller = c
            pending.forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(app))
        refreshShelf()
        loadStats()
        viewModelScope.launch(Dispatchers.IO) { refreshPassages(); refreshFixes() }
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
                    preparing = Listening.preparing,
                )
            },
            voices = studio.voices,
            making = if (fresh.isEmpty()) null else Making(fresh.size, fresh.sumOf { it.speechSecs },
                fresh.sumOf { it.prepSecs + it.voiceSecs }, fresh.sumOf { it.voiceSecs },
                Voices.engineTitle(now?.request?.voice ?: prefs.voice),
                if (VoiceId.engine(now?.request?.voice ?: prefs.voice) == VoiceId.KOKORO) Listening.madeWith else ""),
            checked = checked, checking = checking, loaded = loaded, kept = kept,
            error = error.ifEmpty { Listening.error }, note = Listening.note, hearing = studio.hearing, version = version,
            engines = Voices.engines.mapNotNull { studio.engineFiles[it.first] }, preparing = Listening.preparing,
            recording = studio.recording, clip = studio.clip, cloning = studio.cloning,
            toc = novels.toc.map { e -> e.copy(now = now?.book?.chapterOf(Listening.at.coerceAtLeast(1))?.url == e.url) },
            tocLoading = novels.tocLoading, found = novels.found, searching = novels.searching, stats = stats, passages = passages,
            saving = Export.progress, upNext = upNext, added = added, fixes = fixes,
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
                engine.kokoro()
                studio.loadVoices()
                setup = Setup.Loading("misaki")
                val misaki = engine.exclusive { engine.text.warm("en-us") + engine.text.warm("en-gb") }
                loaded = "Kokoro loaded in ${"%.2f".format(engine.loadSeconds)} s, misaki and spaCy in ${"%.2f".format(misaki)} s"
                setup = Setup.Ready
            } catch (e: Throwable) {
                setup = Setup.Failed(e.plain())
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
                setup = Setup.Failed(e.plain())
            }
        }
    }

    // ------------------------------------------------------------ opening

    /** Listen to something new: now, or after what's playing (it waits in Up next). */
    private fun open(id: String, kind: String, source: String, name: String) {
        if (Listening.now == null && !Listening.opening) return play(UpNext.Item(id, kind, source, name))
        if (id == Listening.now?.request?.id) return
        viewModelScope.launch(Dispatchers.IO) {
            engine.upNext.add(UpNext.Item(id, kind, source, name))
            refreshQueue()
            added = Queued(id, name, kind)
        }
    }

    private fun play(item: UpNext.Item) =
        command(ListenService.OPEN, Request(item.id, item.kind, item.source, item.title, prefs.voice, prefs.speed).toBundle())

    // ------------------------------------------------------------ Up next

    override fun playQueued(id: String) {
        added = null
        engine.upNext.all().firstOrNull { it.id == id }?.let(::play)
    }

    override fun unqueue(id: String) {
        if (added?.id == id) added = null
        viewModelScope.launch(Dispatchers.IO) {
            val gone = engine.upNext.remove(id)
            // (its own copy goes too, unless it's a book on the shelf)
            if (gone != null && gone.kind != "url" && engine.bookmarks.get(id) == null) File(gone.source).delete()
            refreshQueue()
        }
    }

    override fun dismissAdded() {
        added = null
    }

    private fun refreshQueue() {
        upNext = engine.upNext.all().map { Queued(it.id, it.title, it.kind) }
    }

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
                error = "Couldn't open that file (${e.plain()})"
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
        studio.refreshVoices()
        if (Listening.now != null) command(ListenService.VOICE, Bundle().apply { putString("voice", voice) })
    }

    // ------------------------------------------------------------ voices (VoiceStudio's)

    override fun hear(voice: String) {
        if (setup == Setup.Ready) studio.hear(voice)
    }

    // ------------------------------------------------------------ your pronunciations

    override fun setFix(word: String, sayAs: String) {
        if (word.isBlank() || sayAs.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            engine.fixes.set(word, sayAs)
            fixesChanged()
        }
    }

    override fun removeFix(word: String) {
        viewModelScope.launch(Dispatchers.IO) {
            engine.fixes.remove(word)
            fixesChanged()
        }
    }

    override fun hearFix(sayAs: String) {
        if (setup == Setup.Ready && sayAs.isNotBlank()) studio.say(prefs.voice, sayAs.trim())
    }

    /** The pronunciations changed: the book playing says what's still to come with them. */
    private fun fixesChanged() {
        refreshFixes()
        if (Listening.now != null) viewModelScope.launch { command(ListenService.REMAKE) }
    }

    private fun refreshFixes() {
        fixes = engine.fixes.all().map { Fix(it.first, it.second) }
    }

    override fun fetchEngine(id: String) = studio.fetchEngine(id)

    override fun removeEngine(id: String) {
        if (id == VoiceId.KOKORO) return
        if (VoiceId.engine(prefs.voice) == id) prefs(prefs.copy(voice = Prefs().voice))
        studio.removeEngine(id)
    }

    override fun record() = studio.record()
    override fun stopRecording() = studio.stopRecording()
    override fun pickClip(uri: Uri) = studio.pickClip(uri)
    override fun playClip() = studio.playClip()
    override fun dropClip() = studio.dropClip()
    override fun makeVoice(words: String, name: String, lang: String) = studio.makeVoice(words, name, lang)

    override fun forgetVoice(voice: String) {
        if (prefs.voice == voice) prefs(prefs.copy(voice = Prefs().voice))
        studio.forgetVoice(voice)
    }

    // ------------------------------------------------------------ audiobooks

    override fun saveAudiobook(uri: Uri, from: Int, to: Int) {
        val now = Listening.now ?: return
        Export.start(getApplication(), Export.Job(now.book, now.request, now.lang, from, to, uri))
    }

    // ------------------------------------------------------------ saved passages

    override fun savePassage() {
        val now = Listening.now ?: return
        val at = Listening.at.takeIf { it > 0 } ?: return
        val pos = position()
        // Just into a part, the sentence just heard is the last of the part before.
        val (part, offset) = if (pos < 1500 && at > 1) (at - 1) to Int.MAX_VALUE
        else at to (words.second.lastOrNull { it.start <= pos }?.from ?: 0)
        val text = now.book.parts.getOrNull(part - 1)?.text ?: return
        val sentence = Passages.sentence(text, offset.coerceAtMost(text.length - 1))
        val p = Passages.Passage(System.currentTimeMillis(), now.request.id, now.book.novel?.title ?: now.book.title,
            now.book.chapterOf(part)?.title.orEmpty(), part, sentence)
        viewModelScope.launch(Dispatchers.IO) {
            engine.passages.save(p)
            refreshPassages()
        }
        Listening.note = "Saved: “${sentence.take(55)}${if (sentence.length > 55) "…" else ""}”"
    }

    override fun removePassage(saved: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            engine.passages.remove(saved)
            refreshPassages()
        }
    }

    override fun goToPassage(p: Saved) {
        if (Listening.now?.request?.id == p.book) jump(p.part) else resume(p.book)
    }

    private fun refreshPassages() {
        passages = engine.passages.all().map { Saved(it.saved, it.book, it.title, it.chapter, it.part, it.text) }
    }

    // ------------------------------------------------------------ stats

    override fun loadStats() {
        viewModelScope.launch(Dispatchers.IO) {
            stats = kokomux.engine.Stats(kokomux.engine.Sessions(engine.stats).all(), java.time.ZoneId.systemDefault(),
                java.time.LocalDate.now())
        }
    }

    // ------------------------------------------------------------ web novels

    override fun loadToc() {
        Listening.now?.book?.let { novels.loadToc(it, Listening.at) }
    }

    override fun chapter(url: String) {
        val b = Listening.now?.book ?: return
        val inListen = b.chapters.firstOrNull { it.url == url }
        if (inListen != null) jump(inListen.first)
        else command(ListenService.CHAPTER, Bundle().apply { putString("url", url) })
    }

    override fun search(query: String) = novels.search(query)
    override fun novelToc(found: Found) = novels.novelToc(found)

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
                    error = e.plain()
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
                error = e.plain()
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

    // ------------------------------------------------------------ backups

    override fun saveBackup(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Backup.save(app.filesDir, store.values(), version, app.contentResolver.openOutputStream(uri)!!)
                Listening.note = "Backup saved"
            } catch (e: Exception) {
                error = "Couldn't save the backup (${e.plain()})"
            }
        }
    }

    /** Add a backup to what's here; its settings are taken on a phone whose own haven't been changed. */
    override fun restoreBackup(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val got = Backup.restore(app.filesDir, app.contentResolver.openInputStream(uri)!!)
                if (store.fresh && got.settings.isNotEmpty()) {
                    store.take(got.settings)
                    withContext(Dispatchers.Main) { prefs(store.load()) }
                }
                studio.loadVoices()
                refreshPassages()
                refreshFixes()
                refreshShelf()
                loadStats()
                Listening.note = "Restored: ${got.inWords()}"
            } catch (e: Exception) {
                error = "Couldn't restore that (${e.plain()})"
            }
        }
    }

    override fun dismissError() {
        error = ""
        Listening.error = ""
        Listening.note = ""
    }

    /** The bookshelf, from the bookmarks, what's waiting in Up next, and the kept audio's size. */
    fun refreshShelf() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshQueue()
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
        studio.release()
    }

    companion object {
        private val URL = Regex("^https?://\\S+$")
    }
}
