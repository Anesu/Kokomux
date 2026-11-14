package kokomux.engine.qwen

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kokomux.engine.Npy
import kokomux.engine.ming.CharClasses
import kokomux.engine.ming.QwenTokenizer
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random
import java.util.concurrent.CancellationException
import kotlin.math.exp
import kotlin.math.min

/**
 * A voice made from a clip: the speaker's vector, and, when the clip's words
 * are known, the clip as Qwen's own sound codes (12.5 frames a second, 16 codes
 * each) with its words, which Qwen continues from (its "in-context" cloning).
 */
class Clone(val speaker: FloatArray, val codes: Array<LongArray>, val words: String) {
    /** Keep it in [file]. */
    fun save(file: File) {
        val tmp = File(file.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(speaker.size)
            speaker.forEach { out.writeFloat(it) }
            out.writeInt(codes.size)
            codes.forEach { frame -> frame.forEach { out.writeShort(it.toInt()) } }
            out.writeUTF(words)
        }
        if (!tmp.renameTo(file)) error("Couldn't save $file")
    }

    companion object {
        fun load(file: File): Clone = DataInputStream(file.inputStream().buffered()).use { inp ->
            val speaker = FloatArray(inp.readInt()) { inp.readFloat() }
            val codes = Array(inp.readInt()) { LongArray(Qwen.GROUPS) { inp.readShort().toLong() } }
            Clone(speaker, codes, inp.readUTF())
        }
    }
}

/**
 * Qwen3-TTS 0.6B Base on ONNX Runtime, as onnx-community's 4-bit export runs
 * it (its inference.py), with wavekat's 4-bit code predictor, which keeps a
 * cache and so takes one code per call, and its full-precision code tables
 * (both exports of the same weights): a prompt of text and sound-code
 * embeddings, the talker (with its cache) choosing each frame's first code,
 * the code predictor its other 15, and the codec's decoder turning frames into
 * 24 kHz speech. Voices are cloned from a clip ([clone]). Calls block; one at
 * a time.
 */
