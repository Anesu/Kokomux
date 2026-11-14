package kokomux.app

import android.content.Context
import kokomux.app.ui.Prefs
import kokomux.app.ui.ThemeMode

/** The settings, kept under the Termux version's names (KOKORO_VOICE, KOKORO_SPEED, …). */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = Prefs(
        voice = prefs.getString("KOKORO_VOICE", null) ?: "af_heart",
        speed = prefs.getFloat("KOKORO_SPEED", 1f),
        theme = runCatching { ThemeMode.valueOf(prefs.getString("KOKORO_THEME", "auto")!!.uppercase()) }.getOrDefault(ThemeMode.AUTO),
        focus = prefs.getBoolean("KOKORO_FOCUS", true),
        words = prefs.getBoolean("KOKORO_WORDS", true),
        offsetMs = prefs.getInt("KOKORO_WORDS_OFFSET_MS", 0),
        threads = prefs.getInt("KOKORO_THREADS", 0),
        block = prefs.getInt("KOKORO_BLOCK", 0),
    )

    fun save(p: Prefs) {
        prefs.edit()
            .putString("KOKORO_VOICE", p.voice).putFloat("KOKORO_SPEED", p.speed)
            .putString("KOKORO_THEME", p.theme.name.lowercase()).putBoolean("KOKORO_FOCUS", p.focus)
            .putBoolean("KOKORO_WORDS", p.words).putInt("KOKORO_WORDS_OFFSET_MS", p.offsetMs)
            .putInt("KOKORO_THREADS", p.threads).putInt("KOKORO_BLOCK", p.block)
            .apply()
    }

    /** No setting has been changed on this phone yet. */
    val fresh get() = prefs.all.isEmpty()

    /** The settings as they're kept, each as text (for a backup). */
    fun values(): Map<String, String> = prefs.all.mapValues { it.value.toString() }

    /** Keep the settings a backup had ([values]'s), any it lacks left as they come. */
    fun take(values: Map<String, String>) {
        val d = Prefs()
        save(Prefs(
            voice = values["KOKORO_VOICE"] ?: d.voice,
            speed = values["KOKORO_SPEED"]?.toFloatOrNull()?.coerceIn(0.5f, 2f) ?: d.speed,
            theme = runCatching { ThemeMode.valueOf(values["KOKORO_THEME"]!!.uppercase()) }.getOrDefault(d.theme),
            focus = values["KOKORO_FOCUS"]?.toBooleanStrictOrNull() ?: d.focus,
            words = values["KOKORO_WORDS"]?.toBooleanStrictOrNull() ?: d.words,
            offsetMs = values["KOKORO_WORDS_OFFSET_MS"]?.toIntOrNull() ?: d.offsetMs,
            threads = values["KOKORO_THREADS"]?.toIntOrNull() ?: d.threads,
            block = values["KOKORO_BLOCK"]?.toIntOrNull() ?: d.block,
        ))
    }
}
