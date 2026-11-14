package kokomux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Settings (tui.sh's tui_settings): the same groups and one-line descriptions
 * as Termux, each change kept at once with a "✓ Saved". How Kokoro runs and
 * how fast it is on this phone come last, out of everyday use.
 */
@Composable
fun SettingsScreen(state: UiState, actions: Actions, onBack: () -> Unit, onVoices: () -> Unit) {
    val t = LocalTokens.current
    var saved by remember { mutableIntStateOf(0) }
    LaunchedEffect(saved) { if (saved > 0) { delay(1597); saved = 0 } }
    val prefs = state.prefs
    fun save(p: Prefs) { actions.prefs(p); saved++ }

    Column(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopBar("Settings", onBack) { if (saved > 0) Text("✓ Saved", style = Type.small, color = t.green) }
        Column(
            Modifier.widthIn(max = Space.column).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l).padding(bottom = Space.xxl),
        ) {
            SectionTitle("Voice and speech")
            ListRow(Glyphs.hear, "Voice", "${Voices.name(prefs.voice)} · ${Voices.describe(prefs.voice)}", accent = true,
                trailing = { Icon(Glyphs.forward, null, Modifier.size(Space.l - 3.dp), tint = t.dim) }, onClick = onVoices)
            SpeedSetting(prefs.speed) { save(prefs.copy(speed = it)) }

            SectionTitle("Reading")
            Setting("Colours", "Auto follows the phone's own light or dark mode; Dark or Light keeps that palette.") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = Space.s)) {
                    ThemeMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = prefs.theme == mode, onClick = { save(prefs.copy(theme = mode)) },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        ) { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }, style = Type.label) }
                    }
                }
            }
            Toggle("Focus effect", "Text fades the further it is from the part being read, like a camera's depth of " +
                "field, and the words just ahead of the voice are in sharpest focus.", prefs.focus) { save(prefs.copy(focus = it)) }
            Toggle("Light up words", "Highlight each word as it's spoken, using the timings Kokoro reports as it speaks.",
                prefs.words) { save(prefs.copy(words = it)) }
            Setting("Highlight timing", "Raise it if the highlight runs ahead of the voice, lower it if it lags.") {
                Stepper("${if (prefs.offsetMs > 0) "+" else ""}${prefs.offsetMs} ms",
                    down = { save(prefs.copy(offsetMs = (prefs.offsetMs - 20).coerceAtLeast(-987))) },
                    up = { save(prefs.copy(offsetMs = (prefs.offsetMs + 20).coerceAtMost(987))) })
            }

            SectionTitle("Kokoro on this phone")
            Tuning(prefs, enabled = state.playing?.making != true) { save(it) }
            Setting("How fast it makes speech", state.making?.let { m ->
                "${m.parts} parts this listen: ${"%.0f".format(m.speech)} s of speech made in ${"%.0f".format(m.took)} s, " +
                    "${"%.1f".format(m.realTime)}× real time (real-time factor ${"%.3f".format(1 / m.realTime)}). " +
                    "Kokoro alone, ${m.tuning}: ${"%.1f".format(m.kokoroRealTime)}×."
            } ?: "Listen to something, and the time each part took shows here.")
            if (state.loaded.isNotEmpty()) Dim(state.loaded, Modifier.padding(bottom = Space.s))
            Pronunciation(state, actions)

            SectionTitle("Storage")
            KeptAudio(state, actions)

            SectionTitle("About")
            Dim("kokomux for Android ${state.version} · Kokoro 82M v1.0 at full precision · misaki 0.7.4 with " +
                "spaCy · espeak-ng 1.52.0 for the words misaki doesn't know.")
        }
    }
}

/** A screen's top: back, and its name; something on the right if there's news. */
@Composable
fun TopBar(title: String, onBack: () -> Unit, aside: @Composable () -> Unit = {}) {
    val t = LocalTokens.current
    Row(Modifier.widthIn(max = Space.column + Space.touch).fillMaxWidth().height(Space.touch).padding(end = Space.l),
        verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(Space.touch).clip(CircleShape).clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "Back" },
            contentAlignment = Alignment.Center,
        ) { Icon(Glyphs.back, null, Modifier.size(Space.l), tint = t.dim) }
        Text(title, style = Type.title, color = t.fg, modifier = Modifier.weight(1f))
        aside()
    }
}

/** A setting: what it is and what it does, then its control (beside it, or below). */
@Composable
private fun Setting(title: String, description: String, inline: (@Composable () -> Unit)? = null,
                    below: (@Composable () -> Unit)? = null) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().heightIn(min = Space.touch).padding(vertical = Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = Space.m)) {
                Text(title, style = Type.label, color = t.fg)
                Dim(description)
            }
            inline?.invoke()
        }
        below?.invoke()
    }
}

@Composable
private fun Setting(title: String, description: String, below: @Composable () -> Unit) =
    Setting(title, description, inline = null, below = below)

