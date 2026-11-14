package kokomux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Getting ready, the first time, on the optical centre: the mark and the
 * name, then one button that becomes its own progress bar while Kokoro
 * downloads; the mark's bars move while it loads.
 */
@Composable
fun FirstRun(setup: Setup, actions: Actions) {
    val t = LocalTokens.current
    GoldenColumn(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding()) {
        Logo(Space.xxl, live = setup is Setup.Downloading || setup is Setup.Loading)
        Spacer(Modifier.height(Space.l))
        Text("kokomux", style = Type.display, color = t.accent)
        Spacer(Modifier.height(Space.xl))
        when (setup) {
            Setup.Missing -> MainButton("Download Kokoro · 354 MB", onClick = actions::download)
            is Setup.Downloading -> ProgressFill(setup.done, setup.total)
            is Setup.Loading -> Dim("Loading ${setup.what}…", style = Type.body)
            is Setup.Failed -> {
                Text(setup.message, style = Type.body, color = t.red)
                Spacer(Modifier.height(Space.m))
                MainButton("Try again", onClick = actions::download)
            }
            Setup.Ready -> {}
        }
    }
}
