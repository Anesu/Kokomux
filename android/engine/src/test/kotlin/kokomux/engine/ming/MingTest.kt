package kokomux.engine.ming

import kokomux.engine.Estimate
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Ming's host side on this machine: its prompts against the official tokenizer
 * (src/test/resources/ming_prompts.json, made with tokenizers on Ming's
 * tokenizer.json), and its loop over stand-in graphs. The graphs themselves run
 * only on the phone's NPU. The host files are in ../.dev/app/ming (or
 * KOKOMUX_MING): Ming's tokenizer tables and small tensors, about 15 MB.
 */
class MingTest {
    private val dir = File(System.getProperty("kokomux.ming"))

    private fun need() = assumeTrue("no Ming host files in $dir", File(dir, "tokenizer_vocab.txt").exists())

    @Test
    fun promptsAreTheOfficialTokenizers() {
        need()
        val cc = CharClasses.load(File(dir, "char_classes.bin"))
        val tok = QwenTokenizer(File(dir, "tokenizer_vocab.txt"), File(dir, "tokenizer_merges.txt"),
            File(dir, "tokenizer_added.txt"), cc)
        val cases = JSONObject(javaClass.getResource("/ming_prompts.json")!!.readText()).getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val want = c.getJSONArray("ids").let { a -> IntArray(a.length()) { a.getInt(it) } }
            val got = PromptContract.buildInputIds(tok, PromptContract.ttsPrompt(c.getString("lang")), c.getString("text"),
                PromptContract.ipInstruction(c.getString("voice"))).ids
            assertArrayEquals(c.getString("text"), want, got)
        }
    }

    /** Graphs that answer in the right shapes: the step's z points where the stop head says stop, from frame [stopAt]. */
    private class Stand(dir: File, private val stopAt: Int) {
        val calls = HashMap<String, Int>()
        private val w = HostMath.readF32(File(dir, "stop_head.bin"))
        private var steps = 0

        fun graph(name: String, outs: List<Int>) = object : GraphCall {
            override fun call(sig: String, inputs: Array<FloatArray>, read: IntArray?): Array<FloatArray?> {
                calls[name] = (calls[name] ?: 0) + 1
                val o = outs.map { FloatArray(it) }.toTypedArray<FloatArray?>()
                if (name == "step" && steps++ >= stopAt) {
                    for (j in 0 until HostMath.HIDDEN) o[0]!![j] = 10f * (w[HostMath.HIDDEN + j] - w[j])
                }
                return o
            }
            override fun close() {}
        }

        fun graphs(): MingGraphs {
            val kv = HostMath.KV * 64 * 64
            return MingGraphs(
                graph("prefill", listOf(64 * HostMath.HIDDEN, kv, kv)),
                graph("step", listOf(HostMath.HIDDEN, HostMath.KV * 64, HostMath.KV * 64)),
                graph("flow", listOf(64)), graph("agg", listOf(HostMath.HIDDEN)),
                graph("dec", listOf(64 * HostMath.HEAD_WIDTH, kv, kv)),
            )
        }
    }

    @Test
    fun aPieceRunsPrefillThenAFramePerLatentThenTheDecoder() {
        need()
        val stand = Stand(dir, stopAt = 20)
        // (embed_tokens is 272 MB; a stand-in table of zeros the same size is enough here)
        val d = File(System.getProperty("java.io.tmpdir"), "kokomux-ming-host").apply { mkdirs() }
        for (f in dir.listFiles()!!) if (!File(d, f.name).exists()) f.copyTo(File(d, f.name))
        val embed = File(d, "embed_tokens_f16.bin")
        if (embed.length() != 151936L * HostMath.HIDDEN * 2) java.io.RandomAccessFile(embed, "rw").use { it.setLength(151936L * HostMath.HIDDEN * 2) }
        Ming(d, stand.graphs()).use { ming ->
            val pieces = ming.pieces("Hello there. This is a test of the loop.", "en")
            assertTrue(pieces.isNotEmpty())
            val pcm = ming.say(pieces[0], "en", "US_Emma", seed = 1)
            assertEquals(3, stand.calls["prefill"])                 // a 160-odd token prompt in 64-token chunks
            assertEquals(21, stand.calls["step"])                    // frames 0..20: the stop head says stop at 20 (> 3)
            assertEquals(21, stand.calls["flow"])
            assertEquals(20, stand.calls["agg"])                     // not after the last
            assertEquals(2, stand.calls["dec"])                      // 16 latents, then the last 5
            assertTrue(pcm.size in 21 * 3528 - 3 * 1323..21 * 3528)  // 4 frames of 882 a latent, less the ISTFT's trim
        }
    }

    @Test
    fun wordsShareTheirPiecesTime() {
        val text = "Hello there, world. Next one!"
        val first = Estimate.end(text, 0, "Hello there, world.")
        assertEquals(20, first)
        val rows = Estimate.rows(text, listOf(Estimate.Piece(0, first, 0.0, 1.6), Estimate.Piece(first, text.length, 1.85, 2.5)))
            .trim().lines()
        assertEquals("confidence\t1.0", rows[0])
        val words = rows.drop(1).map { it.split('\t').map(String::toLong) }
        assertEquals(5, words.size)
        assertEquals(listOf(0L, 533L, 0L, 5L), words[0])            // "Hello": 6 of 6 + 6 + 6 shares of 1.6 s
        assertEquals(listOf(1067L, 1600L, 13L, 18L), words[2])      // "world" ends its piece
        assertEquals(1850L, words[3][0])                            // "Next" starts the next piece
        assertEquals(2500L, words[4][1])
    }
}
