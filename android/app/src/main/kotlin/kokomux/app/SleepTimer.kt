package kokomux.app

import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import kokomux.app.ui.Sleep
import kokomux.engine.Book
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The sleep timer: so many minutes of playing, or the end of the chapter.
 * It fades the voice out over its last 13 seconds, counts only time spent
 * playing, and stops keeping the place, at full volume for next time. What's
 * left is in [Listening.sleep], for the screen. Runs on the main thread, in
 * the listening service's [scope].
 */
class SleepTimer(private val player: ExoPlayer, private val scope: CoroutineScope) {
    private var ticking: Job? = null
    private var ticked = 0L

    /** Set the timer, or turn it off (null). */
    fun set(s: Sleep?) {
        Listening.sleep = s
        player.volume = 1f
        player.pauseAtEndOfMediaItems = false
        ticked = SystemClock.elapsedRealtime()
        ticking?.cancel()
        ticking = if (s == null) null else scope.launch {
            while (tick()) delay(250)
        }
    }

    /** Playing part [at] of [book]: on its chapter's last part, the player stops at the part's end. */
    fun chapterEnd(book: Book, at: Int) {
        val next = book.chapters.firstOrNull { it.first > at }?.first ?: (book.parts.size + 1)
        player.pauseAtEndOfMediaItems = at == next - 1
    }

    /** Count down the time spent playing, and fade over the last 13 seconds; false once the timer is over. */
    private fun tick(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val spent = if (player.isPlaying) now - ticked else 0L
        ticked = now
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
            null -> return false
        }
        return true
    }

    /** The timer's end: stop, keeping the place, at full volume for next time. */
    private fun asleep(): Boolean {
        player.pause()
        player.volume = 1f
        player.pauseAtEndOfMediaItems = false
        Listening.sleep = null
        return false
    }

    companion object {
        /** The fade, as Termux's. */
        const val FADE_MS = 13_000L
    }
}
