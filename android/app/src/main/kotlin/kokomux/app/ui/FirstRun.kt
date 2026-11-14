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
            is Setup.Downloading -> {
                val share = if (setup.total > 0) setup.done.toFloat() / setup.total else 0f
                Box(
                    Modifier.fillMaxWidth().heightIn(min = Space.touch).clip(Corner.l).background(t.surf)
                        .semantics { contentDescription = "Downloading, ${(share * 100).toInt()}%" },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.matchParentSize()) { Box(Modifier.fillMaxHeight().fillMaxWidth(share).background(Tokens.mix(t.surf, t.accent, Phi.inv(2f)))) }
                    Text("${setup.done / 1_000_000} of ${setup.total / 1_000_000} MB", style = Type.label, color = t.fg)
                }
            }
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
