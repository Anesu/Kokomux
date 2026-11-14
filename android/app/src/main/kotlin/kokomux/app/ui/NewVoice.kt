package kokomux.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kokomux.engine.Books

/**
 * Making a voice with Qwen: the language it reads, a passage in it to read
 * aloud (or a clip picked from the phone), the clip played back, its words
 * (the passage, when it was read), a name, and Make. The clip's words let
 * Qwen carry on from the clip itself, which sounds the most like it.
 */
@Composable
fun NewVoiceScreen(state: UiState, actions: Actions, onBack: () -> Unit) {
    val t = LocalTokens.current
    var lang by rememberSaveable { mutableStateOf(Books.langOf(state.prefs.voice).let { l ->
        Voices.cloneLanguages.firstOrNull { it.first == l }?.first ?: "en-us" }) }
    var picked by rememberSaveable { mutableStateOf(false) }       // the clip came from a file, not the passage
    var words by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var asked by rememberSaveable { mutableStateOf(false) }        // Make was tapped: back once the voice is made
    val passage = Voices.passage(lang)
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { picked = false; actions.record() }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { picked = true; words = ""; actions.pickClip(uri) }
    }
    // A clip read from the passage says the passage.
    LaunchedEffect(state.clip != null, picked, passage) { if (state.clip != null && !picked) words = passage }
    LaunchedEffect(asked, state.cloning, state.clip == null) { if (asked && !state.cloning && state.clip == null) onBack() }

    Column(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopBar("New voice") { actions.dropClip(); onBack() }
        LazyRow(
            Modifier.widthIn(max = Space.column).fillMaxWidth().padding(bottom = Space.s),
            contentPadding = PaddingValues(horizontal = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            items(Voices.cloneLanguages) { (code, title) -> Chip(Voices.shortLanguage(title), lang == code) { lang = code } }
        }
        Column(
            Modifier.widthIn(max = Space.column).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l).padding(bottom = Space.xxl),
        ) {
            val clip = state.clip
            val rec = state.recording
            if (clip == null) {
                // The passage, as the reader sets text, to read aloud.
                Text(passage, style = Type.reading, color = if (rec != null) t.fg else quiet(),
                    modifier = Modifier.fillMaxWidth().clip(Corner.l).background(t.surf).padding(Space.l))
                Spacer(Modifier.height(Space.l))
                if (rec != null) {
                    Track(rec.second, Modifier.fillMaxWidth(), height = 5.dp)
                    Spacer(Modifier.height(Space.s))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(clock(rec.first), style = Type.headline, color = t.accent2, modifier = Modifier.weight(1f))
                        RoundButton(Glyphs.stop, "Stop", Space.xxl, primary = true, onClick = actions::stopRecording)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        Tile(Glyphs.mic, "Record", Modifier.weight(1f)) { mic.launch(Manifest.permission.RECORD_AUDIO) }
                        Tile(Glyphs.file, "Pick a clip", Modifier.weight(1f)) { pick.launch(arrayOf("audio/*")) }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    RoundButton(if (clip.playing) Glyphs.stop else Glyphs.play, if (clip.playing) "Stop" else "Hear the clip",
                        Space.xl + Space.s, onClick = actions::playClip)
                    Text(clock(clip.seconds), style = Type.headline, color = t.accent2, modifier = Modifier.weight(1f))
                    GlyphButton(Glyphs.close, "Another clip", onClick = actions::dropClip)
                }
                Spacer(Modifier.height(Space.l))
                Field(words, { words = it }, "What the clip says", minLines = 3)
                Spacer(Modifier.height(Space.m))
                Field(name, { name = it }, "Name", minLines = 1)
                Spacer(Modifier.height(Space.l))
                if (state.cloning) ProgressFill(0, 1, "Making ${name.ifBlank { "the voice" }}…")
                else MainButton("Make voice", enabled = name.isNotBlank()) {
                    asked = true
                    actions.makeVoice(words, name, lang)
                }
            }
            if (state.error.isNotEmpty()) {
                Spacer(Modifier.height(Space.m))
                ErrorLine(state.error, actions::dismissError)
            }
        }
    }
}

/**
 * A text to fill in, the same everywhere: [label] inside it until something's typed, an optional
 * [glyph] before it, and the reader's face for [reading] text (something to be read aloud).
 */
@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, minLines: Int = 1, glyph: ImageVector? = null,
          reading: Boolean = false, keyboard: KeyboardOptions = KeyboardOptions(imeAction = if (minLines == 1) ImeAction.Done else ImeAction.Default),
          actions: KeyboardActions = KeyboardActions.Default) {
    val t = LocalTokens.current
    val style = if (reading) Type.reading else Type.body
    OutlinedTextField(
        value = value, onValueChange = onChange, minLines = minLines, singleLine = minLines == 1,
        modifier = Modifier.fillMaxWidth(), shape = Corner.l,
        placeholder = { Text(label, style = style, color = quiet()) }, textStyle = style.copy(color = t.fg),
        leadingIcon = glyph?.let { { Icon(it, null, Modifier.size(Space.l - 3.dp), tint = t.dim) } },
        keyboardOptions = keyboard, keyboardActions = actions,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = t.accent, unfocusedBorderColor = t.line),
    )
}

/** "0:06". */
private fun clock(seconds: Double) = "%d:%02d".format(seconds.toInt() / 60, seconds.toInt() % 60)
