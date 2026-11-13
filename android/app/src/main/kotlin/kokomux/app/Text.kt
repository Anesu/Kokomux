package kokomux.app

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import java.io.File

/** A part of the text, and the pause after it in seconds. */
data class Part(val text: String, val pause: Double)

/**
 * The text side, in Python: engine.py's normalise and split_text, and misaki
 * (with espeak-ng for the words it doesn't know) for the sounds. Calls block;
 * make them off the main thread.
 */
class Text(private val context: Context) {
    private val module: PyObject by lazy {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        val data = espeakData()
        val lib = File(context.applicationInfo.nativeLibraryDir, "libespeak-ng.so")
        Python.getInstance().getModule("kokomux_android").also {
            it.callAttr("setup", lib.path, data.path, context.cacheDir.path)
        }
    }

    /** The parts [text] is read in. */
    fun parts(text: String, lang: String): List<Part> {
        val list = JSONArray(module.callAttr("parts", text, lang).toString())
        return (0 until list.length()).map { list.getJSONArray(it).let { p -> Part(p.getString(0), p.getDouble(1)) } }
    }

    /** A part's sounds, as misaki makes them. */
    fun sounds(part: String, lang: String): String = module.callAttr("sounds", part, lang).toString()

    /** Load misaki and spaCy for [lang]; the seconds it took. */
    fun warm(lang: String): Double = module.callAttr("warm", lang).toDouble()

    /** pronunciation.json's sentences through misaki here (see kokomux_android.check). */
    fun check(): String {
        val expected = context.assets.open("pronunciation.json").use { it.readBytes().toString(Charsets.UTF_8) }
        return module.callAttr("check", expected).toString()
    }

    /** espeak-ng's data, copied out of the app once per install (espeak reads it from a folder). */
    private fun espeakData(): File {
        val dir = File(context.filesDir, "espeak-ng-data")
        val stamp = File(dir, ".installed")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (stamp.exists() && stamp.readText() == version) return dir
        dir.deleteRecursively()
        copyAssets("espeak-ng-data", dir)
        stamp.writeText(version)
        return dir
    }

    private fun copyAssets(path: String, dest: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (child in children) copyAssets("$path/$child", File(dest, child))
    }
}
