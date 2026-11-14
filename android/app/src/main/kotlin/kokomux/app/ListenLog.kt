package kokomux.app

import android.os.SystemClock
import kokomux.engine.Book
import kokomux.engine.Session
import kokomux.engine.Sessions
import java.io.File

/**
 * The listens, kept for the stats: each one from play to a pause of 3 minutes
 * or more, with how long it played, from where to where, and the words and
 * chapters heard. The listening service says what happens (playing starts or
 * stops, a part is arrived at, the book ends); the listen is saved as it
 * stands each time. [chaptersOut] is the latest chapter known of a novel
 * (its address), or null.
 */
class ListenLog(file: File, private val chaptersOut: (String) -> Int?) {
    private val sessions = Sessions(file)
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
        return ((chaptersOut(n.url) ?: number) - number).coerceAtLeast(0)
    }

    /**
     * Playing started or stopped, at part [at] of [now]'s book: a listen begins (or carries on,
     * after a short pause), or is kept as it stands.
     */
    fun playing(playing: Boolean, now: Listening.Now, at: Int) {
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
            keep(b, at)
            playingSince = 0
        }
    }

    /** Arriving at [part] from [at]: the part before it was heard (and its chapter, if [part] starts a new one). */
    fun arrived(b: Book, at: Int, part: Int) {
        val l = listen ?: return
        if (part != at + 1 || at < 1) return keep(b, at)
        val chapter = if (b.chapterOf(part) != b.chapterOf(at)) 1 else 0
        listen = l.copy(words = l.words + wordsFrom(b, at) - wordsFrom(b, part), chapters = l.chapters + chapter)
        keep(b, part)
    }

    /** Keep the listen as it stands: the time played so far, at [part] of [b]. */
    private fun keep(b: Book, part: Int) {
        val l = listen ?: return
        val played = if (playingSince > 0) (SystemClock.elapsedRealtime() - playingSince) / 1000.0 else 0.0
        if (playingSince > 0) playingSince = SystemClock.elapsedRealtime()
        val p = part.coerceAtLeast(1)
        val kept = l.copy(end = System.currentTimeMillis(), seconds = l.seconds + played, to = where(b, p),
            left = wordsFrom(b, p), chaptersLeft = chaptersLeft(b, p))
        listen = kept
        if (kept.seconds >= 1) sessions.save(kept)
    }

    /** [b] was heard to its end, from part [at]: the listen is over. */
    fun finished(b: Book, at: Int) {
        listen?.let { l -> listen = l.copy(words = l.words + wordsFrom(b, at), chapters = l.chapters + 1) }
        keep(b, b.parts.size + 1)
        listen = null
    }

    /** Listening stopped at part [at] of [b] (null: nothing was open): the listen is over. */
    fun stopped(b: Book?, at: Int) {
        if (b != null) keep(b, at)
        listen = null
        playingSince = 0
    }
}
