package kokomux.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive

/**
 * The reader (tui.sh's tui_open): the title and chapter, the reading column
 * with the part being read on the golden line, and the footer: where you are
 * (tap the bar to jump) and the time left, previous / play / next, and the
 * speed, sleep timer, chapters and voice, each showing its value. [position]
 * is how far into the part the player is, in ms, for lighting the word being
 * said.
 */
@Composable
fun ReaderScreen(
    p: Playing, prefs: Prefs, position: () -> Long, actions: Actions, to: ReaderTo,
) {
    val t = LocalTokens.current
    val keys = remember { FocusRequester() }
    LaunchedEffect(Unit) { keys.requestFocus() }
    Column(
        Modifier.fillMaxSize().background(t.bg).safeDrawingPadding()
            .focusRequester(keys).focusable().onPreviewKeyEvent { it.type == KeyEventType.KeyDown && readerKey(it, p, actions, to) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Header(p, actions, to)
        Box(Modifier.weight(1f).widthIn(max = Space.column).fillMaxWidth()) {
            ReadingColumn(p, prefs, position, actions)
            // The text dissolves into the background at the column's ends instead of being cut.
            Box(Modifier.fillMaxWidth().height(Space.xl).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(t.bg, t.bg.copy(alpha = 0f)))))
            Box(Modifier.fillMaxWidth().height(Space.xxl).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(t.bg.copy(alpha = 0f), t.bg))))
        }
        Footer(p, actions, to)
    }
}

/**
 * Termux's keys, for DeX or a keyboard: Space plays or pauses, ← hears the
 * part again, → the next one, [ and ] the chapter before or after, − and +
 * the speed, t the sleep timer, c the chapters, s settings, v the voice, q
 * stops.
 */
private fun readerKey(e: KeyEvent, p: Playing, actions: Actions, to: ReaderTo): Boolean {
    val here = p.chapters.indexOf(p.chapter)
    when (e.key) {
        Key.Spacebar -> actions.playPause()
        Key.DirectionLeft -> actions.again()
        Key.DirectionRight -> actions.next()
        Key.LeftBracket -> p.chapters.getOrNull(here - 1)?.let { actions.jump(it.first) }
        Key.RightBracket -> p.chapters.getOrNull(here + 1)?.let { actions.jump(it.first) }
        Key.Minus, Key.NumPadSubtract -> actions.speed(((p.speed * 10 + 0.5f).toInt() - 1).coerceAtLeast(5) / 10f)
        Key.Plus, Key.Equals, Key.NumPadAdd -> actions.speed(((p.speed * 10 + 0.5f).toInt() + 1).coerceAtMost(20) / 10f)
        Key.T -> to.sleep()
        Key.C -> to.chapters()
        Key.S -> to.settings()
        Key.V -> to.voices()
        Key.Q -> { actions.stop(); to.home() }
        else -> return false
    }
    return true
}

/** Where the reader's controls lead: home, and the sheets and screens they open. */
class ReaderTo(
    val home: () -> Unit = {}, val speed: () -> Unit = {}, val sleep: () -> Unit = {}, val chapters: () -> Unit = {},
    val voices: () -> Unit = {}, val settings: () -> Unit = {},
)

