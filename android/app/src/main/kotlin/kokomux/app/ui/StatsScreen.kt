package kokomux.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kokomux.engine.Session
import kokomux.engine.Stats

/**
 * Listening, added up: today's time (the one big number), the streak, this
 * week and all time; the last 7 days as bars and the month as a calendar
 * (tap a bar or a day for its time); the pace; each book's time and when
 * it'll be finished at that pace; and the listens themselves. A chip shows
 * one book's alone.
 */
@Composable
fun StatsScreen(all: Stats?, onBack: () -> Unit, book: String? = null) {
    val t = LocalTokens.current
    var only by rememberSaveable { mutableStateOf(book) }
    Column(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopBar("Listening", onBack)
        if (all == null || all.sessions.isEmpty()) {
            GoldenColumn(Modifier.fillMaxSize()) {
                Logo(Space.xl)
                Spacer(Modifier.height(Space.l))
                Text("Nothing heard yet", style = Type.title, color = quiet())
            }
            return@Column
        }
        val books = all.books()
        if (books.size > 1) LazyRow(
            Modifier.widthIn(max = Space.column).fillMaxWidth().padding(bottom = Space.s),
            contentPadding = PaddingValues(horizontal = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            item { Chip("All", only == null) { only = null } }
            items(books, key = { it.id }) { b -> Chip(b.title.take(24) + if (b.title.length > 24) "…" else "", only == b.id) { only = b.id } }
        }
        val s = remember(all, only) { if (only == null) all else Stats(all.sessions.filter { it.book == only }, ZoneId.systemDefault(), all.today) }
        Column(
            Modifier.widthIn(max = Space.column).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l).padding(bottom = Space.xxl),
        ) {
            // The one big number: today.
            Dim("Today", style = Type.label)
            Text(duration(s.on(s.today)), style = Type.hero, color = t.fg)
            Spacer(Modifier.height(Space.m))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Figure("Streak", if (all.streak == 1) "1 day" else "${all.streak} days", Modifier.weight(1f))
                Figure("This week", hours(s.week().sumOf { it.second }), Modifier.weight(1f))
                Figure("All time", hours(s.total), Modifier.weight(1f))
            }

            SectionTitle("Last 7 days")
            WeekBars(s.week())

            var month by remember { mutableStateOf(YearMonth.from(s.today)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionTitle("${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}") }
                GlyphButton(Glyphs.back, "Month before") { month = month.minusMonths(1) }
                if (month < YearMonth.from(s.today)) GlyphButton(Glyphs.forward, "Month after") { month = month.plusMonths(1) }
            }
            MonthCalendar(month, s.month(month), s.today)

            SectionTitle("Pace")
            Dim(listOf("${duration(s.secondsPerDay)} a day", "${"%,d".format(s.wordsPerDay.toInt())} words a day",
                "${"%.1f".format(s.chaptersPerDay)} chapters a day").joinToString(" · "), style = Type.body)

            if (only == null) {
                SectionTitle("Books")
                for (b in books) BookLine(b, s.today) { only = b.id }
            } else books.firstOrNull { it.id == only }?.let { b ->
                SectionTitle("Finished")
                Text(finishLabel(b, s.today), style = Type.body, color = t.fg)
            }

            SectionTitle("Listens")
            for (l in s.sessions.sortedByDescending { it.start }.take(34)) ListenLine(l, s.today, showBook = only == null)
        }
    }
}

