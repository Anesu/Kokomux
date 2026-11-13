package kokomux.engine

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Kokoro's voices: voices-v1.0.bin, a numpy .npz holding one (510, 1, 256)
 * float32 array per voice. Row n - 1 is the style for a part of n sounds.
 */
class Voices private constructor(private val styles: Map<String, FloatArray>) {
    /** Every voice's name, sorted. */
    val names: List<String> = styles.keys.sorted()

    /** The style vector for a part of [sounds] tokens, as kokoro-onnx picks it. */
    fun style(voice: String, sounds: Int): FloatArray {
        val all = styles[voice] ?: throw IllegalArgumentException("Unknown voice '$voice'")
        val row = minOf(sounds, ROWS) - 1
        return all.copyOfRange(row * WIDTH, (row + 1) * WIDTH)
    }

    companion object {
        const val ROWS = 510
        const val WIDTH = 256

        /** Read every voice in the file. */
        fun read(file: File): Voices = ZipFile(file).use { zip ->
            val styles = zip.entries().asSequence()
                .filter { it.name.endsWith(".npy") }
                .associate { entry ->
                    entry.name.removeSuffix(".npy") to npy(zip.getInputStream(entry).use { it.readBytes() }, entry.name)
                }
            Voices(styles)
        }

        /** A little-endian float32 .npy array of ROWS × 1 × WIDTH, flattened. */
        private fun npy(bytes: ByteArray, name: String): FloatArray {
            require(bytes.size > 10 && bytes[0] == 0x93.toByte() && String(bytes, 1, 5, Charsets.US_ASCII) == "NUMPY") {
                "$name isn't a numpy array"
            }
            val major = bytes[6].toInt()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val headerLen = if (major == 1) buf.getShort(8).toInt() and 0xffff else buf.getInt(8)
            val start = if (major == 1) 10 else 12
            val header = String(bytes, start, headerLen, Charsets.ISO_8859_1)
            require("'<f4'" in header && "'fortran_order': False" in header) { "$name: unexpected array type ($header)" }
            val shape = Regex("""'shape': \(([^)]*)\)""").find(header)?.groupValues?.get(1)
                ?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
            require(shape == listOf(ROWS, 1, WIDTH)) { "$name: unexpected shape $shape" }
            val out = FloatArray(ROWS * WIDTH)
            buf.position(start + headerLen)
            buf.asFloatBuffer().get(out)
            return out
        }
    }
}
