package kokomux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The voice picker (tui.sh's tui_voices): a chip for each language (or all of
 * them), the voices grouped by language, ▶ to hear one say a line in its own
 * language (its bars move while it speaks), and a tap to choose it. Opens at
 * the voice in use.
 */
@Composable
fun VoicesScreen(state: UiState, actions: Actions, onBack: () -> Unit) {
    val t = LocalTokens.current
    var only by rememberSaveable { mutableStateOf("") }          // a language's letter, or "" for all
    val languages = Voices.languages.filter { (letter, _) -> state.voices.any { it.firstOrNull() == letter } }
    val groups = languages.filter { only.isEmpty() || it.first == only[0] }
        .map { (letter, name) -> name to state.voices.filter { it.firstOrNull() == letter } }
    // Where the voice in use is in the list (each group is its name, then its voices).
    val chosenAt = remember(groups) {
        var i = 0
        for ((_, vs) in groups) { i++; val k = vs.indexOf(state.prefs.voice); if (k >= 0) return@remember i + k; i += vs.size }
        0
    }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (chosenAt - 2).coerceAtLeast(0))
    Column(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopBar("Voice", onBack)
        LazyRow(
            Modifier.widthIn(max = Space.column).fillMaxWidth().padding(bottom = Space.s),
            contentPadding = PaddingValues(horizontal = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            item { LanguageChip("All", only.isEmpty()) { only = "" } }
            items(languages) { (letter, name) -> LanguageChip(Voices.shortLanguage(name), only == "$letter") { only = "$letter" } }
        }
        LazyColumn(
            Modifier.widthIn(max = Space.column).fillMaxWidth().weight(1f), state = list,
            contentPadding = PaddingValues(start = Space.l, end = Space.l, bottom = Space.xxl),
        ) {
            for ((language, group) in groups) {
                item(key = "lang-$language") { SectionTitle(language) }
                items(group, key = { it }) { v ->
                    VoiceRow(v, chosen = v == state.prefs.voice, hearing = v == state.hearing,
                        onHear = { actions.hear(v) }, onChoose = { actions.voice(v); onBack() })
                }
            }
        }
    }
}

/** A language to show only its voices; the one in effect filled. */
@Composable
private fun LanguageChip(label: String, on: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    Box(
        Modifier.heightIn(min = 48.dp).clip(CircleShape).selectable(selected = on, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Type.label, color = if (on) t.fg else t.dim,
            modifier = Modifier.clip(CircleShape).background(if (on) t.psel else t.surf)
                .padding(horizontal = Space.m, vertical = Space.s))
    }
}

/** A voice: ▶ to hear it (its bars move while it speaks), or a tap to choose it (the chosen one is marked). */
@Composable
private fun VoiceRow(voice: String, chosen: Boolean, hearing: Boolean, onHear: () -> Unit, onChoose: () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.touch).clip(Corner.m).background(if (chosen) t.sel else t.bg)
            .clickable(role = Role.Button, onClick = onChoose).padding(end = Space.m),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Box(Modifier.width(3.dp).heightIn(min = Space.l).clip(CircleShape).background(if (chosen) t.accent else t.bg))
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(if (hearing) t.psel else t.surf)
                .clickable(role = Role.Button, onClick = onHear).semantics { contentDescription = "Hear ${Voices.name(voice)}" },
            contentAlignment = Alignment.Center,
        ) {
            if (hearing) Logo(Space.m + 3.dp, live = true)
            else Icon(Glyphs.play, null, Modifier.size(Space.l - 3.dp), tint = t.fg)
        }
        Column(Modifier.weight(1f)) {
            Text(Voices.name(voice), style = Type.label, color = t.fg)
            if (chosen) OnSurface(t.sel) { Dim(Voices.gender(voice), maxLines = 1) } else Dim(Voices.gender(voice), maxLines = 1)
        }
        if (chosen) Icon(Glyphs.check, "Chosen", Modifier.size(Space.l - 3.dp), tint = t.accent)
    }
}
