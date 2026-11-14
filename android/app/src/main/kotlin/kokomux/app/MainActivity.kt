package kokomux.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import kokomux.app.ui.KokomuxApp
import kokomux.app.ui.ThemeMode

/**
 * The app's one activity: the screens (ui.KokomuxApp) over the AppModel. What's
 * shared to it (a page's address, some text, a book) or opened with it is read.
 */
class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The notification is where the controls are while the screen is off.
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            val state = model.state()
            // The status and navigation bars' icons follow the app's colours, not only the phone's.
            val dark = when (state.prefs.theme) {
                ThemeMode.AUTO -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(bars, bars)
            }
            KokomuxApp(state, model::position, model::link, model)
        }
        if (savedInstanceState == null) take(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    /** Read what was shared or opened: a book's file, an address, or text. */
    private fun take(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let(model::openFile)
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val file = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                val link = Regex("https?://\\S+").find(text)?.value
                when {
                    file != null -> model.openFile(file)
                    link != null && text.length < link.length + 140 -> model.openPage(link)   // a link, perhaps with its title
                    text.isNotEmpty() -> model.readTyped(text)
                }
            }
            else -> intent.getStringExtra(RESUME)?.let(model::resume)
        }
    }

    companion object {
        /** An intent's extra: carry on with this book (the widget's play, when nothing is playing). */
        const val RESUME = "kokomux.RESUME"
    }

    override fun onResume() {
        super.onResume()
        model.refreshShelf()
    }
}
