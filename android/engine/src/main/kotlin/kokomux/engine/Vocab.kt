package kokomux.engine

/** Kokoro's sounds and their token ids: kokoro-onnx 0.6.1's config.json (the model carries none). */
internal object Vocab {
    private const val SYMBOLS = ";:,.!?—…\u0022()“” \u0303ʣʥʦʨᵝꭧAIOQSTWYᵊabcdefhijklmnopqrstuvwxyzɑɐɒæβɔɕçɖðʤəɚɛɜɟɡɥɨɪʝɯɰŋɳɲɴøɸθœɹɾɻʁɽʂʃʈʧʊʋʌɣɤχʎʒʔˈˌːʰʲ↓→↗↘ᵻ"
    private val IDS = intArrayOf(1, 2, 3, 4, 5, 6, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 31, 33, 35, 36, 39, 41, 42, 43, 44, 45, 46, 47, 48, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 75, 76, 77, 78, 80, 81, 82, 83, 85, 86, 87, 90, 92, 99, 101, 102, 103, 110, 111, 112, 113, 114, 115, 116, 118, 119, 120, 123, 125, 126, 128, 129, 130, 131, 132, 133, 135, 136, 138, 139, 140, 142, 143, 147, 148, 156, 157, 158, 162, 164, 169, 171, 172, 173, 177)
    private val ids: Map<Char, Int> = SYMBOLS.indices.associate { SYMBOLS[it] to IDS[it] }

    /** The token ids of the sounds Kokoro knows, in order; anything else is dropped. */
    fun tokenize(phonemes: String): IntArray = phonemes.mapNotNull { ids[it] }.toIntArray()

    /** The sounds that survive [tokenize], one for each token. */
    fun known(phonemes: String): String = phonemes.filter { it in ids }
}
