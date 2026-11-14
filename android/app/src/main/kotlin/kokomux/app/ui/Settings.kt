package kokomux.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Settings (tui.sh's tui_settings): the voice and speed; how reading looks,
 * shown in a sample of the reader that follows every change; how Kokoro runs
 * on this phone and how fast the voice is making speech; the engines' files
 * and the kept audio; the versions. A change is kept as it's made.
 */
@Composable
fun SettingsScreen(state: UiState, actions: Actions, onBack: () -> Unit, onVoices: () -> Unit) {
    val t = LocalTokens.current
    val prefs = state.prefs
    fun save(p: Prefs) = actions.prefs(p)

    Column(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopBar("Settings", onBack)
        Column(
            Modifier.widthIn(max = Space.column).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l).padding(bottom = Space.xxl),
        ) {
            SectionTitle("Voice")
            ListRow(Glyphs.hear, Voices.name(prefs.voice), Voices.describe(prefs.voice), accent = true,
                trailing = { Icon(Glyphs.forward, null, Modifier.size(Space.l - 3.dp), tint = t.dim) }, onClick = onVoices)
            SpeedSetting(prefs.speed) { save(prefs.copy(speed = it)) }

            SectionTitle("Reading")
            ReadingSample(prefs)
            Spacer(Modifier.height(Space.m))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = prefs.theme == mode, onClick = { save(prefs.copy(theme = mode)) },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                    ) { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }, style = Type.label) }
                }
            }
            Spacer(Modifier.height(Space.s))
            Toggle("Focus", prefs.focus) { save(prefs.copy(focus = it)) }
            Toggle("Light up words", prefs.words) { save(prefs.copy(words = it)) }
            if (prefs.words) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Step(Glyphs.minus, "Earlier") { save(prefs.copy(offsetMs = (prefs.offsetMs - 20).coerceAtLeast(-987))) }
                Text("${if (prefs.offsetMs > 0) "+" else ""}${prefs.offsetMs} ms", style = Type.label, color = t.accent2,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Step(Glyphs.plus, "Later") { save(prefs.copy(offsetMs = (prefs.offsetMs + 20).coerceAtMost(987))) }
            }

            SectionTitle("Kokoro")
            Tuning(prefs, enabled = state.playing?.making != true) { save(it) }
            state.making?.let { HowFast(it) }
            if (state.loaded.isNotEmpty()) Dim(state.loaded, Modifier.padding(vertical = Space.s))
            Pronunciation(state, actions)

            SectionTitle("Storage")
            KeptAudio(state, actions)
            for (e in state.engines) EngineStorage(e, actions, onVoices)

            SectionTitle("About")
            Dim("kokomux ${state.version}\nKokoro 82M v1.0, fp32 · misaki 0.7.4 · spaCy 3.8.7 · espeak-ng 1.52.0\n" +
                "Ming-3.0-tts 0.5B on the NPU (LiteRT 2.3.2, QNN 2.47) · Qwen3-TTS 0.6B Base, 4-bit")
        }
    }
}

/** A screen's top: back, and its name. */
@Composable
fun TopBar(title: String, onBack: () -> Unit) {
    val t = LocalTokens.current
    Row(Modifier.widthIn(max = Space.column + Space.touch).fillMaxWidth().height(Space.touch).padding(horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        GlyphButton(Glyphs.back, "Back", onClick = onBack)
        Text(title, style = Type.title, color = t.fg, modifier = Modifier.weight(1f))
    }
}

/** A setting's name, and its control on the right; the whole row is the target when it has one. */
@Composable
private fun Line(title: String, onClick: (() -> Unit)? = null, role: Role = Role.Button, control: @Composable () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.touch).clip(Corner.m)
            .then(if (onClick != null) Modifier.clickable(role = role, onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Type.label, color = t.fg, modifier = Modifier.weight(1f))
        control()
    }
}

/** An on/off setting; the whole row toggles it. */
@Composable
private fun Toggle(title: String, on: Boolean, set: (Boolean) -> Unit) {
    val t = LocalTokens.current
    Line(title, onClick = { set(!on) }, role = Role.Switch) {
        Switch(checked = on, onCheckedChange = set, colors = SwitchDefaults.colors(
            checkedThumbColor = t.bg, checkedTrackColor = t.accent, uncheckedThumbColor = t.dim,
            uncheckedTrackColor = t.surf, uncheckedBorderColor = t.line))
    }
}