/** ⌄ folds the reader away (it keeps playing); the title and chapter; ⋮ for settings and stopping. */
@Composable
private fun Header(p: Playing, actions: Actions, to: ReaderTo) {
    Row(Modifier.widthIn(max = Space.column + Space.touch).fillMaxWidth().height(Space.touch).padding(horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically) {
        GlyphButton(Glyphs.down, "Home", onClick = to.home)
        Column(Modifier.weight(1f).padding(horizontal = Space.xs)) {
            Text(p.title, style = Type.label, color = LocalTokens.current.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            p.chapter?.title?.takeIf { p.chapters.size > 1 }?.let { Dim(it, maxLines = 1) }
        }
        var more by remember { mutableStateOf(false) }
        Box {
            GlyphButton(Glyphs.more, "More") { more = true }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                DropdownMenuItem(text = { Text("Settings", style = Type.body) }, onClick = { more = false; to.settings() },
                    leadingIcon = { Icon(Glyphs.settings, null, Modifier.size(Space.l)) })
                DropdownMenuItem(text = { Text("Stop", style = Type.body) }, onClick = { more = false; actions.stop(); to.home() },
                    leadingIcon = { Icon(Glyphs.stop, null, Modifier.size(Space.l)) })
            }
        }
    }
}

/**
 * Every part of the book, the one being read on the golden line, φ⁻² of the
 * way down. With the focus effect, text fades the further it is from it,
 * a quarter golden step a line (about three a part), never below φ⁻⁴: what's
 * been heard from ½, what's to come from φ⁻¹. Tap a part to hear it.
 */
@Composable
private fun ReadingColumn(p: Playing, prefs: Prefs, position: () -> Long, actions: Actions) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (p.at - 1).coerceAtLeast(0))
    LaunchedEffect(p.at) { if (p.at > 0) list.animateScrollToItem(p.at - 1) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val golden = maxHeight * Phi.inv(2f)
        LazyColumn(
            state = list, modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Space.l, end = Space.l, top = golden, bottom = maxHeight - golden),
        ) {
            itemsIndexed(p.parts, key = { i, _ -> i }) { i, text ->
                val part = i + 1
                if (part == p.at) Now(text, p, prefs, position, onClick = { actions.again() })
                else Far(text, part - p.at, prefs.focus, onClick = { actions.jump(part) })
            }
        }
    }
}

/** A part not being read: its distance from the one that is sets how far it fades. */
@Composable
private fun Far(text: String, distance: Int, focus: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    val level = when {
        !focus -> if (distance < 0) 0.5f else Phi.inv(1f)
        distance < 0 -> Focus.tone(0.5f, (-distance - 1) * 3f + 1f)
        else -> Focus.tone(Phi.inv(1f), (distance - 1) * 3f)
    }
    val colour by animateColorAsState(t.tone(level), tween(Motion.EASE_OUT), label = "focus")
    Text(
        text, style = Type.reading, color = colour,
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = Space.s),
    )
}

/**
 * The part being read, marked with the accent's bar: words already said at
 * full strength, the one being said on the accent's wash, the next few in the
 * sharpest focus and the rest settling to φ⁻¹, a golden step every 12 letters.
 */
@Composable
private fun Now(text: String, p: Playing, prefs: Prefs, position: () -> Long, onClick: () -> Unit) {
    val t = LocalTokens.current
    var ms by remember(p.at) { mutableLongStateOf(position()) }
    val light = prefs.words && p.words.isNotEmpty()
    LaunchedEffect(p.at, p.playing, light) {
        while (light && p.playing && isActive) {
            withFrameMillis { ms = position() }
        }
        ms = position()
    }
    val at = ms - prefs.offsetMs
    val lit = if (light) p.words.indexOfLast { it.start <= at } else -1
    val shown: AnnotatedString = if (!light) AnnotatedString(text) else buildAnnotatedString {
        var pos = 0
        p.words.forEachIndexed { k, w ->
            if (w.from < pos || w.to > text.length) return@forEachIndexed
            if (w.from > pos) withStyle(SpanStyle(color = tone(t, k, lit, w.from, p.words, prefs.focus))) { append(text, pos, w.from) }
            val style = when {
                k == lit -> SpanStyle(color = t.fg, background = t.hl)
                else -> SpanStyle(color = tone(t, k, lit, w.from, p.words, prefs.focus))
            }
            withStyle(style) { append(text, w.from, w.to) }
            pos = w.to
        }
        if (pos < text.length) withStyle(SpanStyle(color = if (lit >= p.words.lastIndex) t.fg else t.dim)) { append(text, pos, text.length) }
    }
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Box(Modifier.padding(top = Space.m, end = Space.s).width(3.dp).height(Space.l).clip(CircleShape).background(t.accent))
        Text(shown, style = Type.reading, color = t.fg, modifier = Modifier.padding(vertical = Space.s))
    }
}

/** A word's colour in the part being read: said, or still to come and how near. */
private fun tone(t: Tokens, k: Int, lit: Int, from: Int, words: List<WordSpan>, focus: Boolean) = when {
    k <= lit -> t.fg
    !focus -> t.dim
    else -> {
        val litEnd = words.getOrNull(lit)?.to ?: 0
        t.tone(Phi.inv(1f) + (1f - Phi.inv(1f)) / 2f * Focus.decay(((from - litEnd).coerceAtLeast(0)) / 3f))
    }
}

