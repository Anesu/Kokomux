package kokomux.engine

import kotlin.math.max

/**
 * Word timings for a voice that doesn't give its own (Ming, Qwen): each piece
 * of a part's text was said on its own and spans its stretch of the audio, and
 * the piece's words share that stretch by their letters, plus a little for
 * each word's start. Written in the player's form (words.player_rows).
 */
object Estimate {
    /** Characters [from] until [to] of the part's text, said from [start] to [end] seconds into its audio. */
    class Piece(val from: Int, val to: Int, val start: Double, val end: Double)

    /** A word: letters and digits, with ' or - inside it; each Chinese, Japanese or Korean character on its own. */
    private val WORD = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]|[\\p{L}\\p{N}]+(?:['’\\-][\\p{L}\\p{N}]+)*")

    /** How much of a piece's time a word gets: one share to start it, and one per letter. */
    private fun weight(word: String) = 1 + word.length

    /** The rows for [text], said in [pieces]. */
    fun rows(text: String, pieces: List<Piece>): String {
        val out = StringBuilder("confidence\t1.0\n")
        val bytes = byteOffsets(text)
        for (p in pieces) {
            val words = WORD.findAll(text.substring(p.from, p.to)).toList()
            val total = words.sumOf { weight(it.value) }
            if (total == 0) continue
            var at = p.start
            val per = max(0.0, p.end - p.start) / total
            for (w in words) {
                val end = at + per * weight(w.value)
                val a = bytes[p.from + w.range.first]
                val b = bytes[p.from + w.range.last + 1]
                out.append(Math.round(at * 1000)).append('\t').append(Math.round(end * 1000)).append('\t')
                    .append(a).append('\t').append(b).append('\n')
                at = end
            }
        }
        return out.toString()
    }

    /**
     * Where pieces said separately sit in [text]: the piece [said] (as a voice's text front end gave
     * it, perhaps with a full stop added) ends where as many letters and digits have gone by in [text].
     * Returns the character offset after the piece, from [from] on.
     */
    fun end(text: String, from: Int, said: String): Int {
        var want = said.count { it.isLetterOrDigit() }
        var i = from
        while (i < text.length && want > 0) {
            if (text[i].isLetterOrDigit()) want--
            i++
        }
        // Take the punctuation and spaces that close the piece too.
        while (i < text.length && !text[i].isLetterOrDigit()) i++
        return i
    }

    /** The UTF-8 byte offset of every character of [text], and of its end. */
    private fun byteOffsets(text: String): IntArray {
        val out = IntArray(text.length + 1)
        var b = 0
        for (i in text.indices) {
            out[i] = b
            val c = text[i]
            b += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) -> 4
                Character.isLowSurrogate(c) -> 0
                else -> 3
            }
        }
        out[text.length] = b
        return out
    }
}