/** Speed: its value, and a slider from 0.5× to 2×, a tenth at a time, kept when you let go. */
@Composable
private fun SpeedSetting(speed: Float, set: (Float) -> Unit) {
    val t = LocalTokens.current
    var moving by remember(speed) { mutableFloatStateOf(speed) }
    Line("Speed") { Text(speedLabel(moving), style = Type.label, color = t.accent2) }
    Slider(
        value = moving, onValueChange = { moving = Math.round(it * 10) / 10f }, valueRange = 0.5f..2f, steps = 14,
        onValueChangeFinished = { set(moving) },
        modifier = Modifier.semantics { contentDescription = "Speed" },
        colors = SliderDefaults.colors(thumbColor = t.accent, activeTrackColor = t.accent, inactiveTrackColor = t.line,
            activeTickColor = t.accent, inactiveTickColor = t.ghost),
    )
}

/** A step with its glyph and its word ("− Earlier", "Later +"), thumb-sized. */
@Composable
private fun Step(glyph: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.heightIn(min = 48.dp).clip(CircleShape).background(t.surf).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        if (glyph == Glyphs.minus) Icon(glyph, null, Modifier.size(Space.m + 3.dp), tint = t.accent2)
        Text(label, style = Type.small, color = t.fg)
        if (glyph == Glyphs.plus) Icon(glyph, null, Modifier.size(Space.m + 3.dp), tint = t.accent2)
    }
}

/**
 * A few lines of the reader, drawn as the reader draws them with these
 * settings: what's been heard, the part being read with its words lighting
 * up one after another, and what's to come fading with the focus effect.
 */
@Composable
private fun ReadingSample(prefs: Prefs) {
    val t = LocalTokens.current
    val style = Type.reading.copy(fontSize = Type.body.fontSize, lineHeight = Type.body.lineHeight)
    val now = "For three days she walked alone."
    val words = remember { Regex("\\S+").findAll(now).map { it.range }.toList() }
    // A word every 754 ms (2 × 377), round and round, with a pause at the end of the line.
    val beat by rememberInfiniteTransition(label = "sample").animateFloat(2f, 2f + words.size + 2,
        infiniteRepeatable(tween((words.size + 2) * Motion.SLOW * 2, easing = LinearEasing)), label = "word")
    val lit = beat.toInt() % (words.size + 2)
    val near = Phi.inv(1f)
    Column(
        Modifier.fillMaxWidth().clip(Corner.l).border(1.dp, t.ghost, Corner.l).background(t.bg)
            .padding(horizontal = Space.m, vertical = Space.s)
            .semantics { contentDescription = "How the reader looks" },
    ) {
        Text("The road left the village.", style = style, color = t.tone(if (prefs.focus) Focus.tone(0.5f, 1f) else 0.5f))
        Row {
            Box(Modifier.padding(top = Space.xs, end = Space.s).width(3.dp).height(Space.m + 3.dp).clip(CircleShape).background(t.accent))
            Text(buildAnnotatedString {
                if (!prefs.words) { withStyle(SpanStyle(color = t.fg)) { append(now) }; return@buildAnnotatedString }
                var pos = 0
                words.forEachIndexed { k, r ->
                    if (r.first > pos) append(now.substring(pos, r.first))
                    val colour = when {
                        k <= lit -> t.fg
                        !prefs.focus -> t.dim
                        else -> t.tone(near + (1f - near) / 2f * Focus.decay((k - lit) * 2f))
                    }
                    withStyle(if (k == lit) SpanStyle(color = t.fg, background = t.hl) else SpanStyle(color = colour)) {
                        append(now.substring(r.first, r.last + 1))
                    }
                    pos = r.last + 1
                }
            }, style = style, color = t.fg)
        }
        Text("Then the mist lifted.", style = style, color = t.tone(near))
        Text("She saw the sea at last.", style = style, color = t.tone(if (prefs.focus) Focus.tone(near, 3f) else near))
    }
}

