package kokomux.engine

/**
 * Cutting a part's sounds into batches the model takes in one pass (510 at
 * most), the way kokoro-onnx 0.6.1's chunker does: at a sentence end, else a
 * clause, else a word, else anywhere; then balanced, so no batch is a short tail.
 */
internal object Chunker {
    const val SENTENCE_MARKS = ".!?…"
    const val CLAUSE_MARKS = ",;:"

    private val boundaries = listOf(
        Regex("(?<=[.!?…])\\s+"),
        Regex("(?<=[,;:])\\s+"),
        Regex("\\s+"),
    )

    /** Pieces of [phonemes], each no longer than [max]. */
    private fun atoms(phonemes: String, max: Int, level: Int, out: MutableList<String>) {
        if (phonemes.length <= max) {
            if (phonemes.isNotEmpty()) out += phonemes
            return
        }
        for (index in level until boundaries.size) {
            val pieces = boundaries[index].split(phonemes)
            if (pieces.size > 1) {
                for (piece in pieces) atoms(piece.trim(), max, index + 1, out)
                return
            }
        }
        var start = 0
        while (start < phonemes.length) {
            out += phonemes.substring(start, minOf(start + max, phonemes.length))
            start += max
        }
    }

    /** Consecutive atoms grouped into batches within [limit], as index ranges. */
    private fun pack(lengths: List<Int>, limit: Int): List<IntRange> {
        val batches = mutableListOf<IntRange>()
        var start = 0
        var size = 0
        for ((index, length) in lengths.withIndex()) {
            val candidate = if (index == start) length else size + 1 + length
            if (candidate > limit && index > start) {
                batches += start until index
                start = index
                size = length
            } else {
                size = candidate
            }
        }
        if (lengths.isNotEmpty()) batches += start until lengths.size
        return batches
    }

    /** [phonemes] in evenly sized batches of at most [max]. */
    fun split(phonemes: String, max: Int = Kokoro.MAX_SOUNDS): List<String> {
        val atoms = mutableListOf<String>()
        atoms(phonemes.trim(), max, 0, atoms)
        if (atoms.isEmpty()) return emptyList()
        val lengths = atoms.map { it.length }
        val fewest = pack(lengths, max).size
        var low = lengths.max()
        var high = max
        while (low < high) {
            val middle = (low + high) / 2
            if (pack(lengths, middle).size <= fewest) high = middle else low = middle + 1
        }
        return pack(lengths, low).map { range -> range.joinToString(" ") { atoms[it] } }
    }

    /** Seconds of silence after a batch ending with this text. */
    fun pauseAfter(phonemes: String, sentence: Double, clause: Double): Double {
        val mark = phonemes.trimEnd().lastOrNull() ?: return 0.0
        return when (mark) {
            in SENTENCE_MARKS -> sentence
            in CLAUSE_MARKS -> clause
            else -> 0.0
        }
    }
}
