package kokomux.engine

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** When a sound is spoken, in seconds from the start of the part. */
data class Timing(val phoneme: Char, val start: Double, val end: Double)

/**
 * Silence after sentence and clause marks, placed with the model's timings
 * (kokoro-onnx 0.6.1's pauses.py): the gap Kokoro left after a mark is topped
 * up to what the text asks for, by splicing silence into the middle of it.
 */
internal object Pauses {
    private const val QUIET_DB = -40.0
    private const val FRAME = 0.01
    private const val REACH = 0.15

    private fun wantedAfter(phoneme: Char, sentence: Double, clause: Double) = when (phoneme) {
        in Chunker.SENTENCE_MARKS -> sentence
        in Chunker.CLAUSE_MARKS -> clause
        else -> 0.0
    }

    /** Which 10 ms frames are quiet enough to be part of a pause. */
    private fun quietFrames(audio: FloatArray, frame: Int): BooleanArray {
        val n = audio.size / frame
        val loud = FloatArray(n) { f ->
            var sum = 0.0
            for (i in f * frame until (f + 1) * frame) sum += audio[i].toDouble() * audio[i]
            sqrt(sum / frame).toFloat()
        }
        val limit = (loud.maxOrNull() ?: 0f) * 10.0.pow(QUIET_DB / 20)
        return BooleanArray(n) { loud[it] <= limit }
    }

    /** The quiet run touching frame [at], as (start, length); length 0 if none within [reach]. */
    private fun runAround(quiet: BooleanArray, at: Int, reach: Int): Pair<Int, Int> {
        val inside = (at - reach..at + reach)
            .sortedBy { abs(it - at) }
            .firstOrNull { it in quiet.indices && quiet[it] } ?: return at to 0
        var start = inside
        while (start > 0 && quiet[start - 1]) start--
        var end = inside
        while (end + 1 < quiet.size && quiet[end + 1]) end++
        return start to end - start + 1
    }

    /** Lengthen the pause after every mark, moving later timings along. */
    fun insert(audio: FloatArray, timings: List<Timing>, rate: Int, sentence: Double, clause: Double):
        Pair<FloatArray, List<Timing>> {
        if (timings.isEmpty() || (sentence == 0.0 && clause == 0.0)) return audio to timings
        val frame = maxOf(1, (FRAME * rate).toInt())
        val quiet = quietFrames(audio, frame)
        val reach = (REACH / FRAME).toInt()
        val parts = mutableListOf<FloatArray>()
        val moved = mutableListOf<Timing>()
        var cut = 0
        var shift = 0.0
        for (t in timings) {
            moved += Timing(t.phoneme, t.start + shift, t.end + shift)
            val target = wantedAfter(t.phoneme, sentence, clause)
            if (target == 0.0) continue
            val at = (t.end * rate).toInt() / frame
            val (start, length) = runAround(quiet, at, reach)
            val missing = target - length * FRAME
            if (length == 0 || missing <= 0) continue
            val middle = (start + length / 2) * frame
            parts += if (middle > cut) audio.copyOfRange(cut, middle) else FloatArray(0)
            parts += FloatArray((missing * rate).toInt())
            cut = middle
            shift += missing
        }
        if (parts.isEmpty()) return audio to timings
        parts += audio.copyOfRange(cut, audio.size)
        return Audio.join(parts) to moved
    }
}
