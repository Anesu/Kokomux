package kokomux.app.ui

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
import kotlin.math.max

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

/** A section's name, quiet, with what's in it on the right. */
@Composable
fun SectionTitle(text: String, aside: String = "") {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().padding(top = Space.l, bottom = Space.s), verticalAlignment = Alignment.Bottom) {
        Text(text, style = Type.label, color = t.fg, modifier = Modifier.weight(1f))
        if (aside.isNotEmpty()) Text(aside, style = Type.small, color = t.dim)
    }
}

/** One thing to choose: a glyph, what it is, and a line saying more; the whole row is the target. */
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

/** A round transport button; the main one (play or pause) is φ times the others. */
@Composable
fun RoundButton(glyph: ImageVector, label: String, size: Dp, primary: Boolean = false, enabled: Boolean = true,
                onClick: () -> Unit) {
    val t = LocalTokens.current
    Box(
        Modifier.size(size).clip(CircleShape).background(if (primary) t.accent else t.surf)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(glyph, null, Modifier.size(size * Phi.inv(2f) + 3.dp), tint = if (primary) t.bg else if (enabled) t.fg else t.line)
    }
}

/** A glyph above a word: the reader's quieter controls. */
@Composable
fun QuietButton(glyph: ImageVector, label: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val t = LocalTokens.current
    val colour = if (!enabled) t.line else if (on) t.accent2 else t.dim
    Column(
        Modifier.widthIn(min = Space.touch).heightIn(min = Space.touch).clip(Corner.m)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(glyph, null, Modifier.size(Space.l), tint = colour)
        Text(label, style = Type.tiny, color = colour, textAlign = TextAlign.Center, maxLines = 1)
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

/** kokomux's mark: five bars in golden steps (13, 21, 34, 21, 13), violet to cyan, as the icon. */
@Composable
fun Logo(height: Dp = Space.xl) {
    val t = LocalTokens.current
    Canvas(Modifier.size(width = height * 1.4f, height = height)) {
        val heights = listOf(13f, 21f, 34f, 21f, 13f)
        val bar = size.width / 9f
        heights.forEachIndexed { i, h ->
            val x = bar * (2 * i) + bar / 2
            val hh = size.height * h / 34f
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

/** Speeds are shown as Termux shows them: "1.0×", "1.25×". */
fun speedLabel(speed: Float): String {
    val tenths = Math.round(speed * 100) / 100f
    return (if (tenths * 10 % 1f == 0f) "%.1f" else "%.2f").format(tenths) + "×"
}
