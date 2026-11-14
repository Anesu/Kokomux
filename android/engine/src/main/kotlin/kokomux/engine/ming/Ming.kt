// Adapted from litert-community/Ming-3.0-tts-0.5B (android/app, MingEngine.kt), MIT License,
// Copyright (c) 2025 inclusionAI: see LICENSE in this folder.
package kokomux.engine.ming

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.Random
import java.util.concurrent.CancellationException
import kotlin.math.min

/** One signature of one graph: inputs args_0, args_1… in order, outputs output_0, output_1… */
interface GraphCall : AutoCloseable {
    /** Run [sig] with [inputs]; returns the outputs listed in [read] (null: all of them). */
    fun call(sig: String, inputs: Array<FloatArray>, read: IntArray? = null): Array<FloatArray?>
}

/** The five graphs Ming runs, as the app loads them (LiteRT on the NPU), or stand-ins in a test. */
class MingGraphs(val prefill: GraphCall, val step: GraphCall, val flow: GraphCall, val agg: GraphCall,
                 val dec: GraphCall) : AutoCloseable {
    override fun close() = listOf(prefill, step, flow, agg, dec).forEach { it.close() }

    companion object {
        /**
         * The files, as the model card's faster placement has them: everything on the NPU, the
         * language model as one file per signature, the decoder 16 latents a call.
         */
        val FILES = mapOf(
            "prefill" to "lm2_epsdiv_prefill_wfp16.tflite",
            "step" to "lm2_epsdiv_step_wfp16.tflite",
            "flow" to "flow_r4b_s10_wfp16.tflite",
            "agg" to "agg_r4b_wfp16.tflite",
            "dec" to "dec_c16_wfp16.tflite",
        )

        /** Each graph's signature: (inputs, outputs). */
        val SIGNATURES = mapOf(
            "prefill" to mapOf("prefill" to (6 to 3)),
            "step" to mapOf("step" to (6 to 3)),
            "flow" to mapOf("flow" to (4 to 1)),
            "agg" to mapOf("agg" to (1 to 1)),
            "dec" to mapOf("dec" to (6 to 3)),
        )

        /** What the host reads next to the graphs. */
        val HOST_FILES = listOf(
            "embed_tokens_f16.bin", "temb_epss10.bin", "istft_window.bin", "stop_head.bin",
            "char_classes.bin", "tokenizer_vocab.txt", "tokenizer_merges.txt", "tokenizer_added.txt",
        )
    }
}

/**
 * Ming-3.0-tts-0.5B, the host's side (contract.json's host loop): a piece of
 * text -> prompt ids -> embedding rows -> prefill in 64-token chunks -> one
 * frame per 80 ms latent (the language model's step, the stop head here, the
 * flow, the aggregator) -> the decoder every 16 latents -> the ISTFT ->
 * 44.1 kHz samples. A built-in voice is named in the prompt; its speaker row is
 * zeros. Calls block; one piece at a time.
 */
class Ming(dir: File, private val graphs: MingGraphs) : AutoCloseable {
    private val cc = CharClasses.load(File(dir, "char_classes.bin"))
    private val tokenizer = QwenTokenizer(
        File(dir, "tokenizer_vocab.txt"), File(dir, "tokenizer_merges.txt"), File(dir, "tokenizer_added.txt"), cc,
    )
    private val frontend = TextFrontend(cc)
    private val embedChannel = RandomAccessFile(File(dir, "embed_tokens_f16.bin"), "r").channel
    private val embed: MappedByteBuffer =
        embedChannel.map(FileChannel.MapMode.READ_ONLY, 0, embedChannel.size()).also { it.order(ByteOrder.LITTLE_ENDIAN) }
    private val temb = HostMath.readF32(File(dir, "temb_epss10.bin"))
    private val istft = Istft(HostMath.readF32(File(dir, "istft_window.bin")))
    private val stopHead = StopHead(File(dir, "stop_head.bin"))

    private val pk = FloatArray(HostMath.KV * HostMath.LM_PAST * HostMath.HEAD_DIM)
    private val pv = FloatArray(pk.size)
    private val cos64 = FloatArray(64 * 64)
    private val sin64 = FloatArray(64 * 64)
    private val cos1 = FloatArray(64)
    private val sin1 = FloatArray(64)
    private val mask1 = FloatArray(HostMath.LM_PAST + 1)

    /** The pieces [text] is said in (upstream's prepare_text: at most 24 "semantic units" each). */
    fun pieces(text: String, lang: String): List<String> = frontend.prepare(text, lang)

