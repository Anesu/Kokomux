package kokomux.engine

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** numpy's .npy files: a little-endian float32 array, flattened. */
object Npy {
    fun floats(file: File): FloatArray = floats(file.readBytes(), file.name)

    fun floats(bytes: ByteArray, name: String): FloatArray {
        require(bytes.size > 10 && bytes[0] == 0x93.toByte() && String(bytes, 1, 5, Charsets.US_ASCII) == "NUMPY") {
            "$name isn't a numpy array"
        }
        val major = bytes[6].toInt()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val headerLen = if (major == 1) buf.getShort(8).toInt() and 0xffff else buf.getInt(8)
        val start = if (major == 1) 10 else 12
        val header = String(bytes, start, headerLen, Charsets.ISO_8859_1)
        require("'<f4'" in header && "'fortran_order': False" in header) { "$name: unexpected array type ($header)" }
        val out = FloatArray((bytes.size - start - headerLen) / 4)
        buf.position(start + headerLen)
        buf.asFloatBuffer().get(out)
        return out
    }
}
