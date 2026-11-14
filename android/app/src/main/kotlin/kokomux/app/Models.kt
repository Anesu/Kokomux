package kokomux.app

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * An engine's files, downloaded into the app's own storage from fixed
 * addresses (each pinned to a commit), resuming a download that was cut off.
 * Kokoro's are engine.py's; Ming's and Qwen's are fetched when one of their
 * voices is first wanted.
 */
class Pack(val title: String, val dir: File, private val items: List<Item>) {
    /** A file: its name here, where it comes from, and its size (to show progress and check it arrived whole). */
    class Item(val name: String, val url: String, val bytes: Long)

    val total = items.sumOf { it.bytes }

    fun file(name: String) = File(dir, name)

    /** Every file is here, whole. */
    fun ready() = items.all { file(it.name).length() == it.bytes }

    /** The bytes it takes up now. */
    fun size(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun remove() {
        dir.deleteRecursively()
    }

    /** Fetch whatever is missing; [progress] gets (bytes so far, total bytes) across all the files. */
    fun download(progress: (Long, Long) -> Unit) {
        dir.mkdirs()
        var before = 0L
        for (item in items) {
            val base = before
            fetch(item) { done -> progress(base + done, total) }
            before += item.bytes
        }
    }

    private fun fetch(item: Item, progress: (Long) -> Unit) {
        val dest = file(item.name)
        if (dest.length() == item.bytes) return
        dest.delete()
        val part = File(dir, item.name + ".part")
        var last: IOException? = null
        repeat(5) { attempt ->
            try {
                val have = part.length()
                val conn = URI(item.url).toURL().openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.connectTimeout = 60_000
                conn.readTimeout = 60_000
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val resumed = have > 0 && conn.responseCode == HttpURLConnection.HTTP_PARTIAL
                if (conn.responseCode !in 200..299) throw IOException("${item.name}: HTTP ${conn.responseCode}")
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(part, resumed).use { out ->
                        val buf = ByteArray(1 shl 18)
                        var done = if (resumed) have else 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            progress(done)
                        }
                    }
                }
                if (part.length() != item.bytes) throw IOException("${item.name} arrived cut short")
                if (!part.renameTo(dest)) throw IOException("Couldn't save ${item.name}")
                return
            } catch (e: IOException) {
                last = e
                Thread.sleep(2000L * (attempt + 1))
            }
        }
        throw IOException("Couldn't download ${item.name}. Try again: it picks up where it stopped.", last)
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"

        const val KOKORO_MODEL = "kokoro-v1.0-timestamped.fp32.onnx"
        const val KOKORO_VOICES = "voices-v1.0.bin"

        /** Kokoro 82M v1.0, fp32 with durations, and its voices: engine.py's MODEL_SRC and VOICES_SRC. */
        fun kokoro(files: File) = Pack("Kokoro", File(files, "models"), listOf(
            Item(KOKORO_MODEL, "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX-timestamped/resolve/" +
                "dd4401a9add81ac692d20e240d22ec9dda82cc29/onnx/model.onnx", 325_532_171L),
            Item(KOKORO_VOICES, "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin",
                28_214_398L),
        ))

        private const val MING = "https://huggingface.co/litert-community/Ming-3.0-tts-0.5B/resolve/3db0f978908bc5f9c503850045853d8301e4b203/"

        /** Ming's tokenizer tables, which Qwen's tokenizer shares. */
        private val TOKENIZER = listOf(
            "char_classes.bin" to 13_540L, "tokenizer_vocab.txt" to 1_521_126L,
            "tokenizer_merges.txt" to 1_671_839L, "tokenizer_added.txt" to 1_344L,
        )

        /** Ming-3.0-tts-0.5B for the phone's NPU (litert-community): the faster placement's graphs and the host's files. */
        fun ming(files: File) = Pack("Ming 3.0", File(files, "ming"), (listOf(
            "lm2_epsdiv_prefill_wfp16.tflite" to 716_458_304L, "lm2_epsdiv_step_wfp16.tflite" to 716_389_696L,
            "flow_r4b_s10_wfp16.tflite" to 204_500_864L, "agg_r4b_wfp16.tflite" to 203_752_128L,
            "dec_c16_wfp16.tflite" to 722_909_248L, "embed_tokens_f16.bin" to 272_269_312L,
            "temb_epss10.bin" to 40_960L, "istft_window.bin" to 14_112L, "stop_head.bin" to 7_176L,
        ) + TOKENIZER).map { (name, bytes) -> Item(name, MING + name, bytes) })

        private const val QWEN = "https://huggingface.co/onnx-community/Qwen3-TTS-12Hz-0.6B-Base/resolve/2b18c0a044a190e35f1a907779aa51cda0fb1fff/cpu_int4/"
        private const val WAVEKAT = "https://huggingface.co/wavekat/Qwen3-TTS-0.6B-Base-ONNX/resolve/177cd39e2ddab0d4d7ce5152a7abc2d382621158/"

        /**
         * Qwen3-TTS 0.6B Base: onnx-community's 4-bit export, with wavekat's 4-bit code predictor
         * (it keeps a cache, five times faster) and its full-precision code tables, and the tokenizer's tables.
         */
        fun qwen(files: File) = Pack("Qwen3-TTS", File(files, "qwen"), listOf(
            "text_embed.onnx" to 203_384_915L, "talker_cache.onnx" to 288_573_853L, "tok_decoder.onnx" to 458_268_831L,
            "tok_encoder.onnx" to 225_554_101L, "speaker_encoder.onnx" to 36_104_723L,
        ).map { (name, bytes) -> Item(name, QWEN + name, bytes) } + listOf(
            Item("code_predictor.onnx", WAVEKAT + "int4/code_predictor.onnx", 360_475L),
            Item("code_predictor.onnx.data", WAVEKAT + "int4/code_predictor.onnx.data", 335_306_752L),
            Item("talker_codec_embedding.npy", WAVEKAT + "embeddings/talker_codec_embedding.npy", 12_583_040L),
        ) + (0 until 15).map { Item("cp_codec_embedding_$it.npy", WAVEKAT + "embeddings/cp_codec_embedding_$it.npy", 8_388_736L) } +
            TOKENIZER.map { (name, bytes) -> Item(name, MING + name, bytes) })
    }
}
