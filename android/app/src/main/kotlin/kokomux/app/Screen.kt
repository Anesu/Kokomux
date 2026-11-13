package kokomux.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kokomux.app.Spike.Companion.fmt

/** The spike's screen: text, voice, Play, then the time each part took. */
@Composable
fun SpikeScreen(spike: Spike) {
    val busy = spike.playing || spike.stage is Stage.Downloading || spike.checking
    val view = LocalView.current
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // A reading column 610 dp wide at most (a Fibonacci number), centred on a wide screen.
        Box(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 610.dp)
                .padding(horizontal = Space.l, vertical = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Text("kokomux", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold)
            Models(spike)
            OutlinedTextField(
                value = spike.text, onValueChange = { spike.text = it },
                modifier = Modifier.fillMaxWidth(), minLines = 5, label = { Text("Text") },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                VoicePicker(spike, Modifier.weight(1f))
                Button(onClick = spike::playOrStop, enabled = spike.stage == Stage.Ready) {
                    Text(if (spike.playing) "■  Stop" else "▶  Play")
                }
            }
            Tuning(spike)
            if (spike.error.isNotEmpty()) Text(spike.error, color = MaterialTheme.colorScheme.error)
            if (spike.made.isNotEmpty()) Timings(spike.made, spike.madeWith)
            Pronunciation(spike)
            if (spike.loaded.isNotEmpty()) Dim(spike.loaded)
        }
        }
    }
}

@Composable
private fun Models(spike: Spike) {
    when (val s = spike.stage) {
        Stage.Missing -> Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Dim("Kokoro's model and voices aren't on the phone yet.")
            Button(onClick = spike::download) { Text("Download Kokoro (354 MB)") }
        }
        is Stage.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            LinearProgressIndicator(progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f },
                modifier = Modifier.fillMaxWidth())
            Dim("${s.label}: ${s.done / 1_000_000} of ${s.total / 1_000_000} MB")
        }
        is Stage.Loading -> Dim("Loading ${s.what}…")
        is Stage.Failed -> Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(s.message, color = MaterialTheme.colorScheme.error)
            Button(onClick = spike::download) { Text("Try again") }
        }
        Stage.Ready -> {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePicker(spike: Spike, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = spike.voice, onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text("Voice") },
            supportingText = { Text(describe(spike.voice)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (v in spike.voices) {
                DropdownMenuItem(
                    text = { Text("$v   ${describe(v)}") },
                    onClick = { spike.voice = v; open = false },
                )
            }
        }
    }
}

/** How onnxruntime runs Kokoro: the choices kokoro --benchmark tries, to match what it kept in Termux. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tuning(spike: Spike) {
    val ready = spike.stage == Stage.Ready && !spike.playing
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.Center) {
        for (n in listOf(0, 2, 4, 6, 8)) {
            FilterChip(
                selected = spike.threads == n, enabled = ready,
                onClick = { spike.tune(n, spike.block) },
                label = { Text(if (n == 0) "its own choice" else "$n threads") },
            )
        }
        FilterChip(
            selected = spike.block > 0, enabled = ready,
            onClick = { spike.tune(spike.threads, if (spike.block > 0) 0 else 4) },
            label = { Text("block 4") },
        )
    }
}

/** "af_heart" -> "American, female". */
private fun describe(voice: String): String {
    val accent = if (voice.startsWith("b")) "British" else "American"
    val who = if (voice.getOrNull(1) == 'm') "male" else "female"
    return "$accent, $who"
}

/** Each part's times, then the whole: how many times faster than real time it was made. */
@Composable
private fun Timings(made: List<Made>, with: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row { listOf("Part", "misaki", "Kokoro", "Speech", "Speed").forEach { Cell(it, dim = true) } }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        for (m in made) {
            Row {
                Cell("${m.index}")
                Cell("${fmt(m.sounds)} s")
                Cell("${fmt(m.kokoro)} s")
                Cell("${fmt(m.audio)} s")
                Cell("%.1f×".format(m.audio / m.took))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        val audio = made.sumOf { it.audio }
        val took = made.sumOf { it.took }
        val kokoro = made.sumOf { it.kokoro }
        Text(
            "${fmt(audio)} s of speech made in ${fmt(took)} s: %.1f× real time (real-time factor %.3f). ".format(audio / took, took / audio) +
                "Kokoro alone, $with: %.1f× (%.3f).".format(audio / kokoro, kokoro / audio),
            style = MaterialTheme.typography.bodyMedium,
        )
        Dim("First part ready ${fmt(made.first().took)} s after Play.")
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, dim: Boolean = false) {
    Text(
        text, modifier = Modifier.weight(1f), textAlign = TextAlign.End, fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun Pronunciation(spike: Spike) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        TextButton(onClick = spike::check, enabled = spike.stage == Stage.Ready && !spike.checking) {
            Text(if (spike.checking) "Checking pronunciation…" else "Check pronunciation")
        }
        val c = spike.checked ?: return
        val same = c.cases - c.differ.size
        Text(
            "$same of ${c.cases} parts sound the same as engine.py (${fmt(c.seconds)} s).",
            color = if (c.differ.isEmpty()) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
        )
        for ((where, want, got) in c.differ) {
            Text(where, style = MaterialTheme.typography.bodySmall)
            Text("engine.py  $want\nhere       $got", style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.padding(Space.s / 2))
        }
    }
}

@Composable
private fun Dim(text: String) =
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
