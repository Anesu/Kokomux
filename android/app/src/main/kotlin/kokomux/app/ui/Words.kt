package kokomux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Your pronunciations: a word or name and how to say it, spelt as it sounds
 * ("Xiao Yan", "Shao Yen"). **Hear** says the respelling in your voice;
 * **Keep** has every book say the word that way from then on, its own letters
 * still the ones shown and lit. Tap one of yours to change it, × to forget it.
 */
@Composable
fun WordsScreen(state: UiState, actions: Actions, onBack: () -> Unit) {
    val t = LocalTokens.current
    var word by rememberSaveable { mutableStateOf("") }
    var sayAs by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopBar("Said your way", onBack)
        Column(
            Modifier.widthIn(max = Space.column).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l).padding(bottom = Space.xxl),
        ) {
            if (state.error.isNotEmpty()) {
                ErrorLine(state.error, actions::dismissError)
                Spacer(Modifier.height(Space.m))
            }
            Field(word, { word = it }, "A word or name")
            Spacer(Modifier.height(Space.s))
            Field(sayAs, { sayAs = it }, "Say it as")
            Spacer(Modifier.height(Space.m))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Pill(Glyphs.hear, "Hear", "Hear it said") { if (sayAs.isNotBlank()) actions.hearFix(sayAs) }
                Box(Modifier.weight(1f)) {
                    MainButton("Keep", enabled = word.isNotBlank() && sayAs.isNotBlank()) {
                        actions.setFix(word, sayAs)
                        word = ""
                        sayAs = ""
                    }
                }
            }
            if (state.fixes.isNotEmpty()) {
                SectionTitle("Yours")
                for (f in state.fixes) ListRow(
                    null, f.word, f.sayAs,
                    trailing = { GlyphButton(Glyphs.close, "Forget ${f.word}") { actions.removeFix(f.word) } },
                ) { word = f.word; sayAs = f.sayAs }
            }
        }
    }
}
