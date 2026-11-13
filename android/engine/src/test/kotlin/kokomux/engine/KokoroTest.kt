package kokomux.engine

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The Kotlin engine against kokoro-onnx: the same sounds through the same model
 * give speech of the same length, with each sound at the same moment and the
 * same loudness, sentence by sentence (tools/golden.json, from engine.py).
 */
class KokoroTest {
    companion object {
        private lateinit var kokoro: Kokoro
        private lateinit var golden: JSONArray

        @BeforeClass
        @JvmStatic
        fun load() {
            val models = File(System.getProperty("kokomux.models"))
            val model = File(models, "kokoro-v1.0-timestamped.fp32.onnx")
            assumeTrue("no models in $models (dev/rig.sh makes them)", model.exists())
            kokoro = Kokoro(model, File(models, "voices-v1.0.bin"))
            golden = JSONArray(File(System.getProperty("kokomux.golden")).readText())
        }
    }

    private fun envelope(samples: FloatArray, step: Int = 2400) = (samples.indices step step).map { i ->
        val end = minOf(samples.size, i + step)
        sqrt((i until end).sumOf { samples[it].toDouble() * samples[it] } / (end - i))
    }

    private fun correlation(a: List<Double>, b: List<Double>): Double {
        val n = minOf(a.size, b.size)
        val ma = a.take(n).average()
        val mb = b.take(n).average()
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in 0 until n) {
            ab += (a[i] - ma) * (b[i] - mb); aa += (a[i] - ma) * (a[i] - ma); bb += (b[i] - mb) * (b[i] - mb)
        }
        return ab / sqrt(aa * bb)
    }

    @Test
    fun speechMatchesKokoroOnnx() {
        for (i in 0 until golden.length()) {
            val case = golden.getJSONObject(i)
            val speech = kokoro.speak(case.getString("phonemes"), case.getString("voice"), case.getDouble("speed").toFloat())
            val want = case.getInt("samples")
            val label = "${case.getString("voice")}: ${case.getString("text").take(40)}"
            println("$label: ${speech.samples.size} samples, kokoro-onnx made $want")
            // Silence is trimmed in 512-sample steps and pauses spliced in 10 ms frames,
            // so a frame right on a threshold may fall the other way: allow one step.
            assertTrue("$label: ${speech.samples.size} samples, want $want", abs(speech.samples.size - want) <= 512)

            val times = case.getJSONArray("timings")
            assertEquals("$label: sounds timed", times.length(), speech.timings.size)
            val off = speech.timings.indices.map { k ->
                val t = times.getJSONArray(k)
                assertEquals(t.getString(0)[0], speech.timings[k].phoneme)
                abs(t.getDouble(1) - speech.timings[k].start)
            }
            val late = off.count { it > 0.025 }
            println("$label: sounds within 25 ms: ${off.size - late}/${off.size}, worst ${"%.4f".format(off.max())} s")
            assertTrue("$label: $late sounds off by more than 25 ms", late <= off.size / 50)

            val loud = (0 until case.getJSONArray("envelope").length()).map { case.getJSONArray("envelope").getDouble(it) }
            val r = correlation(loud, envelope(speech.samples))
            println("$label: loudness correlation ${"%.4f".format(r)}")
            assertTrue("$label: loudness correlation $r", r > 0.99)
        }
    }

    @Test
    fun longSoundsAreSplitIntoBalancedBatches() {
        val words = List(120) { "hˈɛlO" }.joinToString(" ") + "."
        val batches = Chunker.split(words)
        assertEquals(2, batches.size)
        assertTrue(batches.all { it.length <= Kokoro.MAX_SOUNDS })
        assertTrue(abs(batches[0].length - batches[1].length) <= 6)
    }
}
