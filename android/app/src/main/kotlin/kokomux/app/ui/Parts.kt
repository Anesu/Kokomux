package kokomux.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sin

/**
 * A screen's content in the reading column (610 dp at most, centred), placed
 * on the optical centre when it's shorter than the screen: the free space is
 * split 1 : φ above and below. Longer content scrolls.
 */
@Composable
fun GoldenColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier) {
        val viewport = constraints.maxHeight
        Box(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Layout(content = {
                Column(
                    Modifier.widthIn(max = Space.column).fillMaxWidth().padding(horizontal = Space.l, vertical = Space.xl),
                    content = content,
                )
            }) { measurables, constraints ->
                val p = measurables.first().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                val height = if (viewport == Constraints.Infinity) p.height else max(p.height, viewport)
                layout(p.width, height) { p.place(0, ((height - p.height) * Phi.inv(2f)).toInt()) }
            }
        }
    }
}

/** A section's name. */
@Composable
fun SectionTitle(text: String) {
    val t = LocalTokens.current
    Text(text, style = Type.label, color = t.fg, modifier = Modifier.fillMaxWidth().padding(top = Space.l, bottom = Space.s))
}

/** One thing to choose: a glyph, what it is, and its value or state; the whole row is the target. */
@Composable
fun ListRow(
    glyph: ImageVector?, title: String, caption: String = "", trailing: @Composable (RowScope.() -> Unit)? = null,
    accent: Boolean = false, enabled: Boolean = true, onClick: () -> Unit,
) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.touch).clip(Corner.m)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.s, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        if (glyph != null) {
            Box(Modifier.size(Space.xl).clip(Corner.m).background(if (accent) t.psel else t.surf), contentAlignment = Alignment.Center) {
                Icon(glyph, null, Modifier.size(Space.l), tint = if (enabled) (if (accent) t.accent else t.fg) else t.line)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.label, color = if (enabled) t.fg else t.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (caption.isNotEmpty()) Text(caption, style = Type.small, color = t.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke(this)
    }
}

/**
 * How far through, as a line: the accent up to here, a hint of it over what's
 * made ahead, the track (φ⁻²) after, with a notch where each chapter starts.
 * Tap it to jump there ([onSeek] gets the share of the way across).
 */
@Composable
fun Track(progress: Float, modifier: Modifier = Modifier, made: Float = 0f, marks: List<Float> = emptyList(),
          height: Dp = 3.dp, onSeek: ((Float) -> Unit)? = null) {
    val t = LocalTokens.current
    val touch = if (onSeek != null) Modifier.height(Space.l).pointerInput(onSeek) {
        detectTapGestures { onSeek(((it.x) / size.width).coerceIn(0f, 1f)) }
    } else Modifier.height(height)
    Canvas(modifier.fillMaxWidth().then(touch).semantics { contentDescription = "${(progress * 100).toInt()}% through" }) {
        val y = size.height / 2
        val w = height.toPx()
        drawLine(t.line, Offset(0f, y), Offset(size.width, y), w, StrokeCap.Round)
        if (made > progress) drawLine(Tokens.mix(t.line, t.accent, Phi.inv(2f)), Offset(0f, y),
            Offset(size.width * made.coerceIn(0f, 1f), y), w, StrokeCap.Round)
        for (m in marks) {
            val x = m * size.width
            drawLine(t.bg, Offset(x, y - w), Offset(x, y + w), 2.dp.toPx())
        }
        if (progress > 0f) drawLine(t.accent, Offset(0f, y), Offset(size.width * progress.coerceIn(0f, 1f), y), w, StrokeCap.Round)
    }
}

/** A round transport button; the main one (play or pause) is φ times the others. [busy] spins in place of its glyph. */
@Composable
fun RoundButton(glyph: ImageVector, label: String, size: Dp, primary: Boolean = false, enabled: Boolean = true,
                busy: Boolean = false, onClick: () -> Unit) {
    val t = LocalTokens.current
    val ink = if (primary) t.bg else if (enabled) t.fg else t.line
    Box(
        Modifier.size(size).clip(CircleShape).background(if (primary) t.accent else t.surf)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(size * Phi.inv(2f)), color = ink, strokeWidth = 3.dp,
            trackColor = Color.Transparent)
        else Icon(glyph, null, Modifier.size(size * Phi.inv(2f) + 3.dp), tint = ink)
    }
}

/** An icon every phone uses (back, settings, more), alone, on a thumb-sized target. */
@Composable
fun GlyphButton(glyph: ImageVector, label: String, tint: Color = LocalTokens.current.dim, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(glyph, null, Modifier.size(Space.l + 3.dp), tint = tint) }
}

