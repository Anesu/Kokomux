package kokomux.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Listens kept and added up: days, streaks, the pace, and when a book will be finished. */
class StatsTest {
    private val zone = ZoneId.of("Africa/Harare")
    private val today = LocalDate.of(2026, 11, 14)

    /** A listen [daysAgo] at 21:00, [minutes] long. */
    private fun listen(id: Long, daysAgo: Int, minutes: Int, book: String = "road", words: Int = 0, chapters: Int = 0,
                       left: Int = 10_000, chaptersLeft: Int = -1): Session {
        val start = today.minusDays(daysAgo.toLong()).atTime(21, 0).atZone(zone).toInstant().toEpochMilli()
        return Session(id, book, book.replaceFirstChar { it.uppercase() }, start, start + minutes * 60_000L, minutes * 60.0,
            "Chapter 1", "Chapter 2", words, chapters, left, chaptersLeft)
    }

    @Test
    fun aListenWrittenAgainCountsOnce() {
        val file = Files.createTempFile("kokomux", ".tsv").toFile().apply { delete() }
        val kept = Sessions(file)
        kept.save(listen(1, 0, 5))
        kept.save(listen(1, 0, 25, words = 3000).copy(to = "Chapter 3\twith a tab"))
        kept.save(listen(2, 1, 10, book = "summon"))
        val all = kept.all()
        assertEquals(listOf(2L, 1L), all.map { it.id })
        assertEquals(1500.0, all[1].seconds, 0.0)
        assertEquals("Chapter 3 with a tab", all[1].to)
    }

    @Test
    fun daysWeeksAndMonthsAddUp() {
        val s = Stats(listOf(listen(1, 0, 30), listen(2, 0, 15), listen(3, 2, 60), listen(4, 20, 5)), zone, today)
        assertEquals(45 * 60.0, s.on(today), 0.0)
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 3600.0, 0.0, 2700.0), s.week().map { it.second })
        assertEquals(today, s.week().last().first)
        val nov = s.month(YearMonth.of(2026, 11))
        assertEquals(30, nov.size)
        assertEquals(3600.0, nov[11], 0.0)
        assertEquals(110 * 60.0, s.total, 0.0)
    }

    @Test
    fun aStreakIsDaysInARowWithAMinuteOrMore() {
        // Today not yet listened: the streak up to yesterday still stands.
        val s = Stats(listOf(listen(1, 1, 30), listen(2, 2, 2), listen(3, 3, 9), listen(4, 5, 30), listen(5, 4, 0)), zone, today)
        assertEquals(3, s.streak)
        assertEquals(3, s.best)
        assertEquals(4, Stats(s.sessions + listen(6, 0, 1), zone, today).streak)
        assertEquals(0, Stats(listOf(listen(1, 2, 30)), zone, today).streak)
    }

    @Test
    fun theFinishDateIsAtTheBooksOwnPace() {
        // 3000 words a day over the last 2 days: 9000 left is 3 more days, today the first.
        val s = Stats(listOf(listen(1, 1, 30, words = 3000), listen(2, 0, 30, words = 3000, left = 9000)), zone, today)
        val road = s.books().single()
        assertEquals(6000, road.words)
        assertEquals(LocalDate.of(2026, 11, 16), road.finish)
        assertEquals(3000.0, s.wordsPerDay, 0.0)
        // A web novel: chapters out still to hear, at its chapters a day.
        val n = Stats(listOf(listen(1, 1, 30, book = "summon", chapters = 2, chaptersLeft = 9),
            listen(2, 0, 30, book = "summon", chapters = 4, chaptersLeft = 7)), zone, today).books().single()
        assertEquals(LocalDate.of(2026, 11, 16), n.finish)
        assertEquals(null, Stats(listOf(listen(1, 0, 30, left = 0)), zone, today).books().single().finish)
    }
}
