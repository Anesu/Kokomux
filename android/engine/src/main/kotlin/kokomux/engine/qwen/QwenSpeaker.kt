package kokomux.engine.qwen

import kokomux.engine.Audio
import kokomux.engine.Estimate
import kokomux.engine.Said
import kokomux.engine.Speaker
import java.util.Random

/**
 * A cloned voice saying a book's parts: each sentence said on its own (a fresh
 * prompt, so a long part can't drift), its silence trimmed, a sentence's pause
 * between them, and word timings estimated sentence by sentence.
 */
class QwenSpeaker(private val qwen: Qwen, private val voice: Clone, private val lang: String,
                  private val random: Random = Random()) : Speaker {
    override fun say(text: String): Said {
        val t0 = System.nanoTime()
        val audio = ArrayList<FloatArray>()
        val spans = ArrayList<Estimate.Piece>()
        var samples = 0
        val sentences = SENTENCE.findAll(text).map { it.range }.filter { r -> text.substring(r).any { it.isLetterOrDigit() } }.toList()
        for ((i, r) in sentences.withIndex()) {
            val raw = qwen.say(text.substring(r).trim(), lang, voice, random)
            val (head, tail) = Audio.soundSpan(raw)
            val spoken = raw.copyOfRange(head, tail)
            spans += Estimate.Piece(r.first, r.last + 1, Audio.seconds(samples, RATE), Audio.seconds(samples + spoken.size, RATE))
            audio += spoken
            samples += spoken.size
            if (i < sentences.lastIndex) {
                val pause = Audio.silence(PAUSE, RATE)
                audio += pause
                samples += pause.size
            }
        }
        return Said(Audio.join(audio), RATE, Estimate.rows(text, spans), voiceSecs = (System.nanoTime() - t0) / 1e9)
    }

    companion object {
        const val RATE = Qwen.RATE
        /** Kokoro's pause after a sentence. */
        const val PAUSE = 0.25
        /** A sentence: up to its end mark (and any closing quote), or the end of the part. */
        private val SENTENCE = Regex("[^.!?…。！？]+(?:[.!?…。！？]+[\"'”’)\\]]*|$)")

        /** Qwen's name for a language kokomux reads in (Kokoro's codes); null: Qwen works it out. */
        fun language(lang: String): String? = when {
            lang.startsWith("en") -> "english"
            lang.startsWith("es") -> "spanish"
            lang.startsWith("fr") -> "french"
            lang.startsWith("it") -> "italian"
            lang.startsWith("pt") -> "portuguese"
            lang.startsWith("ja") -> "japanese"
            lang == "cmn" || lang.startsWith("zh") -> "chinese"
            lang.startsWith("de") -> "german"
            lang.startsWith("ko") -> "korean"
            lang.startsWith("ru") -> "russian"
            else -> null
        }
    }
}
