package kokomux.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.pow

/**
 * The design system, one golden ratio throughout, as the Termux screens have
 * it (src/theme.sh, src/tui.sh):
 *
 *  - colour: theme.sh's dark and light palettes; every grey is the background
 *    moved part of the way to the text, in golden steps (φ⁻¹ secondary text,
 *    ½ what's been heard, φ⁻² rules, φ⁻³ texture, φ⁻⁴ surfaces)
 *  - space: Fibonacci dp (each step φ times the one before)
 *  - type: √φ steps from 16 sp; reading text has a line height of φ
 *  - layout: the line being read sits φ⁻² down the reading area; free space
 *    is split 1 : φ above and below (the optical centre); the reading column
 *    is 610 dp wide at most
 *  - motion: Fibonacci milliseconds
 */
object Phi {
    const val PHI = 1.6180339887f
    /** φ⁻ⁿ */
    fun inv(n: Float): Float = PHI.pow(-n)
}

/** Spacing in Fibonacci dp. */
object Space {
    val xxs = 3.dp
    val xs = 5.dp
    val s = 8.dp
    val m = 13.dp
    val l = 21.dp
    val xl = 34.dp
    val xxl = 55.dp
    val xxxl = 89.dp
    /** The reading column's widest. */
    val column = 610.dp
    /** The smallest a touch target gets (Android's 48, on the Fibonacci side). */
    val touch = 55.dp
}

/** Durations in Fibonacci milliseconds. */
object Motion {
    const val QUICK = 144
    const val MEDIUM = 233
    const val SLOW = 377
    /** The part just heard eases out of focus (tui.sh's TUI_EASE_MS). */
    const val EASE_OUT = 382
}

/** Corners in Fibonacci dp. */
object Corner {
    val s = RoundedCornerShape(5.dp)
    val m = RoundedCornerShape(8.dp)
    val l = RoundedCornerShape(13.dp)
    val xl = RoundedCornerShape(21.dp)
}

/** theme.sh's colours for one background (dark or light), with its golden greys. */
@Immutable
class Tokens(
    val dark: Boolean,
    val bg: Color, val fg: Color,
    val accent: Color, val accent2: Color, val green: Color, val red: Color, val amber: Color, val cyan: Color,
) {
    /** Secondary text: hints, labels, what's still to be read (φ⁻¹). */
    val dim = mix(bg, fg, 0.618f)
    /** What's been heard: it recedes, but stays legible (½). */
    val past = mix(bg, fg, 0.5f)
    /** Rules and tracks (φ⁻²). */
    val line = mix(bg, fg, 0.382f)
    /** Texture (φ⁻³). */
    val ghost = mix(bg, fg, 0.236f)
    /** Buttons and panels (φ⁻⁴). */
    val surf = mix(bg, fg, 0.146f)
    private val tint = mix(fg, accent, 0.618f)
    /** A selected row (φ⁻⁴ of the accent-tinted text), and one on a panel (φ⁻³). */
    val sel = mix(bg, tint, 0.146f)
    val psel = mix(bg, tint, 0.236f)
    /** The word being spoken sits on the background moved φ⁻² towards the accent. */
    val hl = mix(bg, accent, 0.382f)

    /** The text colour faded to [level] of the way from the background (the focus effect). */
    fun tone(level: Float) = mix(bg, fg, level.coerceIn(0f, 1f))

    companion object {
        /** theme.sh's mixing: per channel, in sRGB. */
        fun mix(a: Color, b: Color, t: Float) =
            Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t)

        val DARK = Tokens(true, Color(0xFF16151D), Color(0xFFE8E6F0), Color(0xFFB69CFF), Color(0xFF7CD0F5),
            Color(0xFF86D39A), Color(0xFFFF7A7A), Color(0xFFEFC764), Color(0xFF9FF0EC))
        val LIGHT = Tokens(false, Color(0xFFFBFAF8), Color(0xFF1C1B22), Color(0xFF6438C9), Color(0xFF0B67A0),
            Color(0xFF21743A), Color(0xFFC0262D), Color(0xFF855300), Color(0xFF0F7C86))
    }
}

/**
 * The focus effect's falloff: φ^(−i/4), a quarter golden step at a time
 * (theme.sh's THEME_DECAY), never fainter than φ⁻⁴ of the way to the text.
 */
object Focus {
    const val FLOOR = 0.146f
    fun decay(quarterSteps: Float): Float = Phi.PHI.pow(-quarterSteps / 4f)
    /** The tone [steps] quarter golden steps from [near]. */
    fun tone(near: Float, steps: Float) = FLOOR + (near - FLOOR) * decay(steps)
}

/** Type in √φ steps (×1.272) from 16 sp; reading text with a line height of φ. */
object Type {
    val tiny = TextStyle(fontSize = 10.sp, lineHeight = 16.sp)
    val small = TextStyle(fontSize = 13.sp, lineHeight = 21.sp)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 26.sp)
    val label = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
    val title = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
    val headline = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
    val display = TextStyle(fontSize = 33.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold)
    /** The reading column: a book face, 20 sp on 32 sp lines (φ). */
    val reading = TextStyle(fontSize = 20.sp, lineHeight = 32.sp, fontFamily = FontFamily.Serif)
}

val LocalTokens = staticCompositionLocalOf { Tokens.DARK }

/** Colours: auto (follow the phone), dark or light, as KOKORO_THEME. */
enum class ThemeMode { AUTO, DARK, LIGHT }

/** The app's theme: the tokens, and Material's components dressed in them. */
@Composable
fun KokomuxTheme(mode: ThemeMode = ThemeMode.AUTO, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.AUTO -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val t = if (dark) Tokens.DARK else Tokens.LIGHT
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = t.accent, onPrimary = t.bg, primaryContainer = t.psel, onPrimaryContainer = t.fg,
        secondary = t.accent2, onSecondary = t.bg, secondaryContainer = t.sel, onSecondaryContainer = t.fg,
        tertiary = t.green, error = t.red,
        background = t.bg, onBackground = t.fg, surface = t.bg, onSurface = t.fg,
        surfaceVariant = t.surf, onSurfaceVariant = t.dim,
        surfaceContainerLowest = t.bg, surfaceContainerLow = Tokens.mix(t.bg, t.fg, 0.056f),
        surfaceContainer = Tokens.mix(t.bg, t.fg, 0.09f), surfaceContainerHigh = t.surf,
        surfaceContainerHighest = t.ghost, surfaceBright = t.surf, surfaceDim = t.bg,
        outline = t.line, outlineVariant = t.ghost, scrim = Color.Black,
    )
    val typography = Typography(
        displaySmall = Type.display, headlineSmall = Type.headline, titleLarge = Type.title,
        titleMedium = Type.label, titleSmall = Type.small.copy(fontWeight = FontWeight.Medium),
        bodyLarge = Type.body, bodyMedium = Type.body, bodySmall = Type.small,
        labelLarge = Type.label, labelMedium = Type.small, labelSmall = Type.tiny,
    )
    val shapes = Shapes(extraSmall = Corner.s, small = Corner.m, medium = Corner.l, large = Corner.xl, extraLarge = Corner.xl)
    CompositionLocalProvider(LocalTokens provides t) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
    }
}