/**
 * The footer: the bar (where you are, how far ahead parts are made, where
 * chapters start; tap it to jump), the part and the time left, previous /
 * play / next (play spins while the first part is made), then the speed, the
 * sleep timer, the chapters and the voice, each showing its value.
 */
@Composable
private fun Footer(p: Playing, actions: Actions, to: ReaderTo) {
    val n = p.parts.size
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = Space.column).fillMaxWidth().padding(horizontal = Space.l).padding(bottom = Space.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Seek(p, actions)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                RoundButton(Glyphs.previous, "Previous", Space.xxl, enabled = p.at > 0, onClick = actions::previous)
                RoundButton(if (p.playing) Glyphs.pause else Glyphs.play, if (p.playing) "Pause" else "Play", Space.xxxl,
                    primary = true, busy = p.at == 0, onClick = actions::playPause)
                RoundButton(Glyphs.next, "Next", Space.xxl, enabled = p.at in 1 until n, onClick = actions::next)
            }
            Spacer(Modifier.height(Space.m))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                QuietButton(Glyphs.speed, speedLabel(p.speed), on = p.speed != 1f, description = "Speed ${speedLabel(p.speed)}",
                    onClick = to.speed)
                QuietButton(Glyphs.moon, p.sleep?.let { sleepLabel(it) } ?: "Sleep", on = p.sleep != null,
                    description = "Sleep timer", onClick = to.sleep)
                if (p.chapters.size > 1) QuietButton(Glyphs.chapters, "Chapters", onClick = to.chapters)
                QuietButton(Glyphs.hear, Voices.name(p.voice), description = "Voice ${Voices.name(p.voice)}", onClick = to.voices)
            }
        }
    }
}

/**
 * The bar and, under it, the part and the time left: one strip a thumb high.
 * Tap it to go there, or drag along it to see which part and chapter you'd
 * land on, and let go to go there.
 */
@Composable
private fun Seek(p: Playing, actions: Actions) {
    val t = LocalTokens.current
    val n = p.parts.size
    var scrub by remember { mutableStateOf<Float?>(null) }
    fun partAt(f: Float) = 1 + Math.round(f.coerceIn(0f, 1f) * (n - 1))
    val seeking = if (n > 1) Modifier
        .pointerInput(n) { detectTapGestures { actions.jump(partAt(it.x / size.width)) } }
        .pointerInput(n) {
            detectHorizontalDragGestures(
                onDragStart = { scrub = it.x / size.width },
                onDragEnd = { scrub?.let { actions.jump(partAt(it)) }; scrub = null },
                onDragCancel = { scrub = null },
            ) { change, _ -> scrub = (change.position.x / size.width).coerceIn(0f, 1f) }
        }
        .semantics {
            contentDescription = "Position"
            progressBarRangeInfo = ProgressBarRangeInfo(p.at.toFloat(), 1f..n.toFloat(), n - 2)
            setProgress { v -> actions.jump(Math.round(v).coerceIn(1, n)); true }
        } else Modifier
    Box(Modifier.fillMaxWidth().height(Space.touch).then(seeking)) {
        Track(
            progress = scrub ?: if (n > 1 && p.at > 0) (p.at - 1f) / (n - 1) else 0f,
            made = if (n > 1) (p.madeTo - 1f).coerceAtLeast(0f) / (n - 1) else 0f,
            marks = if (p.chapters.size > 1 && n > 1) p.chapters.drop(1).map { (it.first - 1f) / (n - 1) } else emptyList(),
            height = if (scrub != null) 5.dp else 3.dp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = Space.s),
        )
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = Space.xs)) {
            val to = scrub?.let { partAt(it) }
            if (to != null) {
                val chapter = p.chapters.lastOrNull { it.first <= to }?.title?.takeIf { p.chapters.size > 1 }
                Text(listOfNotNull("$to of $n", chapter).joinToString(" · "), style = Type.small, color = t.accent2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Dim(if (p.at > 0) "${p.at} of $n" else "", Modifier.weight(1f), maxLines = 1)
                if (p.left > 0) Dim(timeLeftLabel(p.left), maxLines = 1)
            }
        }
    }
}

/** "12 min" or "end of chapter". */
fun sleepLabel(s: Sleep) = when (s) {
    is Sleep.Minutes -> "${((s.leftMs + 59_999) / 60_000)} min"
    Sleep.EndOfChapter -> "end of chapter"
}
