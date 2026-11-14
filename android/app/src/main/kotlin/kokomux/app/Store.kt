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
}