/** How onnxruntime runs Kokoro: kokoro --benchmark's choices (threads, and blocks of 4). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tuning(prefs: Prefs, enabled: Boolean, set: (Prefs) -> Unit) {
    val t = LocalTokens.current
    Text("Threads", style = Type.label, color = t.fg, modifier = Modifier.padding(top = Space.s, bottom = Space.xs))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        val colors = FilterChipDefaults.filterChipColors(selectedContainerColor = t.psel, selectedLabelColor = t.fg,
            labelColor = t.dim)
        for (n in listOf(0, 2, 4, 6, 8)) {
            FilterChip(selected = prefs.threads == n, enabled = enabled, onClick = { set(prefs.copy(threads = n)) },
                label = { Text(if (n == 0) "Auto" else "$n", style = Type.small) }, colors = colors)
        }
        FilterChip(selected = prefs.block > 0, enabled = enabled,
            onClick = { set(prefs.copy(block = if (prefs.block > 0) 0 else 4)) },
            label = { Text("block 4", style = Type.small) }, colors = colors)
    }
}

/** How fast speech was made this listen: times real time, all in and the voice alone. */
@Composable
private fun HowFast(m: Making) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Text("${"%.1f".format(m.realTime)}×", style = Type.headline, color = if (m.realTime >= 1) t.accent2 else t.red)
        Column(Modifier.weight(1f)) {
            Text("real time", style = Type.label, color = t.fg)
            Dim(listOf("${m.engine} ${"%.1f".format(m.voiceRealTime)}×", "${m.parts} parts", m.tuning)
                .filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 2)
        }
    }
}

/** An engine's files: how much they take up, and letting them go (Kokoro's stay); one not here yet leads to its download. */
@Composable
private fun EngineStorage(e: EngineFiles, actions: Actions, onVoices: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    Line(e.title, onClick = if (e.ready || e.id == "kokoro") null else onVoices) {
        Dim(if (e.ready) sizeLabel(e.total) else if (e.downloading) "${sizeLabel(e.done, e.total)} of ${sizeLabel(e.total)}"
            else "Not here", Modifier.padding(end = Space.s))
        if (e.id != "kokoro" && e.ready) TextButton(onClick = { asking = true }) { Text("Remove", style = Type.label) }
    }
    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        confirmButton = { TextButton(onClick = { asking = false; actions.removeEngine(e.id) }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        title = { Text("Remove ${e.title}?", style = Type.title) },
        text = { Text("${sizeLabel(e.total)} back. Its voices come back with it.", style = Type.body) },
    )
}

/** The pronunciation check: misaki here against engine.py's sounds; what differs, if anything. */
@Composable
private fun Pronunciation(state: UiState, actions: Actions) {
    val t = LocalTokens.current
    val c = state.checked
    Line("Pronunciation") {
        if (c != null && !state.checking) Text("${c.cases - c.differ.size} of ${c.cases}", style = Type.small,
            color = if (c.differ.isEmpty()) t.green else t.red, modifier = Modifier.padding(end = Space.s))
        TextButton(onClick = actions::check, enabled = !state.checking && state.setup == Setup.Ready) {
            Text(if (state.checking) "Checking…" else "Check", style = Type.label)
        }
    }
    if (c != null) for ((where, want, got) in c.differ) {
        Text(where, style = Type.small, color = t.fg)
        Dim("engine.py  $want\nhere       $got")
    }
}

/** The kept audio: how much there is, and letting it go. */
@Composable
private fun KeptAudio(state: UiState, actions: Actions) {
    var asking by remember { mutableStateOf(false) }
    Line("Kept audio") {
        Dim(sizeLabel(state.kept), Modifier.padding(end = Space.s))
        TextButton(onClick = { asking = true }, enabled = state.kept > 0 && state.playing == null) {
            Text("Clear", style = Type.label)
        }
    }
    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        confirmButton = { TextButton(onClick = { asking = false; actions.clearKept() }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        title = { Text("Clear ${sizeLabel(state.kept)}?", style = Type.title) },
        text = { Text("Bookmarks stay.", style = Type.body) },
    )
}

/** "1.2 GB", "340 MB", "None". */
fun sizeLabel(bytes: Long): String = when {
    bytes <= 0 -> "None"
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    else -> "%.0f MB".format(bytes / 1e6)
}
