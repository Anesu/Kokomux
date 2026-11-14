package kokomux.engine.ming

import kokomux.engine.Audio
import kokomux.engine.Estimate
import kokomux.engine.Said
import kokomux.engine.Speaker

/**
 * Ming saying a book's parts in a built-in voice: each part in the pieces
 * Ming's text front end makes, every piece's silence trimmed, the pauses
 * between them Kokoro's (a sentence's or a clause's), and word timings
 * estimated piece by piece.
 */
class MingSpeaker(private val ming: Ming, private val voice: String, private val lang: String) : Speaker {
    override fun say(text: String): Said {
        val t0 = System.nanoTime()
        val pieces = ming.pieces(text, lang)
        val audio = ArrayList<FloatArray>()
        val spans = ArrayList<Estimate.Piece>()
        var samples = 0
        var from = 0
        for ((i, piece) in pieces.withIndex()) {
            val raw = ming.say(piece, lang, voice, seed = (piece.hashCode().toLong() shl 8) + i)
            val (head, tail) = Audio.soundSpan(raw)
            val spoken = raw.copyOfRange(head, tail)
            val to = Estimate.end(text, from, piece)
            spans += Estimate.Piece(from, to, Audio.seconds(samples, RATE), Audio.seconds(samples + spoken.size, RATE))
            audio += spoken
            samples += spoken.size
            if (i < pieces.lastIndex) {
                val pause = Audio.silence(if (piece.trimEnd().lastOrNull() in SENTENCE_ENDS) SENTENCE else CLAUSE, RATE)
                audio += pause
                samples += pause.size
            }
            from = to
        }
        val t1 = System.nanoTime()
        return Said(Audio.join(audio), RATE, Estimate.rows(text, spans), voiceSecs = (t1 - t0) / 1e9)
    }

    companion object {
        const val RATE = Ming.RATE
        /** Kokoro's pauses (kokoro-onnx's), so a book sounds paced the same in either voice. */
        const val SENTENCE = 0.25
        const val CLAUSE = 0.1
        private val SENTENCE_ENDS = setOf('.', '!', '?', '…', '。', '！', '？')
    }
}
