package kokomux.engine.qwen

import kokomux.engine.Audio
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Qwen3-TTS on this machine's onnxruntime, greedy, against a run of the same
 * files in Python (tools/qwen_ref.py, kept with its results in
 * src/test/resources/qwen_clone.json): a clip of Kokoro's bf_emma cloned with
 * its words, saying a new line. The files are in ../.dev/app/qwen/app (or
 * KOKOMUX_QWEN), as the app downloads them; the tokenizer's tables in
 * ../.dev/app/ming.
 */
class QwenTest {
    private val models = File(System.getProperty("kokomux.qwen"))
    private val tokenizer = File(System.getProperty("kokomux.ming"))

    private fun wav(file: File): FloatArray = RandomAccessFile(file, "r").use { f ->
        val bytes = ByteArray(f.length().toInt()).also { f.readFully(it) }
        val b = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        FloatArray(b.remaining()) { b.get(it) / 32768f }
    }

    @Test
    fun aClonedVoiceSaysTheLineAsPythonDoes() {
        assumeTrue("no Qwen export in $models", File(models, "talker_cache.onnx").exists())
        assumeTrue("no tokenizer tables in $tokenizer", File(tokenizer, "tokenizer_vocab.txt").exists())
        val want = JSONObject(javaClass.getResource("/qwen_clone.json")!!.readText())
        val clip = wav(File(javaClass.getResource("/qwen_clip.wav")!!.toURI()))
        Qwen(models, tokenizer).use { qwen ->
            val voice = qwen.clone(clip, want.getString("clip_words"))
            assertEquals(want.getInt("ref_frames"), voice.codes.size)
            val head = want.getJSONArray("ref_code_head")
            for (f in 0 until head.length()) {
                val row = head.getJSONArray(f)
                assertArrayEquals(LongArray(16) { row.getLong(it) }, voice.codes[f])
            }
            val prompt = qwen.prompt(want.getString("text"), "english", voice)
            assertEquals(want.getInt("prefill"), prompt.size)
            val t0 = System.nanoTime()
            val frames = qwen.frames(prompt, want.getInt("max"), null) { false }
            val secs = (System.nanoTime() - t0) / 1e9
            val expected = want.getJSONArray("codes")
            println("Qwen: ${frames.size} frames (${"%.1f".format(frames.size / Qwen.FPS)} s of speech) in ${"%.1f".format(secs)} s; Python made ${expected.length()}")
            // Two onnxruntimes (Python's and the JVM's) can round a near tie differently; the
            // first codes of every frame should agree almost everywhere, and the length closely.
            val same = (0 until minOf(frames.size, expected.length())).count { frames[it][0] == expected.getJSONArray(it).getLong(0) }
            assertTrue("$same of ${expected.length()} first codes agree", same >= expected.length() * 0.9)
            assertTrue(kotlin.math.abs(frames.size - expected.length()) <= 3)
            val pcm = qwen.decode(frames)
            assertEquals(frames.size * Qwen.HOP, pcm.size)
            Audio.writeWav(File(System.getProperty("java.io.tmpdir"), "kokomux-qwen.wav"), pcm, Qwen.RATE)
        }
    }

    @Test
    fun aVoiceKeepsWhatItsMadeOf() {
        val v = Clone(FloatArray(1024) { it / 1024f }, Array(3) { f -> LongArray(16) { (f * 16 + it).toLong() } }, "Some words.")
        val file = File.createTempFile("kokomux-voice", ".qwen")
        v.save(file)
        val back = Clone.load(file)
        assertArrayEquals(v.speaker, back.speaker, 0f)
        assertEquals(3, back.codes.size)
        assertArrayEquals(v.codes[2], back.codes[2])
        assertEquals("Some words.", back.words)
    }
}