    /** One piece said by built-in [voice] ("US_Emma") in [lang] ("en"), as 44.1 kHz samples. */
    fun say(piece: String, lang: String, voice: String, seed: Long, stop: () -> Boolean = { false }): FloatArray {
        val built = PromptContract.buildInputIds(
            tokenizer, PromptContract.ttsPrompt(lang), piece, PromptContract.ipInstruction(voice),
        )
        val ids = built.ids
        val n = ids.size
        require(n < HostMath.MAX_CACHE_LEN - 1) { "A piece of $n tokens leaves Ming no room to speak" }
        val h = HostMath.HIDDEN
        val rows = FloatArray(n * h)
        for (i in 0 until n) embedRow(ids[i], rows, i * h)
        for (p in built.speakerPositions) rows.fill(0f, p * h, (p + 1) * h)

        // Prefill rows[0 : n - 1] in 64-token chunks.
        pk.fill(0f)
        pv.fill(0f)
        val x64 = FloatArray(64 * h)
        val slot = HostMath.LM_PAST * HostMath.HEAD_DIM
        var start = 0
        while (start < n - 1) {
            val k = min(64, n - 1 - start)
            x64.fill(0f)
            System.arraycopy(rows, start * h, x64, 0, k * h)
            HostMath.rope(start, 64, cos64, sin64)
            val out = graphs.prefill.call("prefill", arrayOf(x64, cos64, sin64, HostMath.lmMask(start, 64), pk, pv), intArrayOf(1, 2))
            for (g in 0 until HostMath.KV) {
                System.arraycopy(out[1]!!, g * 64 * 64, pk, g * slot + start * 64, k * 64)
                System.arraycopy(out[2]!!, g * 64 * 64, pv, g * slot + start * 64, k * 64)
            }
            start += 64
            if (stop()) throw CancellationException("stopped")
        }

        // One frame per latent, the decoder as each 16 are ready.
        val random = Random(seed)
        var hist = FloatArray(64)
        var x = rows.copyOfRange((n - 1) * h, n * h)
        val pcm = ArrayList<FloatArray>()
        val decoder = Decoder { pcm += it }
        val steps = HostMath.MAX_CACHE_LEN - n
        for (step in 0 until steps) {
            if (stop()) throw CancellationException("stopped")
            val pos = n - 1 + step
            HostMath.rope(pos, 1, cos1, sin1)
            HostMath.lmMask(pos, 1, mask1)
            val o = graphs.step.call("step", arrayOf(x, cos1, sin1, mask1, pk, pv))
            val z = o[0]!!
            for (g in 0 until HostMath.KV) {
                System.arraycopy(o[1]!!, g * 64, pk, g * slot + pos * 64, 64)
                System.arraycopy(o[2]!!, g * 64, pv, g * slot + pos * 64, 64)
            }
            val logits = stopHead.logits(z)
            val noise = FloatArray(64) { random.nextGaussian().toFloat() }
            val latent = graphs.flow.call("flow", arrayOf(z, hist, noise, temb))[0]!!
            decoder.lats += latent
            val (done, _) = HostMath.stopDecision(logits[0], logits[1], step)
            if (done || step == steps - 1) break
            decoder.drain(last = false)
            x = graphs.agg.call("agg", arrayOf(latent))[0]!!
            hist = latent
        }
        decoder.drain(last = true)
        decoder.finish()
        val out = FloatArray(pcm.sumOf { it.size })
        var at = 0
        for (p in pcm) {
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }

    private fun embedRow(id: Int, out: FloatArray, off: Int) {
        var b = id * HostMath.HIDDEN * 2
        for (j in 0 until HostMath.HIDDEN) {
            out[off + j] = HostMath.halfToFloat(embed.getShort(b).toInt() and 0xffff)
            b += 2
        }
    }

    /**
     * The decoder of one piece: chunks of 16 latents (and one either side for its x4 upsample) with a
     * 64-position cache per layer, then the streaming ISTFT.
     */
    private inner class Decoder(private val sink: (FloatArray) -> Unit) {
        val lats = ArrayList<FloatArray>()
        private val chunk = CHUNK
        private val positions = HostMath.POS_PER_LATENT * chunk
        private var a = 0
        private var dpk = FloatArray(HostMath.KV * HostMath.DEC_PAST * HostMath.HEAD_DIM)
        private var dpv = FloatArray(dpk.size)
        private val stream = istft.Stream()
        private val cosC = FloatArray(positions * 64)
        private val sinC = FloatArray(positions * 64)
        private val window = FloatArray((chunk + 2) * HostMath.LATENT)
        private val frameBuf = FloatArray(3528)

        /** Decode every chunk whose next latent exists; with [last], the rest too. */
        fun drain(last: Boolean) {
            while (lats.size >= a + chunk + 1 || (last && a < lats.size)) {
                HostMath.decLatentWindow(lats, a, chunk, window)
                HostMath.rope(4 * a, positions, cosC, sinC)
                val o = graphs.dec.call("dec", arrayOf(window, cosC, sinC, HostMath.decMask(4 * a, positions), dpk, dpv))
                dpk = o[1]!!
                dpv = o[2]!!
                val head = o[0]!!
                val real = if (last) min(chunk, lats.size - a) else chunk
                for (r in 0 until 4 * real) {
                    istft.frame(head, r * HostMath.HEAD_WIDTH, frameBuf)
                    stream.push(frameBuf)
                }
                stream.flush(sink)
                a += chunk
            }
        }

        fun finish() = stream.finish(sink)
    }

    override fun close() {
        graphs.close()
        embedChannel.close()
    }

    companion object {
        const val RATE = HostMath.SAMPLE_RATE
        /** Latents per decoder call (dec_c16). */
        const val CHUNK = 16

        /** The built-in voices (the model's instruction cookbook), and the language each speaks. */
        val VOICES = listOf(
            "US_Emma" to "en", "US_James" to "en", "US_Stella" to "en", "UK_George" to "en", "UK_Charlotte" to "en",
            "灵小萌" to "zh", "灵岩" to "zh", "灵小雅" to "zh",
        )
    }
}
