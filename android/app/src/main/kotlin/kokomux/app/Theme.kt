package kokomux.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * The Termux version's palette (src/theme.sh): the dark and light sets, and
 * greys that are the background moved part of the way towards the text in
 * golden-ratio steps (φ⁻¹ secondary text, φ⁻² rules, φ⁻⁴ surfaces).
 */
object Palette {
    private data class Set(val bg: Long, val fg: Long, val violet: Long, val sky: Long, val green: Long, val red: Long)
    private val DARK = Set(0xFF16151D, 0xFFE8E6F0, 0xFFB69CFF, 0xFF7CD0F5, 0xFF86D39A, 0xFFFF7A7A)
    private val LIGHT = Set(0xFFFBFAF8, 0xFF1C1B22, 0xFF6438C9, 0xFF0B67A0, 0xFF21743A, 0xFFC0262D)

    fun scheme(dark: Boolean): ColorScheme {
        val s = if (dark) DARK else LIGHT
        val bg = Color(s.bg)
        val fg = Color(s.fg)
        fun grey(step: Float) = lerp(bg, fg, step)
        val base = if (dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = Color(s.violet), onPrimary = bg,
            secondary = Color(s.sky), onSecondary = bg,
            tertiary = Color(s.green),
            error = Color(s.red),
            background = bg, onBackground = fg,
            surface = bg, onSurface = fg,
            surfaceVariant = grey(0.146f), onSurfaceVariant = grey(0.618f),
            surfaceContainer = grey(0.09f), surfaceContainerHigh = grey(0.146f),
            surfaceContainerHighest = grey(0.236f),
            outline = grey(0.382f), outlineVariant = grey(0.236f),
        )
    }
}

/** Spacing in golden steps (Fibonacci dp). */
object Space {
    val s = 8.dp
    val m = 13.dp
    val l = 21.dp
    val xl = 34.dp
}

@Composable
fun KokomuxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Palette.scheme(isSystemInDarkTheme()), content = content)
}
