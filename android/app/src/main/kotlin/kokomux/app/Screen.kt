package kokomux.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kokomux.app.Spike.Companion.fmt
import kokomux.engine.Bookmarks
import kokomux.engine.Maker

/**
 * The one screen for now: what's playing and its controls, the books in
 * progress, opening a file, a web page or a typed text, and the time each part
 * took to make (to compare with kokoro --benchmark), with the checks.
 */
@Composable
fun SpikeScreen(spike: Spike) {
    val busy = spike.stage is Stage.Downloading || spike.checking
    val view = LocalView.current
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }
    // A book heard to the end, or a new one opened: the shelf changes.
    LaunchedEffect(Listening.now, Listening.at) { spike.refreshShelf() }
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
                NowPlaying(spike)
                if (spike.error.isNotEmpty()) Text(spike.error, color = MaterialTheme.colorScheme.error)
                if (Listening.error.isNotEmpty()) Text(Listening.error, color = MaterialTheme.colorScheme.error)
                Shelf(spike)
                Open(spike)
                Typed(spike)
                Tuning(spike)
                if (Listening.made.any { !it.kept }) Timings(Listening.made.filter { !it.kept }, Listening.madeWith)
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

/** The book playing: its title, where it is, the part being read, and the controls. */
@Composable
private fun NowPlaying(spike: Spike) {
    if (Listening.opening) return Dim("Opening…")
    val now = Listening.now ?: return
    val parts = now.book.parts.size
    val at = Listening.at.coerceIn(0, parts)
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(now.book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val chapter = now.book.chapterOf(at)?.title?.takeIf { now.book.chapters.size > 1 }
        val made = Listening.made.lastOrNull()?.index ?: 0
        Dim(listOfNotNull(chapter, if (at > 0) "Part $at of $parts" else null,
            if (Listening.making && made < parts) "made to $made" else null).joinToString(" · "))
        LinearProgressIndicator(progress = { if (parts > 1) (at - 1).coerceAtLeast(0).toFloat() / (parts - 1) else 0f },
            modifier = Modifier.fillMaxWidth())
        if (at > 0) Text(now.book.parts[at - 1].text, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = spike::again) { Text("‹ Again") }
            Button(onClick = spike::pauseOrPlay) { Text(if (spike.playing) "‖ Pause" else "▶ Play") }
            OutlinedButton(onClick = spike::next) { Text("Next ›") }
            TextButton(onClick = spike::stopListening) { Text("Stop") }
        }
    }
}

/** The books in progress, the one heard most recently first. */
@Composable
private fun Shelf(spike: Spike) {
    val playing = Listening.now?.request?.id
    val books = spike.shelf.filter { it.id != playing }
    if (books.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Dim("Books in progress")
        for (b in books) ShelfBook(b, enabled = spike.stage == Stage.Ready) { spike.resume(b) }
    }
}

@Composable
private fun ShelfBook(mark: Bookmarks.Mark, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick), verticalArrangement = Arrangement.spacedBy(Space.s / 2)) {
        Text(mark.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LinearProgressIndicator(progress = { mark.progress }, modifier = Modifier.fillMaxWidth())
        Dim("Part ${mark.part} of ${mark.parts} · ${mark.voice}")
    }
}

/** Open a file (an EPUB, a PDF or text) or a web page. */
@Composable
private fun Open(spike: Spike) {
    val ready = spike.stage == Stage.Ready
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) spike.openFile(uri)
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        // Any file: some file managers call an EPUB just "binary", and the engine tells them apart itself.
        FilledTonalButton(onClick = { pick.launch(arrayOf("*/*")) },
            enabled = ready) { Text("Open a book or file") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            OutlinedTextField(
                value = spike.address, onValueChange = { spike.address = it }, singleLine = true,
                label = { Text("Web page") }, placeholder = { Text("https://…") }, modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = spike::openAddress, enabled = ready && spike.address.isNotBlank()) { Text("Read") }
        }
    }
}

/** Type something to hear, in the chosen voice. */
@Composable
private fun Typed(spike: Spike) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        OutlinedTextField(
            value = spike.text, onValueChange = { spike.text = it },
            modifier = Modifier.fillMaxWidth(), minLines = 5, label = { Text("Text") },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            VoicePicker(spike, Modifier.weight(1f))
            Button(onClick = spike::playText, enabled = spike.stage == Stage.Ready) { Text("▶  Play") }
        }
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
    val ready = spike.stage == Stage.Ready && !Listening.making
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

/** "bf_emma" -> "British English, female" (engine.py's LANGS). */
private fun describe(voice: String): String {
    val language = when (voice.firstOrNull()) {
        'a' -> "American English"; 'b' -> "British English"; 'e' -> "Spanish"; 'f' -> "French"
        'h' -> "Hindi"; 'i' -> "Italian"; 'j' -> "Japanese"; 'p' -> "Brazilian Portuguese"; 'z' -> "Mandarin Chinese"
        else -> "?"
    }
    return "$language, ${if (voice.getOrNull(1) == 'm') "male" else "female"}"
}

/** Each part made this listen, then the whole: how many times faster than real time it was made. */
@Composable
private fun Timings(made: List<Maker.Made>, with: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row { listOf("Part", "misaki", "Kokoro", "Speech", "Speed").forEach { Cell(it, dim = true) } }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        for (m in made.takeLast(TABLE_ROWS)) {
            Row {
                Cell("${m.index}")
                Cell("${fmt(m.soundsSecs)} s")
                Cell("${fmt(m.kokoroSecs)} s")
                Cell("${fmt(m.speechSecs)} s")
                Cell("%.1f×".format(m.speechSecs / (m.soundsSecs + m.kokoroSecs)))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        val audio = made.sumOf { it.speechSecs }
        val took = made.sumOf { it.soundsSecs + it.kokoroSecs }
        val kokoro = made.sumOf { it.kokoroSecs }
        Text(
            "${made.size} parts: ${fmt(audio)} s of speech made in ${fmt(took)} s, " +
                "%.1f× real time (real-time factor %.3f). ".format(audio / took, took / audio) +
                "Kokoro alone, $with: %.1f× (%.3f).".format(audio / kokoro, kokoro / audio),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** The table shows the last 13 parts made (a Fibonacci number); the sums cover them all. */
private const val TABLE_ROWS = 13

@Composable
private fun RowScope.Cell(text: String, dim: Boolean = false) {
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
