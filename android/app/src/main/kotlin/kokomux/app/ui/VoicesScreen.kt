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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
 * The voice picker (tui.sh's tui_voices): type to filter ("bf" is British and
 * female, "emma" finds Emma), the voices grouped by language, the speaker to
 * hear one say a line in its own language, and a tap to choose it.
 */
@Composable
fun VoicesScreen(state: UiState, actions: Actions, onBack: () -> Unit) {
    val t = LocalTokens.current
    var filter by rememberSaveable { mutableStateOf("") }
    val shown = state.voices.filter { v ->
        val f = filter.trim().lowercase()
        f.isEmpty() || v.startsWith(f) || v.contains(f) || Voices.describe(v).lowercase().contains(f)
    }
    Column(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopBar("Voice", onBack)
        Column(Modifier.widthIn(max = Space.column).fillMaxWidth().padding(horizontal = Space.l)) {
            OutlinedTextField(
                value = filter, onValueChange = { filter = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Glyphs.search, null, Modifier.size(Space.l - 3.dp), tint = t.dim) },
                placeholder = { Text("Filter: bf is British and female", style = Type.body, color = t.line) },
                shape = Corner.l, textStyle = Type.body,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = t.accent, unfocusedBorderColor = t.line),
            )
            Dim("${shown.size} of ${state.voices.size} voices · the speaker plays a sample",
                Modifier.padding(top = Space.s))
        }
        LazyColumn(
            Modifier.widthIn(max = Space.column).fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = Space.l, end = Space.l, bottom = Space.xxl),
        ) {
            for ((letter, language) in Voices.languages) {
                val group = shown.filter { it.firstOrNull() == letter }
                if (group.isEmpty()) continue
                item(key = "lang-$letter") { SectionTitle(language, "${group.size}") }
                items(group, key = { it }) { v ->
                    VoiceRow(v, chosen = v == state.prefs.voice, hearing = v == state.hearing,
                        onHear = { actions.hear(v) }, onChoose = { actions.voice(v); onBack() })
                }
            }
        }
    }
}

/** A voice: hear it, or choose it (the chosen one is marked). */
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
        ) { Icon(Glyphs.hear, null, Modifier.size(Space.l - 3.dp), tint = if (hearing) t.accent else t.fg) }
        Column(Modifier.weight(1f)) {
            Text(Voices.name(voice), style = Type.label, color = t.fg)
            Dim(if (hearing) "Playing a sample…" else "${Voices.gender(voice)} · $voice", maxLines = 1)
        }
        if (chosen) Icon(Glyphs.check, "Chosen", Modifier.size(Space.l - 3.dp), tint = t.accent)
    }
}
