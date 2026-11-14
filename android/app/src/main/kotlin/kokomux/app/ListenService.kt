package kokomux.app

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import kokomux.app.ui.Sleep
import kokomux.engine.Book
import kokomux.engine.Bookmarks
import kokomux.engine.Books
import kokomux.engine.Maker
import kokomux.engine.VoiceId
import kokomux.engine.plain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Listening, in the background: one ExoPlayer plays the whole book as a
 * gapless playlist, with the notification and lock-screen controls, while
 * the parts are made ([Maker]) about ten minutes ahead of it and appended
 * each as it's ready. As in Termux:
 *  - the bookmark follows the part playing; a book heard to the end loses its
 *    bookmark, its kept parts and its own copy
 *  - a new speed is heard at once: the part playing carries on at it, pitch
 *    kept, while the rest is made again at that speed
 *  - a new voice starts again at the part you're on
 *  - the sleep timer fades the voice out over its last 13 seconds, counting
 *    only time spent playing, and keeps your place
 *  - carrying on after a minute away goes back 5 seconds first (10 after an hour)
 *  - each listen (from play to a pause of 3 minutes or more) is kept for the
 *    stats: how long, from where to where, the words and chapters heard
 *  - a web novel goes on from chapter to chapter: as the chapter is made, the
 *    next is fetched (or taken from those kept ahead) and added on the end;
 *    its bookmark is the novel's, the chapter and the part in it, and stays,
 *    done, once the latest chapter has been heard
 *  - when a book ends, what's first in Up next is listened to
 *
 * The sleep timer is [SleepTimer]'s and the listens are [ListenLog]'s.
 *
 * Everything here happens on the main thread, in [scope]; reading a book and
 * making its parts wait on the threads for blocking work. Each is a job
 * ([reading], [making]): opening another book, a jump, a new speed or voice
 * cancels the one going, and a cancelled job changes nothing more.
 *
 * The screen connects a MediaController and sends the commands below.
 */
