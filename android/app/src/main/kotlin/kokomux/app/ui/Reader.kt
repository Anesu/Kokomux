package kokomux.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive

/**
 * The reader (tui.sh's tui_open): a header, the reading column with the part
 * being read on the golden line, and the footer: what's happening and where,
 * the progress (tap it to jump), Again / Play / Next, and the quieter speed,
 * sleep, chapters and menu. [position] is how far into the part the player
 * is, in ms, for lighting the word being said.
 */
@Composable
fun ReaderScreen(
    p: Playing, prefs: Prefs, position: () -> Long, actions: Actions,
    onBack: () -> Unit, onChapters: () -> Unit, onSleep: () -> Unit, onMenu: () -> Unit,
) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Header(p, onBack)
        Box(Modifier.weight(1f).widthIn(max = Space.column).fillMaxWidth()) {
            ReadingColumn(p, prefs, position, actions)
            // The text dissolves into the background at the column's ends instead of being cut.
            Box(Modifier.fillMaxWidth().height(Space.xl).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(t.bg, t.bg.copy(alpha = 0f)))))
            Box(Modifier.fillMaxWidth().height(Space.xxl).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(t.bg.copy(alpha = 0f), t.bg))))
        }
        Footer(p, actions, onChapters, onSleep, onMenu)
    }
}

@Composable
private fun Header(p: Playing, onBack: () -> Unit) {
    val t = LocalTokens.current
    Row(Modifier.widthIn(max = Space.column + Space.touch).fillMaxWidth().height(Space.touch).padding(end = Space.l),
        verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(Space.touch).clip(CircleShape).clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "Home" },
            contentAlignment = Alignment.Center,
        ) { Icon(Glyphs.back, null, Modifier.size(Space.l), tint = t.dim) }
        Column(Modifier.weight(1f)) {
            Text(p.title, style = Type.label, color = t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Where you are, if the book has chapters; otherwise who's reading.
            Dim(p.chapter?.title?.takeIf { p.chapters.size > 1 } ?: "${Voices.name(p.voice)} · ${Voices.describe(p.voice)}",
                maxLines = 1)
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

/** The footer, as tui.sh draws it: what's happening, the bar, the transport, the quiet controls. */
@Composable
private fun Footer(p: Playing, actions: Actions, onChapters: () -> Unit, onSleep: () -> Unit, onMenu: () -> Unit) {
    val t = LocalTokens.current
    val n = p.parts.size
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = Space.column).fillMaxWidth().padding(horizontal = Space.l).padding(bottom = Space.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val (symbol, state) = when {
                    p.at == 0 -> "·" to "Getting ready"
                    p.playing -> "▶" to "Playing"
                    else -> "‖" to "Paused"
                }
                val extra = listOfNotNull(
                    p.sleep?.let { sleepLabel(it) },
                    if (p.making && p.madeTo < n) "made to ${p.madeTo}" else null,
                ).joinToString(" · ")
                Text("$symbol ", style = Type.small, color = t.accent)
                Dim(state + if (extra.isNotEmpty()) " · $extra" else "", Modifier.weight(1f), maxLines = 1)
                if (n > 1 && p.at > 0) Dim("${p.at} of $n", maxLines = 1)
            }
            Track(
                progress = if (n > 1 && p.at > 0) (p.at - 1f) / (n - 1) else 0f,
                made = if (n > 1) (p.madeTo - 1f).coerceAtLeast(0f) / (n - 1) else 0f,
                marks = if (p.chapters.size > 1 && n > 1) p.chapters.drop(1).map { (it.first - 1f) / (n - 1) } else emptyList(),
                onSeek = { f -> actions.jump(1 + Math.round(f * (n - 1))) },
            )
            Spacer(Modifier.height(Space.s))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                RoundButton(Glyphs.again, "Again", Space.xxl, onClick = actions::again)
                RoundButton(if (p.playing) Glyphs.pause else Glyphs.play, if (p.playing) "Pause" else "Play", Space.xxxl,
                    primary = true, onClick = actions::playPause)
                RoundButton(Glyphs.next, "Next", Space.xxl, enabled = p.at < n, onClick = actions::next)
            }
            Spacer(Modifier.height(Space.m))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                SpeedControl(p.speed, actions)
                QuietButton(Glyphs.moon, if (p.sleep != null) sleepLabel(p.sleep) else "Sleep", on = p.sleep != null, onClick = onSleep)
                if (p.chapters.size > 1) QuietButton(Glyphs.chapters, "Chapters", onClick = onChapters)
                QuietButton(Glyphs.menu, "Menu", onClick = onMenu)
            }
        }
    }
}

/** − 1.0× +: the speed, a tenth at a time, from 0.5× to 2×; heard at once. */
@Composable
private fun SpeedControl(speed: Float, actions: Actions) {
    val t = LocalTokens.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        SmallGlyph(Glyphs.minus, "Slower", enabled = speed > 0.55f) { actions.speed(((speed * 10).toInt() - 1).coerceAtLeast(5) / 10f) }
        Text(speedLabel(speed), style = Type.small, color = t.accent2, modifier = Modifier.widthIn(min = Space.xl))
        SmallGlyph(Glyphs.plus, "Faster", enabled = speed < 1.95f) { actions.speed(((speed * 10 + 0.5f).toInt() + 1).coerceAtMost(20) / 10f) }
    }
}

@Composable
private fun SmallGlyph(glyph: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(glyph, null, Modifier.size(Space.l - 3.dp), tint = if (enabled) t.accent2 else t.line) }
}

/** "12 min" or "end of chapter". */
fun sleepLabel(s: Sleep) = when (s) {
    is Sleep.Minutes -> "${((s.leftMs + 59_999) / 60_000)} min"
    Sleep.EndOfChapter -> "end of chapter"
}
