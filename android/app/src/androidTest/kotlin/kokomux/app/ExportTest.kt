package kokomux.app

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kokomux.engine.Audio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** Saving as an audiobook: parts' WAV files, one after the other, as AAC in an .m4a the phone can play. */
@RunWith(AndroidJUnit4::class)
class ExportTest {
    @Test
    fun partsBecomeOneM4aOfTheirLength() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val wavs = listOf(1.5, 2.25).mapIndexed { i, secs ->
            File(dir, "part$i.wav").also { f ->
                Audio.writeWav(f, FloatArray((secs * 24000).toInt()) { (0.3 * sin(2 * PI * 220 * it / 24000)).toFloat() }, 24000)
            }
        }
        val out = File(dir, "book.m4a").apply { delete() }
        java.io.RandomAccessFile(out, "rw").use { Export.encode(wavs, it.fd) }
        val ex = MediaExtractor().apply { setDataSource(out.path) }
        val format = ex.getTrackFormat(0)
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME))
        assertEquals(24000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        val secs = format.getLong(MediaFormat.KEY_DURATION) / 1e6
        assertTrue("$secs s", secs in 3.6..3.9)
        ex.release()
    }
}
