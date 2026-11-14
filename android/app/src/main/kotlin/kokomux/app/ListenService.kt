package kokomux.app

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kokomux.engine.Book
import kokomux.engine.Bookmarks
import kokomux.engine.Books
import kokomux.engine.Maker
import kokomux.engine.ReadError
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listening, in the background: one ExoPlayer plays the whole book as a
 * gapless playlist, with the notification and lock-screen controls, while a
 * thread makes the parts ([Maker]) about ten minutes ahead of it and appends
 * each as it's ready. The bookmark follows the part playing; a book heard to
 * the end loses its bookmark and its kept parts, as in Termux.
 *
 * The screen connects a MediaController and sends [OPEN] with a [Request].
 */
class ListenService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private val main = Handler(Looper.getMainLooper())
    private var stop = AtomicBoolean(true)
    private var runs = 0
    // Which part the playlist's first item is, and the one playing (read by the maker's thread).
    private var first = 1
    @Volatile private var at = 0
    private var allMade = false

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
        })
        session = MediaSession.Builder(this, player)
            .setSessionActivity(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            .setCallback(object : MediaSession.Callback {
                // Only the app itself may open a book or stop; the system's controls get the usual commands.
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo) =
                    if (controller.packageName != packageName) super.onConnect(session, controller)
                    else MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                        .setAvailableSessionCommands(
                            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                                .add(SessionCommand(OPEN, Bundle.EMPTY)).add(SessionCommand(STOP, Bundle.EMPTY)).build(),
                        )
                        .build()

                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                                             command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    when (command.customAction) {
                        OPEN -> open(Request.from(args))
                        STOP -> close()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    /** Listen to [request]'s book from its bookmark (or the start), in its voice. */
    private fun open(request: Request) {
        close()
        val run = ++runs
        val stop = AtomicBoolean(false).also { this.stop = it }
        val engine = engine
        Listening.error = ""
        Listening.opening = true
        Listening.made.clear()
        Thread({
            try {
                val lang = Books.langOf(request.voice)
                val kokoro = engine.kokoro()
                val book = engine.textSide.read(request.source, request.name, lang)
                val mark = engine.bookmarks.get(request.id)
                val start = Bookmarks.startAt(mark?.part ?: 1, mark?.parts ?: 0, book.parts.size)
                Maker.trim(engine.cache, request.id, CACHE_LIMIT)
                val cache = Maker.cacheDir(engine.cache, request.id, request.voice, request.speed, lang)
                main.post {
                    if (run != runs) return@post
                    first = start
                    at = 0
                    allMade = false
                    Listening.now = Listening.Now(request, book, start, lang)
                    Listening.madeWith = engine.tuning()
                    Listening.opening = false
                    Listening.making = true
                }
                val maker = Maker(book, cache, engine.textSide, lang, { sounds ->
                    engine.exclusive { kokoro.speak(sounds, request.voice, request.speed) }
                })
                maker.run(start, { at }, { stop.get() }) { made -> main.post { if (run == runs) queue(book, made) } }
                if (!stop.get()) main.post {
                    if (run != runs) return@post
                    allMade = true
                    Listening.making = false
                    if (player.playbackState == Player.STATE_ENDED) finished()
                }
            } catch (e: ReadError) {
                main.post { if (run == runs) failed(e.message.orEmpty()) }
            } catch (e: Throwable) {
                main.post { if (run == runs) failed(e.message ?: e.toString()) }
            }
        }, "kokomux-making").start()
    }

    /** Append a made part to the playlist, and start playing with the first. */
    private fun queue(book: Book, made: Maker.Made) {
        Listening.made += made
        val chapter = book.chapterOf(made.index)?.title?.takeIf { book.chapters.size > 1 }
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(made.wav))
            .setMediaId("${made.index}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(book.title)
                    .setArtist(chapter ?: "Part ${made.index} of ${book.parts.size}")
                    .build(),
            )
            .build()
        player.addMediaItem(item)
        when {
            player.mediaItemCount == 1 -> { player.prepare(); player.play() }
            player.playbackState == Player.STATE_ENDED -> { player.seekTo(player.mediaItemCount - 1, 0); player.play() }
        }
    }

    /** A new part is playing: the bookmark moves to it, and the maker hears where the player is. */
    private fun arrived() {
        val now = Listening.now ?: return
        if (player.mediaItemCount == 0) return
        val part = first + player.currentMediaItemIndex
        at = part
        Listening.at = part
        val r = now.request
        if (now.book.parts.size > 1) engine.bookmarks.save(
            Bookmarks.Mark(r.id, part, now.book.parts.size, now.book.title, r.kind, r.source, r.voice, r.speed, now.lang),
        )
    }

    /** Heard to the end: the bookmark, the kept parts and the book's own copy go, as in Termux. */
    private fun finished() {
        val now = Listening.now ?: return
        val id = now.request.id
        stop.set(true)
        engine.bookmarks.remove(id)
        File(engine.cache, id).deleteRecursively()
        if (now.request.kind != "url") File(now.request.source).delete()
        Listening.now = null
        Listening.at = 0
        player.clearMediaItems()
    }

    private fun failed(message: String) {
        Listening.error = message
        Listening.opening = false
        Listening.making = false
    }

    /** Stop listening, keeping the bookmark. */
    private fun close() {
        stop.set(true)
        player.stop()
        player.clearMediaItems()
        Listening.now = null
        Listening.making = false
        Listening.at = 0
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        stop.set(true)
        session.release()
        player.release()
        super.onDestroy()
    }

    companion object {
        const val OPEN = "kokomux.OPEN"
        const val STOP = "kokomux.STOP"
        /** KOKORO_CACHE_GB's default: 50 GB of kept parts at most. */
        const val CACHE_LIMIT = 50L * 1000 * 1000 * 1000
    }
}