/** An on/off setting; the whole row toggles it. */
@Composable
private fun Toggle(title: String, description: String, on: Boolean, set: (Boolean) -> Unit) {
    val t = LocalTokens.current
    Box(Modifier.fillMaxWidth().clip(Corner.m).clickable(role = Role.Switch) { set(!on) }) {
        Setting(title, description, inline = {
            Switch(checked = on, onCheckedChange = set, colors = SwitchDefaults.colors(
                checkedThumbColor = t.bg, checkedTrackColor = t.accent, uncheckedThumbColor = t.dim,
                uncheckedTrackColor = t.surf, uncheckedBorderColor = t.line))
        })
    }
}

/** Speed: a slider from 0.5× to 2×, a tenth at a time, kept when you let go. */
@Composable
private fun SpeedSetting(speed: Float, set: (Float) -> Unit) {
    val t = LocalTokens.current
    var moving by remember(speed) { mutableFloatStateOf(speed) }
    Setting("Speed", "How fast it reads. A new speed is heard at once.", inline = {
        Text(speedLabel(moving), style = Type.label, color = t.accent2)
    }, below = {
        Slider(
            value = moving, onValueChange = { moving = Math.round(it * 10) / 10f }, valueRange = 0.5f..2f, steps = 14,
            onValueChangeFinished = { set(moving) },
            colors = SliderDefaults.colors(thumbColor = t.accent, activeTrackColor = t.accent, inactiveTrackColor = t.line,
                activeTickColor = t.accent, inactiveTickColor = t.ghost),
        )
    })
}

/** − value +, for a number set in steps. */
@Composable
private fun Stepper(value: String, down: () -> Unit, up: () -> Unit) {
    val t = LocalTokens.current
    Row(Modifier.padding(top = Space.s), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        StepButton(Glyphs.minus, "Earlier", down)
        Text(value, style = Type.label, color = t.accent2, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.widthIn(min = Space.xxxl))
        StepButton(Glyphs.plus, "Later", up)
    }
}

@Composable
private fun StepButton(glyph: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val t = LocalTokens.current
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(t.surf).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(glyph, null, Modifier.size(Space.l - 3.dp), tint = t.accent2) }
}

/** How onnxruntime runs Kokoro: kokoro --benchmark's choices, to pick the one it kept in Termux. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tuning(prefs: Prefs, enabled: Boolean, set: (Prefs) -> Unit) {
    val t = LocalTokens.current
    Setting("How Kokoro runs", "The ways kokoro --benchmark tries: pick the one it kept in Termux to compare " +
        "like with like. Kokoro loads again, in a second or two.") {
        FlowRow(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            val colors = FilterChipDefaults.filterChipColors(selectedContainerColor = t.psel, selectedLabelColor = t.fg,
                labelColor = t.dim)
            for (n in listOf(0, 2, 4, 6, 8)) {
                FilterChip(selected = prefs.threads == n, enabled = enabled, onClick = { set(prefs.copy(threads = n)) },
                    label = { Text(if (n == 0) "its own choice" else "$n threads", style = Type.small) }, colors = colors)
            }
            FilterChip(selected = prefs.block > 0, enabled = enabled,
                onClick = { set(prefs.copy(block = if (prefs.block > 0) 0 else 4)) },
                label = { Text("block 4", style = Type.small) }, colors = colors)
        }
    }
}

/** The pronunciation check: misaki here against engine.py's sounds. */
@Composable
private fun Pronunciation(state: UiState, actions: Actions) {
    val t = LocalTokens.current
    Setting("Pronunciation", "Checks misaki on this phone says 70 parts exactly as engine.py does in Termux.", inline = {
        TextButton(onClick = actions::check, enabled = !state.checking && state.setup == Setup.Ready) {
            Text(if (state.checking) "Checking…" else "Check", style = Type.label)
        }
    }, below = {
        val c = state.checked ?: return@Setting
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text("${c.cases - c.differ.size} of ${c.cases} parts sound the same (${"%.1f".format(c.seconds)} s).",
                style = Type.small, color = if (c.differ.isEmpty()) t.green else t.red)
            for ((where, want, got) in c.differ) {
                Text(where, style = Type.small, color = t.fg)
                Dim("engine.py  $want\nhere       $got")
            }
        }
    })
}

/** The kept audio: how much there is, and letting it go. */
@Composable
private fun KeptAudio(state: UiState, actions: Actions) {
    var asking by remember { mutableStateOf(false) }
    Setting("Kept audio", "Parts already made are kept, per book and voice, so listening again costs nothing. " +
        "It never grows past 50 GB, and a book heard to the end lets go of its own.", inline = {
        TextButton(onClick = { asking = true }, enabled = state.kept > 0 && state.playing == null) {
            Text("Clear", style = Type.label)
        }
    }, below = { Dim(sizeLabel(state.kept), Modifier.padding(top = Space.xs)) })
    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        confirmButton = { TextButton(onClick = { asking = false; actions.clearKept() }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { asking = false }) { Text("Keep it") } },
        title = { Text("Clear the kept audio?", style = Type.title) },
        text = { Text("Your books and where you are in them stay. Their parts are made again as you listen.", style = Type.body) },
    )
}

/** "1.2 GB", "340 MB", "nothing yet". */
fun sizeLabel(bytes: Long): String = when {
    bytes <= 0 -> "Nothing kept yet"
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    else -> "%.0f MB".format(bytes / 1e6)
}