/** A glyph above a word or a value: the reader's quieter controls. [on] lights it in the second accent. */
@Composable
fun QuietButton(glyph: ImageVector, label: String, on: Boolean = false, enabled: Boolean = true,
                description: String = label, onClick: () -> Unit) {
    val t = LocalTokens.current
    val colour = if (!enabled) t.line else if (on) t.accent2 else t.dim
    Column(
        Modifier.widthIn(min = Space.touch).heightIn(min = Space.touch).clip(Corner.m)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(Space.xs)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(glyph, null, Modifier.size(Space.l), tint = colour)
        Text(label, style = Type.tiny, color = colour, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** A way in: its glyph over its name, on a surface; a row of them shares the width. */
@Composable
fun Tile(glyph: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val t = LocalTokens.current
    Column(
        modifier.clip(Corner.l).background(t.surf).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = Space.m, horizontal = Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Icon(glyph, null, Modifier.size(Space.l + 3.dp), tint = if (enabled) t.accent else t.line)
        Text(label, style = Type.small, color = if (enabled) t.fg else t.line, textAlign = TextAlign.Center, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** A setting that shows its value: a glyph and the value, on a pill; tap to change it. */
@Composable
fun Pill(glyph: ImageVector, value: String, description: String = value, onClick: () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.heightIn(min = 48.dp).clip(CircleShape).background(t.psel).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.m).semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Icon(glyph, null, Modifier.size(Space.l - 3.dp), tint = t.accent)
        Text(value, style = Type.label, color = t.fg, maxLines = 1)
    }
}

/**
 * A book's tile: a golden rectangle (1 : φ) in the logo's colours, violet
 * towards cyan, with the title's first letter. The same book always gets
 * the same colours.
 */
@Composable
fun Cover(title: String, width: Dp = Space.xl) {
    val t = LocalTokens.current
    val shift = (title.hashCode().toUInt() % 1000u).toFloat() / 1000f
    val top = Tokens.mix(t.accent, t.cyan, shift * Phi.inv(1f))
    val bottom = Tokens.mix(t.accent, t.cyan, Phi.inv(2f) + shift * Phi.inv(1f))
    Box(
        Modifier.width(width).height(width * Phi.PHI).clip(Corner.s).background(Brush.verticalGradient(listOf(top, bottom))),
        contentAlignment = Alignment.Center,
    ) {
        Text(title.trim().firstOrNull()?.uppercase() ?: "·", style = Type.title, color = t.bg)
    }
}

/**
 * kokomux's mark: five bars in golden steps (13, 21, 34, 21, 13), violet to
 * cyan, as the icon. [live] sets them moving like a voice: something is
 * loading, being opened, or speaking.
 */
@Composable
fun Logo(height: Dp = Space.xl, live: Boolean = false) {
    val t = LocalTokens.current
    val phase = if (live) rememberInfiniteTransition(label = "logo")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1597, easing = LinearEasing)), label = "phase").value else -1f
    Canvas(Modifier.size(width = height * 1.4f, height = height)) {
        val heights = listOf(13f, 21f, 34f, 21f, 13f)
        val bar = size.width / 9f
        heights.forEachIndexed { i, h ->
            val swing = if (phase < 0f) 1f
                else Phi.inv(1f) + (1f - Phi.inv(1f)) * abs(sin((phase + i * Phi.inv(2f)) * PI.toFloat() * 2f))
            val x = bar * (2 * i) + bar / 2
            val hh = max(bar, size.height * h / 34f * swing)
            drawLine(Tokens.mix(t.accent, t.cyan, i / 4f), Offset(x, (size.height - hh) / 2), Offset(x, (size.height + hh) / 2),
                bar, StrokeCap.Round)
        }
    }
}

/** A quiet line of secondary text. */
@Composable
fun Dim(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = Type.small, maxLines: Int = Int.MAX_VALUE) =
    Text(text, modifier, style = style, color = LocalTokens.current.dim, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

/** Something went wrong: what, in the palette's red, and a way to dismiss it. */
@Composable
fun ErrorLine(text: String, onDismiss: () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().clip(Corner.m).background(Tokens.mix(t.bg, t.red, 0.146f)).clickable(onClick = onDismiss)
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text(text, style = Type.small, color = t.fg, modifier = Modifier.weight(1f))
        Icon(Glyphs.close, "Dismiss", Modifier.size(Space.m + 3.dp), tint = t.dim)
    }
}

/** "2 h 14 min left", "9 min left". */
fun timeLeftLabel(seconds: Double): String {
    val minutes = ceil(seconds / 60).toInt().coerceAtLeast(1)
    return when {
        minutes < 60 -> "$minutes min left"
        minutes % 60 == 0 -> "${minutes / 60} h left"
        else -> "${minutes / 60} h ${minutes % 60} min left"
    }
}

/** Speeds are shown as Termux shows them: "1.0×", "1.25×". */
fun speedLabel(speed: Float): String {
    val tenths = Math.round(speed * 100) / 100f
    return (if (tenths * 10 % 1f == 0f) "%.1f" else "%.2f").format(tenths) + "×"
}