class Qwen(private val dir: File, tokenizerDir: File = dir, threads: Int = 0) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setInterOpNumThreads(1)
        if (threads > 0) setIntraOpNumThreads(threads)
    }
    private fun session(name: String) = env.createSession(File(dir, "$name.onnx").path, options)

    private val textEmbed = session("text_embed")
    private val talker = session("talker_cache")
    private val predictor = session("code_predictor")
    private val decoder = session("tok_decoder")
    // Only for making a voice.
    private val encoder by lazy { session("tok_encoder") }
    private val speakerEncoder by lazy { session("speaker_encoder") }
    private val pastNames = talker.inputNames.toList().drop(3)

    /** The talker's table for a frame's first code, and the predictor's for each of the other 15. */
    private val codecTable = Npy.floats(File(dir, "talker_codec_embedding.npy"))
    private val groupTables = Array(GROUPS - 1) { Npy.floats(File(dir, "cp_codec_embedding_$it.npy")) }

    private val tokenizer = CharClasses.load(File(tokenizerDir, "char_classes.bin")).let { cc ->
        QwenTokenizer(File(tokenizerDir, "tokenizer_vocab.txt"), File(tokenizerDir, "tokenizer_merges.txt"),
            File(tokenizerDir, "tokenizer_added.txt"), cc)
    }
    private val padRow by lazy { embedText(longArrayOf(TTS_PAD)) }

    private fun ids(text: String) = tokenizer.encode(text).map { it.toLong() }.toLongArray()

    private fun longs(values: LongArray, vararg shape: Long) = OnnxTensor.createTensor(env, LongBuffer.wrap(values), shape)
    private fun floats(values: FloatArray, vararg shape: Long) = OnnxTensor.createTensor(env, FloatBuffer.wrap(values), shape)

    /** One input in, its first output's floats out. */
    private fun run1(s: OrtSession, name: String, input: OnnxTensor): FloatArray = input.use {
        s.run(mapOf(name to it)).use { r -> (r[0] as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { a -> b.get(a) } } }
    }

    private fun embedText(ids: LongArray) = run1(textEmbed, "text_ids", longs(ids, 1, ids.size.toLong()))
    private fun embedCodec(ids: LongArray) = FloatArray(ids.size * H).also { out ->
        for ((i, id) in ids.withIndex()) System.arraycopy(codecTable, id.toInt() * H, out, i * H, H)
    }

    /** A frame's 16 codes as the talker's next input: the sum of their embeddings. */
    private fun stepEmbed(codes: LongArray): FloatArray {
        val out = codecTable.copyOfRange(codes[0].toInt() * H, codes[0].toInt() * H + H)
        for (g in 1 until GROUPS) {
            val t = groupTables[g - 1]
            val at = codes[g].toInt() * H
            for (i in 0 until H) out[i] += t[at + i]
        }
        return out
    }

    // ------------------------------------------------------------ making a voice

    /**
     * A voice from [clip] (24 kHz mono, a few seconds of one person speaking), with the
     * clip's [words] if they're known (then Qwen continues from the clip itself).
     */
    fun clone(clip: FloatArray, words: String): Clone {
        val speaker = run1(speakerEncoder, "audio", floats(clip, 1, clip.size.toLong()))
        return Clone(speaker, if (words.isBlank()) emptyArray() else encode(clip), words.trim())
    }

    /**
     * The clip as sound codes. The encoder takes a second at a time and gives 13 frames for its
     * 12.5, the last half padding: windows go 12 frames apart and keep their first 12.
     */
    fun encode(clip: FloatArray): Array<LongArray> {
        val frames = (clip.size + HOP - 1) / HOP
        val out = ArrayList<LongArray>()
        var s = 0
        while (s < clip.size) {
            val window = FloatArray(WINDOW)
            System.arraycopy(clip, s, window, 0, min(WINDOW, clip.size - s))
            floats(window, 1, 1, WINDOW.toLong()).use { input ->
                encoder.run(mapOf("audio" to input)).use { r ->
                    val codes = (r[0] as OnnxTensor).longBuffer
                    for (f in 0 until 12) out += LongArray(GROUPS) { codes.get(f * GROUPS + it) }
                }
            }
            s += 12 * HOP
        }
        return out.take(frames).toTypedArray()
    }

    // ------------------------------------------------------------ speaking

    /** [text] in [voice], speaking [lang] ("english"; see [LANGUAGES]), as 24 kHz samples. */
    fun say(text: String, lang: String, voice: Clone, random: Random?, stop: () -> Boolean = { false }): FloatArray {
        val codes = frames(prompt(text, lang, voice), maxFrames(text), random, stop)
        return decode(codes)
    }

    /** The talker's prompt, as embeddings [n × 1024]. */
    internal fun prompt(text: String, lang: String, voice: Clone): Array<FloatArray> {
        val rows = ArrayList<FloatArray>()
        fun add(flat: FloatArray) {
            for (i in 0 until flat.size / H) rows += flat.copyOfRange(i * H, (i + 1) * H)
        }
        val special = embedText(longArrayOf(TTS_BOS, TTS_EOS, TTS_PAD))
        val bos = special.copyOfRange(0, H)
        val eos = special.copyOfRange(H, 2 * H)
        val pad = special.copyOfRange(2 * H, 3 * H)
        val language = LANGUAGES[lang]
        val tags = if (language != null) longArrayOf(CODEC_THINK, CODEC_THINK_BOS, language, CODEC_THINK_EOS)
        else longArrayOf(CODEC_NOTHINK, CODEC_THINK_BOS, CODEC_THINK_EOS)
        // The codec side: the tags, the speaker, then pad and bos.
        val codec = ArrayList<FloatArray>()
        embedCodec(tags).let { e -> for (i in tags.indices) codec += e.copyOfRange(i * H, (i + 1) * H) }
        codec += voice.speaker
        embedCodec(longArrayOf(CODEC_PAD, CODEC_BOS)).let { e -> codec += e.copyOfRange(0, H); codec += e.copyOfRange(H, 2 * H) }

        val input = ids("<|im_start|>assistant\n$text<|im_end|>\n<|im_start|>assistant\n")
        add(embedText(input.copyOfRange(0, 3)))                         // <|im_start|>assistant\n
        for (i in 0 until codec.size - 1) rows += plus(if (i < codec.size - 2) pad else bos, codec[i])
        val body = input.copyOfRange(3, input.size - 5)                  // the text itself
        if (voice.codes.isNotEmpty()) {
            val clipIds = ids("<|im_start|>assistant\n${voice.words}<|im_end|>\n").let { it.copyOfRange(3, it.size - 2) }
            val texts = ArrayList<FloatArray>()
            embedText(clipIds + body).let { e -> for (i in 0 until e.size / H) texts += e.copyOfRange(i * H, (i + 1) * H) }
            texts += eos
            val padCodes = embedCodec(LongArray(texts.size) { CODEC_PAD })
            for ((i, t) in texts.withIndex()) rows += plus(t, padCodes.copyOfRange(i * H, (i + 1) * H))
            rows += plus(embedCodec(longArrayOf(CODEC_BOS)), pad)
            for (frame in voice.codes) rows += plus(stepEmbed(frame), pad)
        } else {
            val texts = ArrayList<FloatArray>()
            embedText(body).let { e -> for (i in 0 until e.size / H) texts += e.copyOfRange(i * H, (i + 1) * H) }
            texts += eos
            val padCodes = embedCodec(LongArray(texts.size) { CODEC_PAD })
            for ((i, t) in texts.withIndex()) rows += plus(t, padCodes.copyOfRange(i * H, (i + 1) * H))
            rows += plus(pad, embedCodec(longArrayOf(CODEC_BOS)))
        }
        return rows.toTypedArray()
    }

    /** At most this many frames for [text]: three times a slow reading of it, and five seconds. */
    private fun maxFrames(text: String) = ((text.length / 12.0 * 3 + 5) * FPS).toInt()

    /**
     * Frames of 16 codes from the prompt until the talker says it's done: greedy with no
     * [random] (for tests), otherwise sampled as the model's generation_config has it.
     */
    internal fun frames(prompt: Array<FloatArray>, max: Int, random: Random?, stop: () -> Boolean): Array<LongArray> {
        val out = ArrayList<LongArray>()
        val heard = LinkedHashSet<Int>()
        var past: OrtSession.Result? = null
        var total = prompt.size
        try {
            var result = talk(prompt, 0, total, null)
            past = result
            while (out.size < max) {
                if (stop()) throw CancellationException("stopped")
                val logits = lastRow(result[0] as OnnxTensor, VOCAB)
                val hidden = lastRow(result[1] as OnnxTensor, H)
                for (i in VOCAB - 1024 until VOCAB) if (i != CODEC_EOS.toInt()) logits[i] = Float.NEGATIVE_INFINITY
                if (out.size < 2) logits[CODEC_EOS.toInt()] = Float.NEGATIVE_INFINITY      // at least two frames
                for (i in heard) logits[i] = if (logits[i] < 0) logits[i] * PENALTY else logits[i] / PENALTY
                val first = pick(logits, random)
                if (first == CODEC_EOS.toInt()) break
                heard += first
                val codes = LongArray(GROUPS).also { it[0] = first.toLong() }
                predict(hidden, codes, random)
                out += codes
                val next = stepEmbed(codes)
                for (i in 0 until H) next[i] += padRow[i]
                result = talk(arrayOf(next), total, total + 1, result)
                past?.close()
                past = result
                total += 1
            }
        } finally {
            past?.close()
        }
        return out.toTypedArray()
    }

    /**
     * A frame's other 15 codes, one per call of the predictor: it starts from the talker's
     * [hidden] state and the first code, then takes each code it chose, with its cache.
     */
    private fun predict(hidden: FloatArray, codes: LongArray, random: Random?) {
        var x = FloatArray(2 * H).also { hidden.copyInto(it); System.arraycopy(codecTable, codes[0].toInt() * H, it, H, H) }
        var past: OrtSession.Result? = null
        val empty = FloatArray(0)
        try {
            for (g in 0 until GROUPS - 1) {
                val n = x.size / H
                val made = ArrayList<OnnxTensor>()
                val result = try {
                    val inputs = HashMap<String, OnnxTensor>()
                    inputs["inputs_embeds"] = floats(x, 1, n.toLong(), H.toLong()).also { made += it }
                    inputs["generation_steps"] = longs(longArrayOf(g.toLong()), 1).also { made += it }
                    if (past == null) {
                        inputs["past_keys"] = floats(empty, 5, 1, 8, 0, 128).also { made += it }
                        inputs["past_values"] = floats(empty, 5, 1, 8, 0, 128).also { made += it }
                    } else {
                        inputs["past_keys"] = past[1] as OnnxTensor
                        inputs["past_values"] = past[2] as OnnxTensor
                    }
                    predictor.run(inputs)
                } finally {
                    made.forEach { it.close() }
                }
                past?.close()
                past = result
                codes[g + 1] = pick(lastRow(result[0] as OnnxTensor, SUB_VOCAB), random).toLong()
                x = groupTables[g].copyOfRange(codes[g + 1].toInt() * H, codes[g + 1].toInt() * H + H)
            }
        } finally {
            past?.close()
        }
    }

    /** One pass of the talker over [rows] at positions [from].., with the cache in [past] (none: the prompt). */
    private fun talk(rows: Array<FloatArray>, from: Int, total: Int, past: OrtSession.Result?): OrtSession.Result {
        val n = rows.size
        val flat = FloatArray(n * H).also { for (i in 0 until n) rows[i].copyInto(it, i * H) }
        val pos = LongArray(3 * n) { (from + it % n).toLong() }
        val inputs = HashMap<String, OnnxTensor>()
        val made = ArrayList<OnnxTensor>()
        try {
            inputs["inputs_embeds"] = floats(flat, 1, n.toLong(), H.toLong()).also { made += it }
            inputs["position_ids"] = longs(pos, 3, 1, n.toLong()).also { made += it }
            inputs["attention_mask"] = longs(LongArray(total) { 1 }, 1, total.toLong()).also { made += it }
            for ((k, name) in pastNames.withIndex()) {
                inputs[name] = if (past == null) floats(FloatArray(0), 1, 8, 0, 128).also { made += it }
                else past[2 + k] as OnnxTensor
            }
            return talker.run(inputs)
        } finally {
            made.forEach { it.close() }
        }
    }

    private fun lastRow(t: OnnxTensor, width: Int): FloatArray {
        val b = t.floatBuffer
        val row = FloatArray(width)
        b.position(b.limit() - width)
        b.get(row)
        return row
    }

    /** The code to say: the likeliest with no [random]; else top-k 50 at temperature 0.9. */
    private fun pick(logits: FloatArray, random: Random?): Int {
        var best = 0
        for (i in logits.indices) if (logits[i] > logits[best]) best = i
        if (random == null) return best
        val top = logits.indices.sortedByDescending { logits[it] }.take(TOP_K).filter { logits[it].isFinite() }
        val max = logits[top[0]].toDouble()
        val p = top.map { exp((logits[it] - max) / TEMPERATURE) }
        var r = random.nextDouble() * p.sum()
        for ((k, i) in top.withIndex()) {
            r -= p[k]
            if (r <= 0) return i
        }
        return top.last()
    }

    /**
     * Frames to 24 kHz samples: the decoder takes 25 frames at a time, so each call is the
     * 10 frames before as context and 15 new ones, of which only the new are kept.
     */
    internal fun decode(codes: Array<LongArray>): FloatArray {
        if (codes.isEmpty()) return FloatArray(0)
        val out = FloatArray(codes.size * HOP)
        var s = 0
        while (s < codes.size) {
            val lo = maxOf(0, s - 10)
            val hi = min(codes.size, s + 15)
            val n = hi - lo
            val window = LongArray(DECODE * GROUPS)
            for (f in 0 until DECODE) codes[lo + f % n].copyInto(window, f * GROUPS)
            longs(window, 1, DECODE.toLong(), GROUPS.toLong()).use { input ->
                decoder.run(mapOf("audio_codes" to input)).use { r ->
                    val wave = (r[0] as OnnxTensor).floatBuffer
                    wave.position((s - lo) * HOP)
                    wave.get(out, s * HOP, (hi - s) * HOP)
                }
            }
            s += 15
        }
        return out
    }

    private fun plus(a: FloatArray, b: FloatArray) = FloatArray(H) { a[it] + b[it] }

    override fun close() {
        listOf(textEmbed, talker, predictor, decoder).forEach { it.close() }
        options.close()
    }

    companion object {
        const val RATE = 24000
        const val H = 1024
        const val GROUPS = 16
        const val VOCAB = 3072
        const val SUB_VOCAB = 2048
        const val FPS = 12.5
        /** Samples per frame, and what the encoder and decoder take at a time. */
        const val HOP = 1920
        const val WINDOW = 24000
        const val DECODE = 25
        // config.json of Qwen/Qwen3-TTS-12Hz-0.6B-Base.
        const val TTS_BOS = 151672L
        const val TTS_EOS = 151673L
        const val TTS_PAD = 151671L
        const val CODEC_PAD = 2148L
        const val CODEC_BOS = 2149L
        const val CODEC_EOS = 2150L
        const val CODEC_THINK = 2154L
        const val CODEC_NOTHINK = 2155L
        const val CODEC_THINK_BOS = 2156L
        const val CODEC_THINK_EOS = 2157L
        val LANGUAGES = mapOf(
            "chinese" to 2055L, "english" to 2050L, "german" to 2053L, "italian" to 2070L, "portuguese" to 2071L,
            "spanish" to 2054L, "japanese" to 2058L, "korean" to 2064L, "french" to 2061L, "russian" to 2069L,
        )
        // generation_config.json: repetition penalty on the first code, top-k and temperature on all.
        const val PENALTY = 1.05f
        const val TOP_K = 50
        const val TEMPERATURE = 0.9


    }
}
