package kokomux.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The app's icons, drawn on a 24-unit grid with one line weight so they read
 * as a family (Icon tints them). The ones every phone uses (play, pause,
 * previous, next, back, settings, more) stand alone; the rest stand beside
 * their word or value.
 */
object Glyphs {
    private fun glyph(name: String, filled: Boolean = false, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = if (filled) null else SolidColor(Color.Black),
                strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                pathBuilder = draw,
            )
        }.build()

    val play = glyph("play", filled = true) { moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close() }
    val pause = glyph("pause", filled = true) {
        moveTo(7f, 5f); lineTo(10.5f, 5f); lineTo(10.5f, 19f); lineTo(7f, 19f); close()
        moveTo(13.5f, 5f); lineTo(17f, 5f); lineTo(17f, 19f); lineTo(13.5f, 19f); close()
    }
    val next = glyph("next", filled = true) {
        moveTo(5f, 6f); lineTo(14.5f, 12f); lineTo(5f, 18f); close()
        moveTo(16f, 6f); lineTo(18.5f, 6f); lineTo(18.5f, 18f); lineTo(16f, 18f); close()
    }
    val previous = glyph("previous", filled = true) {
        moveTo(19f, 6f); lineTo(9.5f, 12f); lineTo(19f, 18f); close()
        moveTo(8f, 6f); lineTo(5.5f, 6f); lineTo(5.5f, 18f); lineTo(8f, 18f); close()
    }
    val stop = glyph("stop", filled = true) { moveTo(7f, 7f); lineTo(17f, 7f); lineTo(17f, 17f); lineTo(7f, 17f); close() }
    val moon = glyph("sleep", filled = true) {
        moveTo(19.5f, 14.5f); arcTo(8f, 8f, 0f, true, true, 9.5f, 4.5f)
        arcTo(6.5f, 6.5f, 0f, false, false, 19.5f, 14.5f); close()
    }
    /** Lines with a dot before each: the chapters. */
    val chapters = glyph("chapters") {
        moveTo(9f, 7f); lineTo(20f, 7f); moveTo(9f, 12f); lineTo(20f, 12f); moveTo(9f, 17f); lineTo(20f, 17f)
        moveTo(4.5f, 7f); lineTo(4.6f, 7f); moveTo(4.5f, 12f); lineTo(4.6f, 12f); moveTo(4.5f, 17f); lineTo(4.6f, 17f)
    }
    val back = glyph("back") { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) }
    /** Down: the reader folds away to the home screen, still playing. */
    val down = glyph("down") { moveTo(5f, 9f); lineTo(12f, 16f); lineTo(19f, 9f) }
    val forward = glyph("forward") { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }
    val close = glyph("close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    /** A gear: settings. */
    val settings = glyph("settings") {
        val teeth = 8
        for (k in 0 until teeth) {
            val a = Math.toRadians(k * 360.0 / teeth)
            val (c, s) = Math.cos(a).toFloat() to Math.sin(a).toFloat()
            moveTo(12f + 6.5f * c, 12f + 6.5f * s); lineTo(12f + 9f * c, 12f + 9f * s)
        }
        moveTo(18.5f, 12f); arcTo(6.5f, 6.5f, 0f, true, true, 5.5f, 12f); arcTo(6.5f, 6.5f, 0f, true, true, 18.5f, 12f)
        moveTo(15f, 12f); arcTo(3f, 3f, 0f, true, true, 9f, 12f); arcTo(3f, 3f, 0f, true, true, 15f, 12f)
    }
    /** A dial with its needle past the middle: speed. */
    val speed = glyph("speed") {
        moveTo(4f, 17f); arcTo(8f, 8f, 0f, true, true, 20f, 17f)
        moveTo(12f, 15f); lineTo(16.5f, 9.5f)
    }
    /** A page with its corner folded: a book or a file. */
    val file = glyph("file") {
        moveTo(6f, 3.5f); lineTo(14.5f, 3.5f); lineTo(18.5f, 7.5f); lineTo(18.5f, 20.5f); lineTo(6f, 20.5f); close()
        moveTo(14.5f, 3.5f); lineTo(14.5f, 7.5f); lineTo(18.5f, 7.5f)
        moveTo(9f, 12f); lineTo(15.5f, 12f); moveTo(9f, 16f); lineTo(15.5f, 16f)
    }
    /** The world: a web page. */
    val globe = glyph("web page") {
        moveTo(20f, 12f); arcTo(8f, 8f, 0f, true, true, 4f, 12f); arcTo(8f, 8f, 0f, true, true, 20f, 12f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(12f, 4f); curveTo(8.5f, 7.5f, 8.5f, 16.5f, 12f, 20f); curveTo(15.5f, 16.5f, 15.5f, 7.5f, 12f, 4f)
    }
    val paste = glyph("paste") {
        moveTo(8f, 5f); lineTo(6f, 5f); lineTo(6f, 20.5f); lineTo(18f, 20.5f); lineTo(18f, 5f); lineTo(16f, 5f)
        moveTo(9f, 3.5f); lineTo(15f, 3.5f); lineTo(15f, 6.5f); lineTo(9f, 6.5f); close()
        moveTo(9f, 11f); lineTo(15f, 11f); moveTo(9f, 15f); lineTo(13f, 15f)
    }
    val type = glyph("type") {
        moveTo(4f, 20f); lineTo(5f, 15.5f); lineTo(15.5f, 5f); lineTo(19f, 8.5f); lineTo(8.5f, 19f); close()
        moveTo(13f, 7.5f); lineTo(16.5f, 11f)
    }
    val mic = glyph("microphone") {
        moveTo(9f, 6f); arcTo(3f, 3f, 0f, false, true, 15f, 6f); lineTo(15f, 11f)
        arcTo(3f, 3f, 0f, false, true, 9f, 11f); close()
        moveTo(5.5f, 11f); arcTo(6.5f, 6.5f, 0f, false, false, 18.5f, 11f); moveTo(12f, 17.5f); lineTo(12f, 21f)
    }
    val download = glyph("download") {
        moveTo(12f, 4f); lineTo(12f, 15f); moveTo(7f, 10.5f); lineTo(12f, 15.5f); lineTo(17f, 10.5f)
        moveTo(5f, 19.5f); lineTo(19f, 19.5f)
    }
    val trash = glyph("remove") {
        moveTo(5f, 7f); lineTo(19f, 7f); moveTo(10f, 7f); lineTo(10f, 4.5f); lineTo(14f, 4.5f); lineTo(14f, 7f)
        moveTo(7f, 7f); lineTo(8f, 19.5f); lineTo(16f, 19.5f); lineTo(17f, 7f)
    }
    val check = glyph("check") { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) }
    val plus = glyph("plus") { moveTo(6f, 12f); lineTo(18f, 12f); moveTo(12f, 6f); lineTo(12f, 18f) }
    val minus = glyph("minus") { moveTo(6f, 12f); lineTo(18f, 12f) }
    /** A speaker: hear a voice. */
    val hear = glyph("hear") {
        moveTo(4.5f, 9.5f); lineTo(8f, 9.5f); lineTo(12f, 5.5f); lineTo(12f, 18.5f); lineTo(8f, 14.5f); lineTo(4.5f, 14.5f); close()
        moveTo(15.5f, 9f); curveTo(17.2f, 10.7f, 17.2f, 13.3f, 15.5f, 15f)
        moveTo(18f, 6.5f); curveTo(21.3f, 9.8f, 21.3f, 14.2f, 18f, 17.5f)
    }
    val more = glyph("more", filled = true) {
        for (y in listOf(6f, 12f, 18f)) {
            moveTo(13.6f, y); arcTo(1.6f, 1.6f, 0f, true, true, 10.4f, y); arcTo(1.6f, 1.6f, 0f, true, true, 13.6f, y); close()
        }
    }
}
