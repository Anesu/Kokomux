package kokomux.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** A part's speech: its samples at [Audio.RATE], and when each sound is spoken. */
class Speech(val samples: FloatArray, val timings: List<Timing>) {
    val seconds get() = Audio.seconds(samples.size)
}

/**
 * Kokoro 82M v1.0 (the fp32 timestamped export) on ONNX Runtime's CPU, turning
 * a part's sounds into speech exactly as engine.py has kokoro-onnx 0.6.1 do it:
 * create_timed(sounds, is_phonemes=True), with the model's durations, silence
 * trimmed from each batch and the pauses after marks topped up.
 *
 * The same code runs on the phone (onnxruntime-android) and in the JVM tests
 * (onnxruntime), which share the ai.onnxruntime API.
 */
class Kokoro(model: File, voices: File, threads: Int = 0, block: Int = 0) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    // As engine.py's load_kokoro: threads 0 is onnxruntime's own choice; block > 0 spreads
    // the work across cores of different speeds (kokoro --benchmark tries both).
    private val session: OrtSession = OrtSession.SessionOptions().use { so ->
        so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        so.setInterOpNumThreads(1)
        if (threads > 0) so.setIntraOpNumThreads(threads)
        if (block > 0) so.addConfigEntry("session.dynamic_block_base", block.toString())
        env.createSession(model.path, so)
    }
    val voices: Voices = Voices.read(voices)

    /** Speech for a part's sounds (misaki's phonemes) in [voice] at [speed] (0.5 to 2). */
    fun speak(phonemes: String, voice: String, speed: Float = 1f): Speech {
        require(speed in 0.5f..2f) { "Speed should be between 0.5 and 2.0, got $speed" }
        val batches = Chunker.split(pythonWords(phonemes).joinToString(" "))
        require(batches.isNotEmpty()) { "Nothing to say in '$phonemes'" }
        val pieces = mutableListOf<FloatArray>()
        val timings = mutableListOf<Timing>()
        var offset = 0
        for ((i, batch) in batches.withIndex()) {
            val pause = if (i < batches.lastIndex) Chunker.pauseAfter(batch, SENTENCE_PAUSE, CLAUSE_PAUSE) else 0.0
            require(batch.length <= MAX_SOUNDS) { "text is too long, must be less than $MAX_SOUNDS phonemes" }
            val tokens = Vocab.tokenize(batch)
            require(tokens.isNotEmpty()) { "No sounds of '$batch' are in the model's vocabulary" }
            val (wave, durations) = infer(tokens, voices.style(voice, tokens.size), speed)
            // Sample offsets of every sound's start (the leading pad dropped), then trimmed.
            val edges = tokenEdges(durations, wave.size).drop(1)
            val (head, tail) = Audio.soundSpan(wave)
            val kept = wave.copyOfRange(head, tail)
            val audio = if (pause > 0) Audio.join(listOf(kept, Audio.silence(pause))) else kept
            val moved = edges.map { (it - head).coerceIn(0L, kept.size.toLong()) + offset }
            Vocab.known(batch).forEachIndexed { k, ph ->
                if (k + 1 < moved.size) timings += Timing(ph, moved[k].toDouble() / Audio.RATE, moved[k + 1].toDouble() / Audio.RATE)
            }
            pieces += audio
            offset += audio.size
        }
        val (audio, spoken) = Pauses.insert(Audio.join(pieces), timings, Audio.RATE, SENTENCE_PAUSE, CLAUSE_PAUSE)
        return Speech(audio, spoken)
    }

    /** One pass of the model: the waveform and each token's duration (pads included). */
    private fun infer(tokens: IntArray, style: FloatArray, speed: Float): Pair<FloatArray, FloatArray> {
        val ids = LongArray(tokens.size + 2).also { for (i in tokens.indices) it[i + 1] = tokens[i].toLong() }
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { input ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(style), longArrayOf(1, Voices.WIDTH.toLong())).use { st ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1)).use { sp ->
                    session.run(mapOf("input_ids" to input, "style" to st, "speed" to sp)).use { out ->
                        val wave = (out[0] as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                        val dur = (out[1] as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                        return wave to dur
                    }
                }
            }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val MAX_SOUNDS = 510
        const val SENTENCE_PAUSE = 0.25
        const val CLAUSE_PAUSE = 0.1

        /**
         * Sample offset of every token boundary, the leading pad's included: the
         * durations' running total scaled to the waveform, in float32 as numpy does.
         */
        fun tokenEdges(durations: FloatArray, samples: Int): List<Long> {
            val frames = FloatArray(durations.size + 1)
            for (i in durations.indices) frames[i + 1] = frames[i] + durations[i]
            val scale = samples.toFloat() / frames.last()
            return frames.map { Math.rint((it * scale).toDouble()).toLong() }
        }

        /** Python's str.split(): runs of whitespace, none empty. */
        fun pythonWords(s: String): List<String> {
            val out = mutableListOf<String>()
            var start = -1
            for (i in 0..s.length) {
                val space = i == s.length || s[i].isWhitespace()
                if (space && start >= 0) {
                    out += s.substring(start, i)
                    start = -1
                } else if (!space && start < 0) {
                    start = i
                }
            }
            return out
        }
    }
}

/** A part ready to play: its speech with softened edges and the pause after it (engine.py's run_job). */
fun Speech.withPause(pause: Double): FloatArray =
    Audio.join(listOf(Audio.softenEdges(samples), Audio.silence(pause)))