/** A figure: what it is over its value (sans, the value's own width). */
@Composable
private fun Figure(label: String, value: String, modifier: Modifier) {
    val t = LocalTokens.current
    Column(modifier.clip(Corner.l).background(t.surf).padding(Space.m)) {
        OnSurface(t.surf) { Dim(label, maxLines = 1) }
        Text(value, style = Type.title, color = t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The last 7 days as columns in the accent, rounded at the top and square on the baseline, at
 * most 24 dp wide; today's value (or the one tapped) said above the chart, the days below.
 */
@Composable
private fun WeekBars(days: List<Pair<LocalDate, Double>>) {
    val t = LocalTokens.current
    var picked by remember(days) { mutableStateOf(days.lastIndex) }
    val max = days.maxOf { it.second }.coerceAtLeast(60.0)
    val (day, secs) = days[picked]
    Text("${dayName(day, days.last().first)} · ${duration(secs)}", style = Type.small, color = t.fg)
    Spacer(Modifier.height(Space.s))
    Canvas(
        Modifier.fillMaxWidth().height(Space.xxl * 2).pointerInput(days) {
            detectTapGestures { p -> picked = (p.x / (size.width / days.size)).toInt().coerceIn(0, days.lastIndex) }
        }.semantics {
            contentDescription = "Last 7 days: " + days.joinToString(", ") { (d, s) -> "${dayName(d, days.last().first)} ${duration(s)}" }
        },
    ) {
        val slot = size.width / days.size
        val w = minOf(24.dp.toPx(), slot * Phi.inv(1f))
        val r = 4.dp.toPx()
        drawLine(t.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        days.forEachIndexed { i, (_, s) ->
            val h = (s / max * (size.height - r)).toFloat()
            if (h <= 0f) return@forEachIndexed
            val x = slot * i + (slot - w) / 2
            val y = size.height - h
            val colour = if (i == picked) t.accent else Tokens.mix(t.surf, t.accent, Phi.inv(1f))
            // Rounded at the data end, square at the baseline.
            val path = Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(x, y, x + w, size.height,
                    topLeftCornerRadius = CornerRadius(minOf(r, h)), topRightCornerRadius = CornerRadius(minOf(r, h))))
            }
            drawPath(path, colour)
        }
    }
    Row(Modifier.fillMaxWidth()) {
        days.forEachIndexed { i, (d, _) ->
            Text(d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = Type.tiny,
                color = if (i == picked) t.fg else quiet(), textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * The month as a calendar, Monday first: each day's square in the accent, deeper the longer it
 * was listened (none: the surface), today ringed; tap a day for its time.
 */
@Composable
private fun MonthCalendar(month: YearMonth, secs: List<Double>, today: LocalDate) {
    val t = LocalTokens.current
    var picked by remember(month) { mutableStateOf<Int?>(null) }
    val lead = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
    val cells = lead + secs.size
    // Five steps of the accent over the surface, φ apart; the longest day is the deepest.
    val steps = listOf(Phi.inv(4f), Phi.inv(3f), Phi.inv(2f), Phi.inv(1f), 1f)
    val top = secs.maxOrNull()?.coerceAtLeast(60.0) ?: 60.0
    picked?.let { d -> Text("${dayName(month.atDay(d), today)} · ${duration(secs[d - 1])}", style = Type.small, color = t.fg) }
    Spacer(Modifier.height(Space.s))
    Row(Modifier.fillMaxWidth()) {
        for (d in DayOfWeek.entries) Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = Type.tiny, color = quiet(),
            textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
    }
    for (week in 0 until (cells + 6) / 7) Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (k in 0 until 7) {
            val day = week * 7 + k - lead + 1
            Box(Modifier.weight(1f).aspectRatio(1f)) {
                if (day in 1..secs.size) {
                    val s = secs[day - 1]
                    val fill = if (s <= 0) t.surf else Tokens.mix(t.surf, t.accent, steps[((s / top) * (steps.size - 1)).toInt().coerceIn(0, steps.lastIndex)])
                    val isToday = month.atDay(day) == today
                    Box(
                        Modifier.fillMaxSize().clip(Corner.s).background(fill)
                            .then(if (isToday) Modifier.border(2.dp, t.accent2, Corner.s) else Modifier)
                            .clickable(role = Role.Button) { picked = day }
                            .semantics { contentDescription = "${dayName(month.atDay(day), today)}, ${duration(s)}" },
                        contentAlignment = Alignment.Center,
                    ) {
                        // A day's number inside its square: white or near-black, by the square's lightness.
                        Text("$day", style = Type.tiny, color = if (fill.luminance() > 0.4f) Color(0xFF141414) else Color.White)
                    }
                }
            }
        }
    }
}

/** A book's time and listens, and when it'll be finished; tap for its stats alone. */
@Composable
private fun BookLine(b: Stats.Book, today: LocalDate, onClick: () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.touch).clip(Corner.m).clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Space.s, horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Cover(b.title, Space.l + Space.s)
        Column(Modifier.weight(1f)) {
            Text(b.title, style = Type.label, color = t.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Dim("${duration(b.seconds)} · ${if (b.listens == 1) "1 listen" else "${b.listens} listens"}", maxLines = 1)
        }
        Text(finishLabel(b, today), style = Type.small, color = t.accent2, maxLines = 2, textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = Space.xxl * 2))
    }
}

/** A listen: when, how long, the book (in all books' list), and from where to where. */
@Composable
private fun ListenLine(l: Session, today: LocalDate, showBook: Boolean) {
    val t = LocalTokens.current
    val start = Instant.ofEpochMilli(l.start).atZone(ZoneId.systemDefault())
    Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Column(Modifier.widthIn(min = Space.xxl + Space.l)) {
            Text(dayName(start.toLocalDate(), today), style = Type.small, color = t.fg, maxLines = 1)
            Dim("%02d:%02d".format(start.hour, start.minute), maxLines = 1)
        }
        Column(Modifier.weight(1f)) {
            Text(listOfNotNull(duration(l.seconds), l.title.takeIf { showBook }).joinToString(" · "), style = Type.small,
                color = t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Dim(if (l.from == l.to) l.from else "${l.from} → ${l.to}", maxLines = 1)
        }
    }
}

/** "Today", "Yesterday", "Wed 12 Nov". */
fun dayName(d: LocalDate, today: LocalDate): String = when (d) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> "${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${d.dayOfMonth} " +
        d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
}

/** "2 h 14 min", "34 min", "0 min". */
fun duration(seconds: Double): String {
    val m = Math.round(seconds / 60).toInt()
    return if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)} min" else "$m min"
}

/** A figure's time, short: "34 min", "5.1 h", "48 h". */
fun hours(seconds: Double): String = when {
    seconds < 3600 -> "${Math.round(seconds / 60)} min"
    seconds < 36_000 -> "%.1f h".format(seconds / 3600)
    else -> "${Math.round(seconds / 3600)} h"
}

/** When a book will be finished: "Finished", "Done Fri 21 Nov", "Up to date Thu"; a novel heard up to date says so. */
fun finishLabel(b: Stats.Book, today: LocalDate): String {
    val novel = b.last.chaptersLeft >= 0
    val finish = b.finish
    return when {
        novel && b.last.chaptersLeft == 0 -> "Up to date"
        !novel && b.last.left <= 0 -> "Finished"
        finish == null -> ""
        novel -> "Up to date by ${dayName(finish, today)}"
        else -> "Done by ${dayName(finish, today)}"
    }
}
