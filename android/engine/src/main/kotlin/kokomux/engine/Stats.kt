package kokomux.engine

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.ceil

/**
 * A listen: one book heard from one place to another between pauses (a pause
 * of under [Sessions.JOIN] joins the listen either side). [seconds] is time
 * spent playing; [words] and [chapters] what was heard to the end in it;
 * [left] the words still to come in the book, and for a web novel
 * [chaptersLeft] the chapters out after the one it's at.
 */
data class Session(
    val id: Long, val book: String, val title: String, val start: Long, val end: Long, val seconds: Double,
    val from: String, val to: String, val words: Int, val chapters: Int, val left: Int, val chaptersLeft: Int = -1,
)

/**
 * The listens, kept in one file a line each (tab-separated; a listen written
 * again as it goes on, the last line of an id counting).
 */
class Sessions(private val file: File) {
    @Synchronized
    fun all(): List<Session> {
        if (!file.exists()) return emptyList()
        val byId = LinkedHashMap<Long, Session>()
        for (line in file.readLines()) parse(line)?.let { byId.remove(it.id); byId[it.id] = it }
        return byId.values.sortedBy { it.start }
    }

    /** Keep [s] (again, if it's already kept: the newest is what counts). */
    @Synchronized
    fun save(s: Session) {
        file.parentFile?.mkdirs()
        file.appendText(listOf(s.id, s.book, s.start, s.end, "%.1f".format(java.util.Locale.ROOT, s.seconds), s.words,
            s.chapters, s.left, s.chaptersLeft, clean(s.from), clean(s.to), clean(s.title)).joinToString("\t") + "\n")
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    private fun parse(line: String): Session? {
        val f = line.split('\t')
        if (f.size < 12) return null
        return runCatching {
            Session(f[0].toLong(), f[1], f[11], f[2].toLong(), f[3].toLong(), f[4].toDouble(), f[9], f[10],
                f[5].toInt(), f[6].toInt(), f[7].toInt(), f[8].toInt())
        }.getOrNull()
    }

    companion object {
        /** A pause shorter than this is part of the same listen: 3 minutes. */
        const val JOIN = 3 * 60_000L
    }
}

/**
 * What the listens add up to, day by day in [zone], as of [today]: time
 * listened (in all, a day, a book), the streak of days listened, the pace
 * (words and chapters a day) and when a book will be finished at it.
 */
class Stats(val sessions: List<Session>, private val zone: ZoneId, val today: LocalDate) {
    private fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /** Seconds listened each day (a listen counts on the day it started). */
    val byDay: Map<LocalDate, Double> = sessions.groupBy { day(it.start) }.mapValues { (_, s) -> s.sumOf { it.seconds } }

    val total get() = sessions.sumOf { it.seconds }
    fun on(d: LocalDate) = byDay[d] ?: 0.0

    /** The last 7 days, today last. */
    fun week(): List<Pair<LocalDate, Double>> = (6 downTo 0).map { today.minusDays(it.toLong()) }.map { it to on(it) }

    /** Each day of [month] (1 to its length) and the seconds listened then. */
    fun month(month: YearMonth): List<Double> = (1..month.lengthOfMonth()).map { on(month.atDay(it)) }

    /** A day counts towards a streak with a minute or more of listening. */
    private fun counts(d: LocalDate) = on(d) >= 60

    /** Days in a row listened, up to today (or yesterday, while today's listen is still to come). */
    val streak: Int get() {
        var d = if (counts(today)) today else today.minusDays(1)
        var n = 0
        while (counts(d)) { n++; d = d.minusDays(1) }
        return n
    }

    /** The longest streak there's been. */
    val best: Int get() {
        var best = 0
        var run = 0
        var last: LocalDate? = null
        for (d in byDay.keys.filter(::counts).sorted()) {
            run = if (last != null && d == last.plusDays(1)) run + 1 else 1
            best = maxOf(best, run)
            last = d
        }
        return best
    }

    /** Over the last [PACE_DAYS] days (or since the first listen, if that's sooner), a day on average. */
    private fun perDay(what: (Session) -> Double, of: List<Session> = sessions): Double {
        val from = today.minusDays(PACE_DAYS - 1L)
        val recent = of.filter { !day(it.start).isBefore(from) }
        if (recent.isEmpty()) return 0.0
        val first = sessions.minOf { day(it.start) }.let { if (it.isAfter(from)) it else from }
        val days = java.time.temporal.ChronoUnit.DAYS.between(first, today) + 1
        return recent.sumOf(what) / days
    }

    val wordsPerDay get() = perDay({ it.words.toDouble() })
    val chaptersPerDay get() = perDay({ it.chapters.toDouble() })
    val secondsPerDay get() = perDay({ it.seconds })

    /** What [book]'s listens add up to. */
    data class Book(val id: String, val title: String, val seconds: Double, val words: Int, val chapters: Int,
                    val listens: Int, val last: Session, val finish: LocalDate?)

    /**
     * Each book's listens, the one heard most recently first, with when it'll be finished at the
     * pace it's been heard lately (a web novel: up to date), or at the pace of all listening if
     * it hasn't been heard lately.
     */
    fun books(): List<Book> = sessions.groupBy { it.book }.map { (id, s) ->
        val last = s.maxBy { it.end }
        val novel = last.chaptersLeft >= 0
        val pace = if (novel) perDay({ it.chapters.toDouble() }, s).takeIf { it > 0 } ?: chaptersPerDay
        else perDay({ it.words.toDouble() }, s).takeIf { it > 0 } ?: wordsPerDay
        val left = if (novel) last.chaptersLeft.toDouble() else last.left.toDouble()
        val finish = when {
            left <= 0 -> null
            pace <= 0 -> null
            else -> today.plusDays(ceil(left / pace).toLong() - 1).coerceAtLeast(today)
        }
        Book(id, last.title, s.sumOf { it.seconds }, s.sumOf { it.words }, s.sumOf { it.chapters }, s.size, last, finish)
    }.sortedByDescending { it.last.end }

    companion object {
        /** The pace is the last fortnight's. */
        const val PACE_DAYS = 14
    }
}

private fun LocalDate.coerceAtLeast(d: LocalDate) = if (isBefore(d)) d else this
