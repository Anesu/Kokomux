package kokomux.app

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * An engine's files, downloaded into the app's own storage from fixed
 * addresses (each pinned to a commit), resuming a download that was cut off.
 * A file is kept only once it's whole and its SHA-256 is the one written here.
 * Kokoro's are engine.py's; Ming's and Qwen's are fetched when one of their
 * voices is first wanted.
 */
class Pack(val title: String, val dir: File, private val items: List<Item>) {
    /** A file: its name here, where it comes from, its size (to show progress) and its SHA-256 (to check it arrived as it was published). */
    class Item(val name: String, val url: String, val bytes: Long, val sha256: String)

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
                if (sha256(part) != item.sha256) {
                    part.delete()                      // (nothing of it is worth resuming)
                    throw IOException("${item.name} arrived damaged")
                }
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

        /** A file's SHA-256, in hex. */
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 18)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** A file as it's listed below: its name, size and SHA-256; [at] makes it an [Item] from where its files are. */
        private class F(val name: String, val bytes: Long, val sha256: String) {
            fun at(base: String) = Item(name, base + name, bytes, sha256)
        }

        /** Kokoro 82M v1.0, fp32 with durations, and its voices: engine.py's MODEL_SRC and VOICES_SRC, with its checksums. */
        fun kokoro(files: File) = Pack("Kokoro", File(files, "models"), listOf(
            Item(KOKORO_MODEL, "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX-timestamped/resolve/" +
                "dd4401a9add81ac692d20e240d22ec9dda82cc29/onnx/model.onnx", 325_532_171L,
                "651ea8291843a92276a4a003581a215cb07d15e47dde6fcfb1b768f9a1682054"),
            Item(KOKORO_VOICES, "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin",
                28_214_398L, "bca610b8308e8d99f32e6fe4197e7ec01679264efed0cac9140fe9c29f1fbf7d"),
        ))

        private const val MING = "https://huggingface.co/litert-community/Ming-3.0-tts-0.5B/resolve/3db0f978908bc5f9c503850045853d8301e4b203/"

        /** Ming's tokenizer tables, which Qwen's tokenizer shares. */
        private val TOKENIZER = listOf(
            F("char_classes.bin", 13_540L, "b12ac7f70eea66c531bbd89779f8d7af710eadceece190b9ebd1dc7d593f11d3"),
            F("tokenizer_vocab.txt", 1_521_126L, "479c920073825cc49357a884b9b26adcfd96155beb2ea068666c6d991065859f"),
            F("tokenizer_merges.txt", 1_671_839L, "599bab54075088774b1733fde865d5bd747cbcc7a547c5bc12610e874e26f5e3"),
            F("tokenizer_added.txt", 1_344L, "088313bd84664c79f4bd6e9d3fff5d6020961ff7aa8d409981b0e95b1eca3ff3"),
        ).map { it.at(MING) }

        /** Ming-3.0-tts-0.5B for the phone's NPU (litert-community): the faster placement's graphs and the host's files. */
        fun ming(files: File) = Pack("Ming 3.0", File(files, "ming"), listOf(
            F("lm2_epsdiv_prefill_wfp16.tflite", 716_458_304L, "db3964aa40278d4445a2037e6430c19315472d18fc11f078490882280e6582d3"),
            F("lm2_epsdiv_step_wfp16.tflite", 716_389_696L, "6c4ecacf5fb4e34bcfd63d59542febde057225a0f1cc189a5e7fa836ae161433"),
            F("flow_r4b_s10_wfp16.tflite", 204_500_864L, "9b6aed11d7086b54e78ac0282b8354d7843aec565fe3ada9a5ad7eb92bef0868"),
            F("agg_r4b_wfp16.tflite", 203_752_128L, "5188d360e0c393898b6221e52996c8b06526c45f162ed9a2201573fb4c78c1e0"),
            F("dec_c16_wfp16.tflite", 722_909_248L, "4d4a6ef61230451c710ca06fffcf90c2d2c7645bddcc7d69d42c2a1c9af9fa0a"),
            F("embed_tokens_f16.bin", 272_269_312L, "85f2682f649a94501d9759f5a5053a850d3910a3f02ce28c23771b0a2f15a273"),
            F("temb_epss10.bin", 40_960L, "cb3b832859e22bfa00203fe46a6b9008484c9f115b7096697c3564c1ca9328d2"),
            F("istft_window.bin", 14_112L, "1e1daeb1138f1ff220fc6f1e1e45c377a3ef7de4458105e32934c29ba2b8d5f9"),
            F("stop_head.bin", 7_176L, "c31ad4d6bc42107d6d4e28af210d2b4edce8a9a072637c6d241170b9011d909a"),
        ).map { it.at(MING) } + TOKENIZER)

        private const val QWEN = "https://huggingface.co/onnx-community/Qwen3-TTS-12Hz-0.6B-Base/resolve/2b18c0a044a190e35f1a907779aa51cda0fb1fff/cpu_int4/"
        private const val WAVEKAT = "https://huggingface.co/wavekat/Qwen3-TTS-0.6B-Base-ONNX/resolve/177cd39e2ddab0d4d7ce5152a7abc2d382621158/"

        /**
         * Qwen3-TTS 0.6B Base: onnx-community's 4-bit export, with wavekat's 4-bit code predictor
         * (it keeps a cache, five times faster) and its full-precision code tables, and the tokenizer's tables.
         */
        fun qwen(files: File) = Pack("Qwen3-TTS", File(files, "qwen"), listOf(
            F("text_embed.onnx", 203_384_915L, "530243e68ca8a723d0bf6705497f4b435c9875844d0488ccd67f571d10277705"),
            F("talker_cache.onnx", 288_573_853L, "861689fb1778f480137b647ad7550e6316136918f6f98e1799e141a42b62c82b"),
            F("tok_decoder.onnx", 458_268_831L, "8ec10051735029f6e08b04834128c9108428885c39384f443e49a6790ccb129f"),
            F("tok_encoder.onnx", 225_554_101L, "4a335eaf525ceef17b457f82704d3bc835d4cb15f42fc6f5908f2e860d3da400"),
            F("speaker_encoder.onnx", 36_104_723L, "6f1729bc072f7ecd0beb7b408162bb5fcf6374a4df3e2ab803d94ccf6497decc"),
        ).map { it.at(QWEN) } + listOf(
            F("code_predictor.onnx", 360_475L, "6077ceb8fd1dd2c9e4533d564d917b913f4a4bf1056ee946f99dcb56dff3234f"),
            F("code_predictor.onnx.data", 335_306_752L, "78b530db39646d04ce8dbe60ae35e814ba05d832f345d828ed6ad69483af8b43"),
        ).map { it.at(WAVEKAT + "int4/") } + listOf(
            F("talker_codec_embedding.npy", 12_583_040L, "47fa9e30f98b1528fc9b332d314f22a32fa33e187509a4d3537f8b2c31199e39"),
            F("cp_codec_embedding_0.npy", 8_388_736L, "2cf4b5d6f6fca977657bc82634eea275cd072036a6a16484febd41b5445abff1"),
            F("cp_codec_embedding_1.npy", 8_388_736L, "c8655ecd642f17d04a85ea37c2665205a8666843925227b25dc8c8bceb8015f5"),
            F("cp_codec_embedding_2.npy", 8_388_736L, "5436624853b3091bc1b88cf37a4d60af46346c508237d72e597bed561a5b2138"),
            F("cp_codec_embedding_3.npy", 8_388_736L, "1041b78ea5158c96cda4aae45bd21707a6fe8c92e6831715075d70d7b5b8e77a"),
            F("cp_codec_embedding_4.npy", 8_388_736L, "17af8770454ab26ca82f092d0d5a634680e4d14412d327fb8b6f94a57b256800"),
            F("cp_codec_embedding_5.npy", 8_388_736L, "b9abbf163b50ba0207e2196772c67b2e1025cf7f4fbe378c6a44123869253ad9"),
            F("cp_codec_embedding_6.npy", 8_388_736L, "1fb06414cb7d4edcd73142e79cbb4bd46f918517e5812b73345ade9535c6eafb"),
            F("cp_codec_embedding_7.npy", 8_388_736L, "08b8d7bd125d75ffa42f515153e66ade42cb16c2eea6ebe1935a2ac2509cbad0"),
            F("cp_codec_embedding_8.npy", 8_388_736L, "a2cf331bb3e2f2c0d44c91c55af045b9c975199331e4566a373b883ce04e9964"),
            F("cp_codec_embedding_9.npy", 8_388_736L, "389422edef8c0b6dbf6cdb48359a0274b11984c04668c004810866784dab2942"),
            F("cp_codec_embedding_10.npy", 8_388_736L, "bf17275629b464a4a38826beeb79b99e0e58cc02c530da2e572b9b7d9972fb2d"),
            F("cp_codec_embedding_11.npy", 8_388_736L, "dee4af40f34626a4951fba2b894120c8cf8293024ae068d1f2579f356c6e2284"),
            F("cp_codec_embedding_12.npy", 8_388_736L, "e307b70fbaddc6e7e48259b4e8c9417f24b155be9281205f38bbf40c64359951"),
            F("cp_codec_embedding_13.npy", 8_388_736L, "cc15bfc4290be77523f8f14c78b9b1710042b5d078291ee87038dfb7ecb3f185"),
            F("cp_codec_embedding_14.npy", 8_388_736L, "aa0de98365775e1cb968514595ff7e23936bea21c2bda6e5bab7e7052cdf97ba"),
        ).map { it.at(WAVEKAT + "embeddings/") } + TOKENIZER)
    }
}
