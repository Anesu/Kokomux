package kokomux.engine

/** A part said: its samples at [rate], its word timings in the player's form (words.player_rows), and how long it took. */
class Said(val samples: FloatArray, val rate: Int, val rows: String, val prepSecs: Double = 0.0, val voiceSecs: Double = 0.0) {
    val seconds get() = Audio.seconds(samples.size, rate)

    /** Ready to play: softened edges and the pause after it (engine.py's run_job). */
    fun withPause(pause: Double): FloatArray =
        Audio.join(listOf(Audio.softenEdges(samples, rate), Audio.silence(pause, rate)))
}

/** A voice that says a book's parts: Kokoro through misaki, Ming, or a voice cloned with Qwen. */
fun interface Speaker {
    fun say(text: String): Said
}

/**
 * Kokoro saying parts as engine.py has it: misaki's sounds from the text side,
 * Kokoro's speech ([speak]), and each word's timing from the sounds it spoke.
 */
class KokoroSpeaker(private val text: TextSide, private val lang: String, private val speak: (sounds: String) -> Speech) : Speaker {
    override fun say(text: String): Said {
        val t0 = System.nanoTime()
        val sounds = this.text.speakable(text, lang)
        val t1 = System.nanoTime()
        val speech = speak(sounds.sounds)
        val t2 = System.nanoTime()
        return Said(speech.samples, Audio.RATE, this.text.wordRows(text, lang, sounds, speech.timings),
            (t1 - t0) / 1e9, (t2 - t1) / 1e9)
    }
}
