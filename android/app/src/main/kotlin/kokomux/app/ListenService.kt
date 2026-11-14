package kokomux.app

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.atomic.AtomicBoolean
import kokomux.app.ui.Sleep
import kokomux.engine.Book
import kokomux.engine.Bookmarks
import kokomux.engine.Books
import kokomux.engine.Maker
import kokomux.engine.ReadError
import kokomux.engine.Session
import kokomux.engine.Sessions
import kokomux.engine.VoiceId

/**
 * Listening, in the background: one ExoPlayer plays the whole book as a
 * gapless playlist, with the notification and lock-screen controls, while a
 * thread makes the parts ([Maker]) about ten minutes ahead of it and appends
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
 *
 * The screen connects a MediaController and sends the commands below.
 */
class ListenService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private val main = Handler(Looper.getMainLooper())
    private var stop = AtomicBoolean(true)
    private var runs = 0
    private var book: Book? = null
    private var request: Request? = null
    private var lang = "en-us"
    // Which part the playlist's first item is, and the one playing (read by the maker's thread).
    private var first = 1
    @Volatile private var at = 0
    private var allMade = false
    private var pausedAt = 0L
    private var sleepTicked = 0L

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
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) = arrived()
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && allMade) finished()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                backALittle(isPlaying)
                track(isPlaying)
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
        val run = runs
        Listening.error = ""
        Listening.opening = true
        Listening.made.clear()
        Listening.unpaced()
        read(request, run) { b, lang ->
            // A novel's chapter is the novel's: its bookmark, if it's in this chapter (or heard to the end of it).
            val r = b.novel?.let { n -> request.copy(id = Books.id(n.url), kind = "novel", source = b.chapters.first().url) } ?: request
            val mark = engine.bookmarks.get(r.id)?.takeIf { b.novel == null || it.source == r.source }
            val start = if (mark?.done == true && mark.part >= mark.parts) b.parts.size + 1
            else Bookmarks.startAt(mark?.part ?: 1, mark?.parts ?: 0, b.parts.size)
            begin(r, b, lang, start)
        }
    }

    /** Read [request]'s book on a thread (Kokoro too, if it isn't loaded), then [then] on the main thread. */
    private fun read(request: Request, run: Int, then: (Book, String) -> Unit) {
        val engine = engine
        Thread({
            try {
                val lang = Books.langOf(request.voice)
                val b = engine.textSide.read(request.source, request.name, lang)
                Maker.trim(engine.cache, request.id, CACHE_LIMIT)
                main.post { if (run == runs) then(b, lang) }
            } catch (e: ReadError) {
                main.post { if (run == runs) failed(e.message.orEmpty()) }
            } catch (e: Throwable) {
                main.post { if (run == runs) failed(e.message ?: e.toString()) }
            }
        }, "kokomux-reading").start()
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
        b.novel?.let { b.chapters.lastOrNull() }?.let { c -> Thread({ runCatching { engine.novels.ahead(engine.novels.chapter(c.url)!!.first) } }, "kokomux-ahead").start() }
    }

    /**
     * Make the parts from [from] on, in the request's voice and speed, onto the
     * playlist. [keepPlaying]: the part playing stays (a new speed); otherwise
     * the playlist starts again at [from].
     */
    private fun startMaking(from: Int, keepPlaying: Boolean, append: Boolean = false) {
        val r = request ?: return
        val b = book ?: return
        stop.set(true)
        val run = ++runs
        val stop = AtomicBoolean(false).also { this.stop = it }
        allMade = false
        if (append) {
            // (a novel's next chapter: what's on the playlist stays)
        } else if (keepPlaying && player.mediaItemCount > 0) {
            val keep = player.currentMediaItemIndex + 1
            if (player.mediaItemCount > keep) player.removeMediaItems(keep, player.mediaItemCount)
        } else {
            player.clearMediaItems()
            first = from
            at = 0
        }
        if (from > b.parts.size) {
            allMade = true
            if (b.novel != null) extend(run)
            return
        }
        Listening.making = true
        val engine = engine
        val madeAt = madeAt(r)
        val cache = Maker.cacheDir(engine.cache, r.id, r.voice, madeAt, lang)
        val lang = lang
        Thread({
            try {
                val speaker = engine.speaker(r.voice, r.speed, lang) { graph, of ->
                    main.post { if (run == runs) Listening.preparing = "Getting Ming ready for this phone · $graph of $of" }
                }
                main.post { if (run == runs) Listening.preparing = "" }
                val maker = Maker(b, cache, speaker)
                maker.run(from, { at }, { stop.get() }) { made -> main.post { if (run == runs) queue(b, made, madeAt) } }
                if (!stop.get()) main.post {
                    if (run != runs) return@post
                    allMade = true
                    Listening.making = false
                    if (b.novel != null) extend(run)
                    else if (player.playbackState == Player.STATE_ENDED) finished()
                }
            } catch (e: Throwable) {
                main.post { if (run == runs) failed(e.message ?: e.toString()) }
            }
        }, "kokomux-making").start()
    }

    /**
     * A novel's next chapter, added on the end and made on from there: kept ahead already, or
     * fetched now. When there's none out yet, the novel is heard up to date.
     */
    private fun extend(run: Int) {
        val b = book ?: return
        val next = b.novel?.next ?: return upToDate()
        val lang = lang
        allMade = false
        Listening.making = true
        Thread({
            try {
                val got = engine.novels.chapter(next)
                val parts = got?.let { engine.exclusive { engine.text.parts(it.second, lang) } }
                main.post {
                    if (run != runs || book !== b) return@post
                    if (got == null || parts.isNullOrEmpty()) {
                        book = b.copy(novel = b.novel?.copy(next = null))
                        upToDate()
                        return@post
                    }
                    val longer = b.plus(got.first, parts)
                    book = longer
                    Listening.now = Listening.now?.let { Listening.Now(it.request, longer, it.first, it.lang) }
                    startMaking(b.parts.size + 1, keepPlaying = true, append = true)
                }
                if (got != null) engine.novels.ahead(got.first)
            } catch (e: Throwable) {
                // Offline with nothing kept ahead: what's made plays out, and the next chapter is tried again then.
                main.post {
                    if (run != runs) return@post
                    allMade = true
                    Listening.making = false
                    Listening.error = "Couldn't get the next chapter (${e.message})"
                    if (player.playbackState == Player.STATE_ENDED) finished()
                }
            }
        }, "kokomux-next-chapter").start()
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
        heard(now.book, part)
        at = part
        Listening.at = part
        mark(now.book, r, part, now.lang)
        ListeningWidget.refresh(this)
        if (Listening.sleep == Sleep.EndOfChapter) chapterEnd()
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
            val run = ++runs
            stop.set(true)
            player.stop()
            Listening.opening = true
            // (a novel starts again at the chapter it's in)
            val again = if (chapter != null) next.copy(source = chapter.url) else next
            val inChapter = if (chapter != null) from - chapter.first + 1 else from
            read(again, run) { b, lang ->
                val r = b.novel?.let { n -> again.copy(id = Books.id(n.url), kind = "novel") } ?: again
                begin(r, b, lang, if (chapter != null) inChapter.coerceIn(1, b.parts.size)
                else Bookmarks.startAt(from, book?.parts?.size ?: 0, b.parts.size))
            }
        }
    }

    private fun arrivedBookmark() {
        if (at > 0) arrived()
    }

    // ------------------------------------------------------------ listens, for the stats

    private val sessions by lazy { Sessions(engine.stats) }
    private var listen: Session? = null
    /** When playing started (elapsed ms), 0 while paused. */
    private var playingSince = 0L
    /** Words in each part from it to the end of the book (worked out once a book). */
    private var wordsFrom: Pair<Book?, IntArray> = null to IntArray(0)

    private fun wordsFrom(b: Book, part: Int): Int {
        if (wordsFrom.first !== b) {
            val from = IntArray(b.parts.size + 2)
            for (i in b.parts.indices.reversed()) from[i + 1] = from[i + 2] + b.parts[i].text.split(Regex("\\s+")).count { it.isNotEmpty() }
            wordsFrom = b to from
        }
        return wordsFrom.second.getOrElse(part.coerceAtLeast(1)) { 0 }
    }

    /** Where [part] is, in words: its chapter, or "Part 4 of 12". */
    private fun where(b: Book, part: Int) =
        b.chapterOf(part)?.title?.takeIf { b.chapters.size > 1 || b.novel != null } ?: "Part $part of ${b.parts.size}"

    /** A novel's chapters out after the one [part] is in (-1: not a novel). */
    private fun chaptersLeft(b: Book, part: Int): Int {
        val n = b.novel ?: return -1
        val number = b.chapterOf(part)?.number ?: return -1
        return ((engine.novels.known(n.url)?.latest ?: number) - number).coerceAtLeast(0)
    }

    /** Playing started or stopped: a listen begins (or carries on, after a short pause), or is kept as it stands. */
    private fun track(playing: Boolean) {
        val now = Listening.now ?: return
        val b = now.book
        val time = System.currentTimeMillis()
        if (playing) {
            val l = listen
            if (l == null || l.book != now.request.id || time - l.end > Sessions.JOIN) {
                val part = at.coerceAtLeast(now.first)
                listen = Session(time, now.request.id, b.novel?.title ?: b.title, time, time, 0.0, where(b, part), where(b, part),
                    0, 0, wordsFrom(b, part), chaptersLeft(b, part))
            }
            playingSince = SystemClock.elapsedRealtime()
        } else {
            keepListen()
            playingSince = 0
        }
    }

    /** Arriving at [part]: the part before it was heard (and its chapter, if [part] starts a new one). */
    private fun heard(b: Book, part: Int) {
        val l = listen ?: return
        if (part != at + 1 || at < 1) return keepListen()
        val chapter = if (b.chapterOf(part) != b.chapterOf(at)) 1 else 0
        listen = l.copy(words = l.words + wordsFrom(b, at) - wordsFrom(b, part), chapters = l.chapters + chapter)
        keepListen(part)
    }

    /** Keep the listen as it stands (time played so far; at [part]). */
    private fun keepListen(part: Int = at) {
        val l = listen ?: return
        val b = book ?: Listening.now?.book ?: return
        val played = if (playingSince > 0) (SystemClock.elapsedRealtime() - playingSince) / 1000.0 else 0.0
        if (playingSince > 0) playingSince = SystemClock.elapsedRealtime()
        val p = part.coerceAtLeast(1)
        listen = l.copy(end = System.currentTimeMillis(), seconds = l.seconds + played, to = where(b, p),
            left = wordsFrom(b, p), chaptersLeft = chaptersLeft(b, p))
        if (listen!!.seconds >= 1) sessions.save(listen!!)
    }

    // ------------------------------------------------------------ the sleep timer

    /** Set the sleep timer, or turn it off. */
    private fun sleep(s: Sleep?) {
        Listening.sleep = s
        player.volume = 1f
        player.pauseAtEndOfMediaItems = false
        sleepTicked = SystemClock.elapsedRealtime()
        main.removeCallbacks(sleepTick)
        if (s != null) main.post(sleepTick)
        if (s == Sleep.EndOfChapter) chapterEnd()
    }

    /** Each second: count down the time spent playing, and fade over the last 13 seconds. */
    private val sleepTick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val spent = if (player.isPlaying) now - sleepTicked else 0L
            sleepTicked = now
            when (val s = Listening.sleep) {
                is Sleep.Minutes -> {
                    val left = s.leftMs - spent
                    player.volume = (left / FADE_MS.toFloat()).coerceIn(0f, 1f)
                    if (left <= 0) return asleep()
                    Listening.sleep = Sleep.Minutes(left)
                }
                Sleep.EndOfChapter -> {
                    if (player.pauseAtEndOfMediaItems && player.duration > 0) {
                        val left = player.duration - player.currentPosition
                        player.volume = (left / FADE_MS.toFloat()).coerceIn(0f, 1f)
                        if (!player.isPlaying && left <= 250) {           // paused at the chapter's end
                            if (player.hasNextMediaItem()) player.seekTo(player.currentMediaItemIndex + 1, 0)
                            return asleep()
                        }
                    }
                }
                null -> return
            }
            main.postDelayed(this, 250)
        }
    }

    /** On the chapter's last part, the player stops at its end. */
    private fun chapterEnd() {
        val b = book ?: return
        val next = b.chapters.firstOrNull { it.first > at }?.first ?: (b.parts.size + 1)
        player.pauseAtEndOfMediaItems = at == next - 1
    }

    /** The sleep timer's end: stop, keeping the place, at full volume for next time. */
    private fun asleep() {
        player.pause()
        player.volume = 1f
        player.pauseAtEndOfMediaItems = false
        Listening.sleep = null
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
        stop.set(true)
        listen?.let { l -> listen = l.copy(words = l.words + wordsFrom(now.book, at), chapters = l.chapters + 1) }
        keepListen(now.book.parts.size + 1)
        listen = null
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
        keepListen()
        listen = null
        playingSince = 0
        stop.set(true)
        runs++
        Listening.opening = false
        player.stop()
        player.clearMediaItems()
        player.volume = 1f
        player.pauseAtEndOfMediaItems = false
        main.removeCallbacks(sleepTick)
        Listening.sleep = null
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
        stop.set(true)
        main.removeCallbacks(sleepTick)
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
        private val COMMANDS = listOf(OPEN, STOP, JUMP, SPEED, VOICE, SLEEP, CHAPTER)
        /** KOKORO_CACHE_GB's default: 50 GB of kept parts at most. */
        const val CACHE_LIMIT = 50L * 1000 * 1000 * 1000
        /** The sleep timer's fade, as Termux's. */
        const val FADE_MS = 13_000L
    }
}