class ListenService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** A book being read, to open it. */
    private var reading: Job? = null
    /** The parts being made, and a novel's next chapters fetched after them. */
    private var making: Job? = null
    private lateinit var sleepTimer: SleepTimer
    private val log by lazy { ListenLog(engine.stats) { novel -> engine.novels.known(novel)?.latest } }
    private var book: Book? = null
    private var request: Request? = null
    private var lang = "en-us"
    // Which part the playlist's first item is, and the one playing (read by the maker's thread).
    private var first = 1
    @Volatile private var at = 0
    private var allMade = false
    private var pausedAt = 0L

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        sleepTimer = SleepTimer(player, scope)
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) = arrived()
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && allMade) finished()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                backALittle(isPlaying)
                Listening.now?.let { log.playing(isPlaying, it, at) }
                ListeningWidget.playingNow = isPlaying
                ListeningWidget.refresh(this@ListenService)
            }
        })
        session = MediaSession.Builder(this, player)
            .setSessionActivity(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            .setCallback(object : MediaSession.Callback {
                // Only the app itself may open a book or change it; the system's controls get the usual commands.
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo) =
                    if (controller.packageName != packageName) super.onConnect(session, controller)
                    else MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                        .setAvailableSessionCommands(
                            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().apply {
                                for (c in COMMANDS) add(SessionCommand(c, Bundle.EMPTY))
                            }.build(),
                        )
                        .build()

                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                                             command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    when (command.customAction) {
                        OPEN -> open(Request.from(args))
                        CHAPTER -> request?.let { open(it.copy(source = args.getString("url")!!)) }
                        STOP -> close()
                        JUMP -> jump(args.getInt("part"))
                        SPEED -> speed(args.getFloat("speed"))
                        VOICE -> voice(args.getString("voice")!!)
                        REMAKE -> remake()
                        SLEEP -> sleep(when (args.getString("kind")) {
                            "minutes" -> Sleep.Minutes(args.getLong("ms"))
                            "chapter" -> Sleep.EndOfChapter
                            else -> null
                        })
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    /** Listen to [request]'s book from its bookmark (or the start). */
    private fun open(request: Request) {
        close()
        Listening.error = ""
        Listening.opening = true
        Listening.made.clear()
        Listening.unpaced()
        reading = read(request) { b, lang ->
            engine.upNext.remove(request.id)       // (it isn't waiting any more)
            // A novel's chapter is the novel's: its bookmark, if it's in this chapter (or heard to the end of it).
            val r = b.novel?.let { n -> request.copy(id = Books.id(n.url), kind = "novel", source = b.chapters.first().url) } ?: request
            val mark = engine.bookmarks.get(r.id)?.takeIf { b.novel == null || it.source == r.source }
            val start = if (mark?.done == true && mark.part >= mark.parts) b.parts.size + 1
            else Bookmarks.startAt(mark?.part ?: 1, mark?.parts ?: 0, b.parts.size)
            begin(r, b, lang, start)
        }
    }

    /** Read [request]'s book, then [then] with it and the language it's read in. */
    private fun read(request: Request, then: (Book, String) -> Unit): Job = scope.launch {
        val engine = engine
        val lang = Books.langOf(request.voice)
        val b = try {
            withContext(Dispatchers.IO) {
                engine.textSide.read(request.source, request.name, lang).also { Maker.trim(engine.cache, request.id, CACHE_LIMIT) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@launch failed(e.plain())
        }
        then(b, lang)
    }

    private fun begin(r: Request, b: Book, lang: String, start: Int) {
        request = r
        book = b
        this.lang = lang
        Listening.now = Listening.Now(r, b, start, lang)
        Listening.madeWith = engine.tuning()
        Listening.opening = false
        startMaking(start, keepPlaying = false)
        player.playWhenReady = true
        // The next few chapters, fetched so they can be heard offline.
        b.novel?.let { b.chapters.lastOrNull() }?.let { c ->
            scope.launch(Dispatchers.IO) { runCatching { engine.novels.ahead(engine.novels.chapter(c.url)!!.first) } }
        }
    }

    /**
     * Make the parts from [from] on, in the request's voice and speed, onto the
     * playlist. [keepPlaying]: the part playing stays (a new speed); otherwise
     * the playlist starts again at [from].
     */
    private fun startMaking(from: Int, keepPlaying: Boolean) {
        if (request == null || book == null) return
        making?.cancel()
        allMade = false
        if (keepPlaying && player.mediaItemCount > 0) {
            val keep = player.currentMediaItemIndex + 1
            if (player.mediaItemCount > keep) player.removeMediaItems(keep, player.mediaItemCount)
        } else {
            player.clearMediaItems()
            first = from
            at = 0
        }
        making = scope.launch { make(from) }
    }

    /**
     * The parts from [start] to the end of the book, then, for a novel, chapter after chapter
     * added on the end and made, for as long as there's a next one.
     */
    private suspend fun make(start: Int) {
        var from = start
        while (true) {
            val r = request ?: return
            val b = book ?: return
            if (from <= b.parts.size) {
                Listening.making = true
                if (!makeParts(r, b, from)) return
                allMade = true
                Listening.making = false
                if (b.novel == null) {
                    if (player.playbackState == Player.STATE_ENDED) finished()
                    return
                }
            } else {
                allMade = true
                if (b.novel == null) return
            }
            if (!nextChapter(b)) return
            from = b.parts.size + 1
        }
    }

    /** Say [b]'s parts from [from] on in [r]'s voice, each onto the playlist as it's made; false if it couldn't be done. */
    private suspend fun makeParts(r: Request, b: Book, from: Int): Boolean {
        val engine = engine
        val lang = lang
        val madeAt = madeAt(r)
        val cache = Maker.cacheDir(engine.cache, r.id, r.voice, madeAt, lang)
        try {
            // (what's found out on the way is shown from the main thread, in order, and not at all once cancelled)
            withContext(Dispatchers.IO) {
                val speaker = engine.speaker(r.voice, r.speed, lang) { graph, of ->
                    launch(Dispatchers.Main) { Listening.preparing = "Getting Ming ready for this phone · $graph of $of" }
                }
                launch(Dispatchers.Main) { Listening.preparing = "" }
                Maker(b, cache, speaker).run(from, { at }, { !isActive }) { made ->
                    launch(Dispatchers.Main) { queue(b, made, madeAt) }
                }
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failed(e.plain())
            return false
        }
    }

    /**
     * A novel's next chapter, added on the end of [b]: kept ahead already, or fetched now. False
     * when there's none to make: the novel is heard up to date, or the phone is offline.
     */
    private suspend fun nextChapter(b: Book): Boolean {
        val next = b.novel?.next
        if (next == null) {
            upToDate()
            return false
        }
        val engine = engine
        val lang = lang
        allMade = false
        Listening.making = true
        val got = try {
            withContext(Dispatchers.IO) {
                engine.novels.chapter(next)?.let { (chapter, text) -> chapter to engine.exclusive { engine.text.parts(text, lang) } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Offline with nothing kept ahead: what's made plays out, and the next chapter is tried again then.
            allMade = true
            Listening.making = false
            Listening.error = "Couldn't get the next chapter (${e.plain()})"
            if (player.playbackState == Player.STATE_ENDED) finished()
            return false
        }
        if (got == null || got.second.isEmpty()) {
            book = b.copy(novel = b.novel?.copy(next = null))
            upToDate()
            return false
        }
        val longer = b.plus(got.first, got.second)
        book = longer
        Listening.now = Listening.now?.let { Listening.Now(it.request, longer, it.first, it.lang) }
        scope.launch(Dispatchers.IO) { runCatching { engine.novels.ahead(got.first) } }
        return true
    }

    /** Heard to the latest chapter: if it's all played, the novel stays on the shelf, done, for what comes next. */
    private fun upToDate() {
        allMade = true
        Listening.making = false
        if (player.playbackState == Player.STATE_ENDED || player.mediaItemCount == 0) finished()
    }

    /** The speed [r]'s parts are made at: Kokoro's own, 1× for the others (the player speeds them up). */
    private fun madeAt(r: Request) = if (VoiceId.makesAtSpeed(r.voice)) r.speed else 1f

    /** Append a made part to the playlist (made at [speed]), and start playing with the first. */
    private fun queue(b: Book, made: Maker.Made, speed: Float) {
        Listening.made += made
        Listening.paced(b.parts[made.index - 1].text.length, made.seconds * speed)
        val chapter = b.chapterOf(made.index)?.title?.takeIf { b.chapters.size > 1 }
        player.addMediaItem(
            MediaItem.Builder()
                .setUri(Uri.fromFile(made.wav))
                .setMediaId("${made.index}@$speed")
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(b.title)
                        .setArtist(chapter ?: "Part ${made.index} of ${b.parts.size}").build(),
                )
                .build(),
        )
        when {
            player.mediaItemCount == 1 -> player.prepare()
            player.playbackState == Player.STATE_ENDED -> { player.seekTo(player.mediaItemCount - 1, 0); player.play() }
        }
    }

    /** A new part is playing: the bookmark moves to it, the maker hears where the player is, and the speed is set for it. */
    private fun arrived() {
        val now = Listening.now ?: return
        val item = player.currentMediaItem ?: return
        val part = item.mediaId.substringBefore('@').toIntOrNull() ?: return
        val madeAt = item.mediaId.substringAfter('@').toFloatOrNull() ?: 1f
        val r = request ?: return
        player.playbackParameters = PlaybackParameters(r.speed / madeAt)
        log.arrived(now.book, at, part)
        at = part
        Listening.at = part
        mark(now.book, r, part, now.lang)
        ListeningWidget.refresh(this)
        if (Listening.sleep == Sleep.EndOfChapter) sleepTimer.chapterEnd(now.book, part)
    }

    /** The bookmark at [part]: a book's, or a novel's (its chapter, and the part in it). */
    private fun mark(b: Book, r: Request, part: Int, lang: String, done: Boolean = false) {
        val n = b.novel
        val c = b.chapterOf(part)
        if (n != null && c != null) {
            val parts = b.partsOf(c)
            engine.bookmarks.save(Bookmarks.Mark(r.id, part - c.first + 1, parts.count(), n.title, "novel", c.url, r.voice,
                r.speed, lang, chapter = c.title, number = c.number, novel = n.url, done = done))
        } else if (b.parts.size > 1) {
            engine.bookmarks.save(Bookmarks.Mark(r.id, part, b.parts.size, b.title, r.kind, r.source, r.voice, r.speed, lang))
        }
    }

    /** Go to [part]: at once if it's on the playlist, otherwise making starts there. */
    private fun jump(part: Int) {
        val b = book ?: return
        if (part !in 1..b.parts.size) return
        val index = (0 until player.mediaItemCount).firstOrNull {
            player.getMediaItemAt(it).mediaId.substringBefore('@') == "$part"
        }
        if (index != null) player.seekTo(index, 0) else startMaking(part, keepPlaying = false)
        player.play()
    }

    /**
     * A new speed, heard at once: this part sped up (pitch kept), and for Kokoro the rest made
     * again at it (the other voices are made at 1× and only played faster).
     */
    private fun speed(speed: Float) {
        val r = request ?: return
        if (speed == r.speed || speed !in 0.5f..2f) return
        request = r.copy(speed = speed)
        Listening.now = Listening.now?.let { Listening.Now(request!!, it.book, it.first, it.lang) }
        val madeAt = player.currentMediaItem?.mediaId?.substringAfter('@')?.toFloatOrNull() ?: madeAt(r)
        player.playbackParameters = PlaybackParameters(speed / madeAt)
        // (before the first part plays, everything is made again from where it starts)
        if (VoiceId.makesAtSpeed(r.voice)) startMaking(if (at > 0) at + 1 else first, keepPlaying = at > 0)
        arrivedBookmark()
    }

    /**
     * How parts are said has changed (a pronunciation of yours): the part playing carries on, and
     * the rest are made as they're said now (those said the same are kept as they are).
     */
    private fun remake() {
        if (request != null) startMaking(if (at > 0) at + 1 else first, keepPlaying = at > 0)
    }

    /** A new voice: the part you're on starts again in it (read again if its language differs). */
    private fun voice(voice: String) {
        val r = request ?: return
        if (voice == r.voice) return
        val next = r.copy(voice = voice)
        val from = at.coerceAtLeast(1)
        val chapter = book?.takeIf { it.novel != null }?.chapterOf(from)
        Listening.unpaced()
        if (Books.langOf(voice) == lang) {
            Listening.preparing = ""
            request = next
            Listening.now = Listening.now?.let { Listening.Now(next, it.book, it.first, it.lang) }
            startMaking(from, keepPlaying = false)
            player.play()
            arrivedBookmark()
        } else {
            reading?.cancel()
            making?.cancel()
            player.stop()
            Listening.opening = true
            // (a novel starts again at the chapter it's in)
            val again = if (chapter != null) next.copy(source = chapter.url) else next
            val inChapter = if (chapter != null) from - chapter.first + 1 else from
            reading = read(again) { b, lang ->
                val r = b.novel?.let { n -> again.copy(id = Books.id(n.url), kind = "novel") } ?: again
                begin(r, b, lang, if (chapter != null) inChapter.coerceIn(1, b.parts.size)
                else Bookmarks.startAt(from, book?.parts?.size ?: 0, b.parts.size))
            }
        }
    }

    private fun arrivedBookmark() {
        if (at > 0) arrived()
    }

    /** Set the sleep timer, or turn it off. */
    private fun sleep(s: Sleep?) {
        sleepTimer.set(s)
        if (s == Sleep.EndOfChapter) book?.let { sleepTimer.chapterEnd(it, at) }
    }

    /** Carrying on after a minute or more away goes back 5 seconds, or 10 after an hour. */
    private fun backALittle(playing: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!playing) {
            if (player.playbackState == Player.STATE_READY) pausedAt = now
            return
        }
        val away = if (pausedAt > 0) now - pausedAt else 0
        pausedAt = 0
        if (away >= 60_000) player.seekTo((player.currentPosition - if (away >= 3_600_000) 10_000 else 5_000).coerceAtLeast(0))
    }

    // ------------------------------------------------------------ the end

    /**
     * Heard to the end: the bookmark, the kept parts and the book's own copy go, as in Termux. A
     * novel's bookmark stays, done, at the end of the latest chapter, for the chapters still to come.
     */
    private fun finished() {
        val now = Listening.now ?: return
        val id = now.request.id
        val sleep = Listening.sleep as? Sleep.Minutes
        making?.cancel()
        log.finished(now.book, at)
        val b = book
        if (b?.novel != null) {
            mark(b, now.request, b.parts.size, now.lang, done = true)
            b.chapters.lastOrNull()?.let { Listening.note = "Up to date: ${it.title} is the latest" }
        } else {
            engine.bookmarks.remove(id)
            if (now.request.kind != "url") File(now.request.source).delete()
        }
        File(engine.cache, id).deleteRecursively()
        Listening.now = null
        Listening.at = 0
        Listening.sleep = null
        player.clearMediaItems()
        book = null
        request = null
        playNext(sleep)
    }

    /**
     * What's first in Up next, if anything is waiting, in the voice and at the speed of the
     * settings; a sleep timer counting minutes ([sleep]) carries on through it.
     */
    private fun playNext(sleep: Sleep.Minutes?) {
        val next = engine.upNext.all().firstOrNull() ?: return
        val prefs = Store(this).load()
        open(Request(next.id, next.kind, next.source, next.title, prefs.voice, prefs.speed))
        if (sleep != null) sleepTimer.set(sleep)
    }

    private fun failed(message: String) {
        Listening.error = message
        Listening.preparing = ""
        Listening.opening = false
        Listening.making = false
    }

    /** Stop listening, keeping the bookmark (a book still opening is let go too). */
    private fun close() {
        ListeningWidget.playingNow = false
        ListeningWidget.refresh(this)
        log.stopped(book ?: Listening.now?.book, at)
        reading?.cancel()
        making?.cancel()
        Listening.opening = false
        player.stop()
        player.clearMediaItems()
        sleepTimer.set(null)
        Listening.now = null
        Listening.making = false
        Listening.at = 0
        book = null
        request = null
        at = 0
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        player.release()
        super.onDestroy()
    }

    companion object {
        const val OPEN = "kokomux.OPEN"
        const val STOP = "kokomux.STOP"
        const val JUMP = "kokomux.JUMP"
        const val SPEED = "kokomux.SPEED"
        const val VOICE = "kokomux.VOICE"
        const val SLEEP = "kokomux.SLEEP"
        /** A novel's chapter that isn't in this listen: the novel starts again there. */
        const val CHAPTER = "kokomux.CHAPTER"
        /** How parts are said has changed: those not yet heard are made again. */
        const val REMAKE = "kokomux.REMAKE"
        private val COMMANDS = listOf(OPEN, STOP, JUMP, SPEED, VOICE, SLEEP, CHAPTER, REMAKE)
        /** KOKORO_CACHE_GB's default: 50 GB of kept parts at most. */
        const val CACHE_LIMIT = 50L * 1000 * 1000 * 1000
    }
}
