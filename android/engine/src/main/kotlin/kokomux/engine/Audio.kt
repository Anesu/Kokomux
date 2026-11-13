package kokomux.engine

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Sound arrays: trimming silence, softening edges, pauses and WAV files. */
object Audio {
    const val RATE = 24000

    /**
     * Where the sound in [y] starts and ends, as librosa's trim (kokoro-onnx's
     * copy) finds it: 2048-sample frames every 512, centred, and anything 60 dB
     * below the loudest frame is silence. Returns (start, end); (0, 0) if all silent.
     */
    fun soundSpan(y: FloatArray, topDb: Double = 60.0, frame: Int = 2048, hop: Int = 512): Pair<Int, Int> {
        val pad = frame / 2
        val frames = 1 + y.size / hop                      // 1 + (len + 2·pad − frame) / hop
        val rms = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0.0
            val from = f * hop - pad
            for (i in max(0, from) until min(y.size, from + frame)) sum += y[i].toDouble() * y[i]
            rms[f] = sqrt(sum / frame).toFloat()
        }
        val ref = rms.max().toDouble()
        val refDb = 10 * log10(max(1e-10, ref * ref))
        var first = -1
        var last = -1
        for (f in 0 until frames) {
            val db = 10 * log10(max(1e-10, rms[f].toDouble() * rms[f])) - refDb
            if (db > -topDb) {
                if (first < 0) first = f
                last = f
            }
        }
        if (first < 0) return 0 to 0
        return first * hop to min(y.size, (last + 1) * hop)
    }

    /** Soften the first 4 ms and last 12 ms, so a trimmed edge can't click (engine.py's edges). */
    fun softenEdges(samples: FloatArray, rate: Int = RATE): FloatArray {
        val out = samples.copyOf()
        val head = min(out.size / 4, (rate * 0.004).toInt())
        val tail = min(out.size / 4, (rate * 0.012).toInt())
        for (i in 0 until head) out[i] *= linspace(i, head, up = true)
        for (i in 0 until tail) out[out.size - tail + i] *= linspace(i, tail, up = false)
        return out
    }

    /** numpy's linspace(0, 1, n)[i] (or linspace(1, 0, n)[i]) as float32. */
    private fun linspace(i: Int, n: Int, up: Boolean): Float {
        if (n == 1) return if (up) 0f else 1f
        val t = i.toDouble() / (n - 1)
        return (if (up) t else 1 - t).toFloat()
    }

    /** [seconds] of silence. */
    fun silence(seconds: Double, rate: Int = RATE) = FloatArray(max(0, (seconds * rate).toInt()))

    /** Arrays end to end. */
    fun join(parts: List<FloatArray>): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }

    /** Save as a 16-bit mono WAV, as soundfile does by default. */
    fun writeWav(file: File, samples: FloatArray, rate: Int = RATE) {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * 32767f).roundEven().toInt().toShort())
        val head = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.capacity()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.capacity())
        }
        val tmp = File(file.path + ".tmp")
        RandomAccessFile(tmp, "rw").use { f ->
            f.setLength(0)
            f.write(head.array())
            f.write(data.array())
        }
        if (!tmp.renameTo(file)) error("Couldn't save $file")
    }

    private fun Float.roundEven(): Float = Math.rint(this.toDouble()).toFloat()

    /** Seconds of audio in [samples]. */
    fun seconds(samples: Int, rate: Int = RATE) = samples.toDouble() / rate
}
