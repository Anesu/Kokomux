package kokomux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Getting ready, the first time: what's needed and why, one button, then how
 * far it's got. On the optical centre, as the Termux installer's screens.
 */
@Composable
fun FirstRun(setup: Setup, actions: Actions) {
    val t = LocalTokens.current
    GoldenColumn(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding()) {
        Logo(Space.xxl)
        Spacer(Modifier.height(Space.l))
        Text("kokomux", style = Type.display, color = t.accent)
        Dim("Kokoro text-to-speech, on this phone", style = Type.body)
        Spacer(Modifier.height(Space.xl))
        Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
            when (setup) {
                Setup.Missing -> {
                    Text("Kokoro's voice model and its 54 voices come from Hugging Face and GitHub, once: 354 MB. " +
                        "After that, everything happens on the phone.", style = Type.body, color = t.fg)
                    MainButton("Download Kokoro (354 MB)", onClick = actions::download)
                }
                is Setup.Downloading -> {
                    Text("Downloading ${setup.label}…", style = Type.body, color = t.fg)
                    Track(if (setup.total > 0) setup.done.toFloat() / setup.total else 0f)
                    Dim("${setup.done / 1_000_000} of ${setup.total / 1_000_000} MB")
                }
                is Setup.Loading -> {
                    Text("Loading ${setup.what}…", style = Type.body, color = t.fg)
                    Dim("The first time takes longest: the app unpacks its Python packages.")
                }
                is Setup.Failed -> {
                    Text(setup.message, style = Type.body, color = t.red)
                    MainButton("Try again", onClick = actions::download)
                }
                Setup.Ready -> {}
            }
        }
    }
}
