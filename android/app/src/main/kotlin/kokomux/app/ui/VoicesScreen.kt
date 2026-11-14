package kokomux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import kokomux.engine.VoiceId

/**
 * The voice picker (tui.sh's tui_voices), one tab per engine: Kokoro, Ming
 * 3.0, Qwen3-TTS. An engine not on the phone yet is one button that becomes
 * its download's progress. Kokoro's and Ming's voices come with a chip for
 * each language; Qwen's are the voices you've cloned, and a new one. ▶ hears
 * a voice say a line in its own language (its bars move while it speaks); a
 * tap chooses it. Opens on the voice in use.
 */
@Composable
fun VoicesScreen(state: UiState, actions: Actions, onBack: () -> Unit, onNewVoice: () -> Unit) {
    val t = LocalTokens.current
    var engine by rememberSaveable { mutableStateOf(Voices.engine(state.prefs.voice)) }
    Column(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopBar("Voice", onBack)
        Row(
            Modifier.widthIn(max = Space.column).fillMaxWidth().padding(horizontal = Space.l).padding(bottom = Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            for ((id, title) in Voices.engines) Chip(title, engine == id) { engine = id }
        }
        val files = state.engines.firstOrNull { it.id == engine }
        if (files != null && !files.ready) {
            EngineDownload(files, actions)
            return@Column
        }
        if (engine == VoiceId.QWEN) Cloned(state, actions, onBack, onNewVoice)
        else Builtin(state, engine, actions, onBack)
    }
}

/** An engine to download: its size on the button, then the download itself filling the button. */
@Composable
private fun EngineDownload(files: EngineFiles, actions: Actions) {
    val t = LocalTokens.current
    GoldenColumn(Modifier.fillMaxSize().padding(horizontal = Space.l)) {
        Logo(Space.xl, live = files.downloading)
        Spacer(Modifier.height(Space.l))
        Text(files.title, style = Type.title, color = t.fg)
        Spacer(Modifier.height(Space.l))
        when {
            files.downloading -> ProgressFill(files.done, files.total)
            else -> {
                if (files.error.isNotEmpty()) {
                    Text(files.error, style = Type.small, color = t.red)
                    Spacer(Modifier.height(Space.m))
                }
                MainButton((if (files.have > 0) "Carry on · " else "Download · ") + sizeLabel(files.total - files.have)) {
                    actions.fetchEngine(files.id)
                }
            }
        }
    }
}

/** Kokoro's or Ming's voices: a chip for each language (or all), the voices grouped by language. */
@Composable
private fun Builtin(state: UiState, engine: String, actions: Actions, onBack: () -> Unit) {
    var only by rememberSaveable(engine) { mutableStateOf("") }            // a language, or "" for all
    val voices = state.voices.filter { Voices.engine(it) == engine }
    val languages = voices.map { Voices.language(it) }.distinct()
    val groups = languages.filter { only.isEmpty() || it == only }.map { l -> l to voices.filter { Voices.language(it) == l } }
    // Where the voice in use is in the list (each group is its name, then its voices).
    val chosenAt = remember(groups) {
        var i = 0
        for ((_, vs) in groups) { i++; val k = vs.indexOf(state.prefs.voice); if (k >= 0) return@remember i + k; i += vs.size }
        0
    }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (chosenAt - 2).coerceAtLeast(0))
    if (languages.size > 1) LazyRow(
        Modifier.widthIn(max = Space.column).fillMaxWidth().padding(bottom = Space.s),
        contentPadding = PaddingValues(horizontal = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        item { Chip("All", only.isEmpty()) { only = "" } }
        items(languages) { l -> Chip(Voices.shortLanguage(l), only == l) { only = l } }
    }
    LazyColumn(
        Modifier.widthIn(max = Space.column).fillMaxWidth(), state = list,
        contentPadding = PaddingValues(start = Space.l, end = Space.l, bottom = Space.xxl),
    ) {
        for ((language, group) in groups) {
            item(key = "lang-$language") { SectionTitle(language) }
            items(group, key = { it }) { v ->
                VoiceRow(v, Voices.gender(v), chosen = v == state.prefs.voice, hearing = v == state.hearing,
                    onHear = { actions.hear(v) }, onChoose = { actions.voice(v); onBack() })
            }
        }
    }
}

/** The voices cloned with Qwen, and a new one. */
@Composable
private fun Cloned(state: UiState, actions: Actions, onBack: () -> Unit, onNewVoice: () -> Unit) {
    val voices = state.voices.filter { Voices.engine(it) == VoiceId.QWEN }
    LazyColumn(
        Modifier.widthIn(max = Space.column).fillMaxWidth(),
        contentPadding = PaddingValues(start = Space.l, end = Space.l, bottom = Space.xxl),
    ) {
        items(voices, key = { it }) { v ->
            VoiceRow(v, Voices.language(v), chosen = v == state.prefs.voice, hearing = v == state.hearing,
                onHear = { actions.hear(v) }, onChoose = { actions.voice(v); onBack() },
                onForget = { actions.forgetVoice(v) })
        }
        item(key = "new") {
            Spacer(Modifier.height(Space.s))
            ListRow(Glyphs.mic, "New voice", "Record or pick a clip", accent = true, onClick = onNewVoice)
        }
    }
}

/**
 * A voice: ▶ to hear it (its bars move while it speaks), a tap to choose it (the chosen one is
 * marked), and, for one you made, ⋮ to forget it.
 */
@Composable
private fun VoiceRow(voice: String, detail: String, chosen: Boolean, hearing: Boolean, onHear: () -> Unit,
                     onChoose: () -> Unit, onForget: (() -> Unit)? = null) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.touch).clip(Corner.m).background(if (chosen) t.sel else t.bg)
            .clickable(role = Role.Button, onClick = onChoose).padding(end = if (onForget == null) Space.m else 0.dp),
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
            if (chosen) OnSurface(t.sel) { Dim(detail, maxLines = 1) } else Dim(detail, maxLines = 1)
        }
        if (chosen) Icon(Glyphs.check, "Chosen", Modifier.size(Space.l - 3.dp), tint = t.accent)
        if (onForget != null) Box {
            var open by remember { mutableStateOf(false) }
            GlyphButton(Glyphs.more, "More") { open = true }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Forget ${Voices.name(voice)}", style = Type.label) },
                    leadingIcon = { Icon(Glyphs.trash, null) }, onClick = { open = false; onForget() })
            }
        }
    }
}
