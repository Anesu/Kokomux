package kokomux.app

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Kokoro's model and voices, downloaded on first run into the app's own
 * storage from the same addresses engine.py's `download` uses, resuming a
 * download that was cut off.
 */
class Models(private val dir: File) {
    class Item(val name: String, val url: String, val label: String)

    val model = File(dir, MODEL.name)
    val voices = File(dir, VOICES.name)

    /** Both files are there. */
    fun ready() = model.length() > 0 && voices.length() > 0

    /** Fetch whatever is missing; [progress] gets (label, bytes so far, total bytes). */
    fun download(progress: (String, Long, Long) -> Unit) {
        dir.mkdirs()
        for (item in listOf(MODEL, VOICES)) fetch(item, progress)
    }

    private fun fetch(item: Item, progress: (String, Long, Long) -> Unit) {
        val dest = File(dir, item.name)
        if (dest.length() > 0) return
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
                if (conn.responseCode !in 200..299) throw IOException("${item.label}: HTTP ${conn.responseCode}")
                val start = if (resumed) have else 0L
                val total = start + conn.contentLengthLong
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(part, resumed).use { out ->
                        val buf = ByteArray(1 shl 18)
                        var done = start
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            progress(item.label, done, total)
                        }
                    }
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
        // engine.py's MODEL_SRC and VOICES_SRC (Kokoro 82M v1.0, fp32 with durations, pinned to a commit).
        val MODEL = Item(
            "kokoro-v1.0-timestamped.fp32.onnx",
            "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX-timestamped/resolve/" +
                "dd4401a9add81ac692d20e240d22ec9dda82cc29/onnx/model.onnx",
            "Kokoro",
        )
        val VOICES = Item(
            "voices-v1.0.bin",
            "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin",
            "Voices",
        )
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    }
}
