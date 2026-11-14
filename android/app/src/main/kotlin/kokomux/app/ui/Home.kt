package kokomux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The home screen (tui.sh's tui_home): the voice new things are read in, and
 * settings; what's playing; four ways to start something new; the bookshelf
 * of books in progress (one taken off it can be brought back for 5 s). On
 * the optical centre when there's room.
 */
@Composable
fun HomeScreen(
    state: UiState, actions: Actions,
    onReader: () -> Unit, onOpenFile: () -> Unit, onPage: () -> Unit, onType: () -> Unit,
    onVoices: () -> Unit, onSettings: () -> Unit,
) {
    val t = LocalTokens.current
    // A book taken off the shelf goes for good when its undo bar does (after 5 s, or on leaving home).
    var removal by remember { mutableStateOf<Removal?>(null) }
    removal?.let { r ->
        LaunchedEffect(r) { delay(5000); removal = null }
        DisposableEffect(r) { onDispose { if (!r.undone) actions.remove(r.book.id) } }
    }
    Box(Modifier.fillMaxSize().background(t.bg)) {
        GoldenColumn(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Logo(Space.xl)
                Text("kokomux", style = Type.title, color = t.accent, modifier = Modifier.weight(1f), maxLines = 1)
                Pill(Glyphs.hear, Voices.name(state.prefs.voice), "Voice ${Voices.name(state.prefs.voice)}", onClick = onVoices)
                GlyphButton(Glyphs.settings, "Settings", onClick = onSettings)
            }
            Spacer(Modifier.height(Space.l))
            if (state.error.isNotEmpty()) {
                ErrorLine(state.error, actions::dismissError)
                Spacer(Modifier.height(Space.m))
            }
            if (state.opening) NowCard(null, actions, onReader)
            else state.playing?.let { NowCard(it, actions, onReader) }
            if (state.opening || state.playing != null) Spacer(Modifier.height(Space.l))

            val ready = state.setup == Setup.Ready
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Tile(Glyphs.file, "File", Modifier.weight(1f), enabled = ready, onClick = onOpenFile)
                Tile(Glyphs.globe, "Web page", Modifier.weight(1f), enabled = ready, onClick = onPage)
                Tile(Glyphs.paste, "Clipboard", Modifier.weight(1f), enabled = ready) { actions.readClipboard(); onReader() }
                Tile(Glyphs.type, "Type", Modifier.weight(1f), enabled = ready, onClick = onType)
            }

            val shelf = state.shelf.filter { it.id != state.playing?.id && it.id != removal?.book?.id }
            if (shelf.isNotEmpty()) {
                Spacer(Modifier.height(Space.s))
                SectionTitle("Bookshelf")
                for (b in shelf) ShelfRow(b, onClick = { actions.resume(b.id); onReader() }, onRemove = { removal = Removal(b) })
            }
        }
        removal?.let { r ->
            Undo(r.book.title, Modifier.align(Alignment.BottomCenter).safeDrawingPadding()) { r.undone = true; removal = null }
        }
    }
}

/** A book being taken off the shelf, until its undo bar goes. */
private class Removal(val book: ShelfBook) { var undone = false }

/** "Removed: the title", and Undo, floating at the bottom of the screen. */
@Composable
private fun Undo(title: String, modifier: Modifier, onUndo: () -> Unit) {
    val t = LocalTokens.current
    Row(
        modifier.widthIn(max = Space.column).fillMaxWidth().padding(Space.l).clip(Corner.l).background(t.fg)
            .padding(start = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Removed: $title", style = Type.small, color = t.bg, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        TextButton(onClick = onUndo, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Undo", style = Type.label, color = Tokens.mix(t.accent, t.bg, 0.382f))
        }
    }
}

/** What's playing (or opening): its cover, its chapter or part, play or pause; tap it for the reader. */
@Composable
private fun NowCard(p: Playing?, actions: Actions, onReader: () -> Unit) {
    val t = LocalTokens.current
    Column(
        Modifier.fillMaxWidth().clip(Corner.l).background(t.surf).clickable(role = Role.Button, onClick = onReader)
            .padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) { OnSurface(t.surf) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            if (p == null) Box(Modifier.size(Space.xl, Space.xl * Phi.PHI), contentAlignment = Alignment.Center) { Logo(Space.l, live = true) }
            else Cover(p.title)
            Column(Modifier.weight(1f)) {
                Text(p?.title ?: "Opening…", style = Type.label, color = t.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (p != null && p.at > 0) Dim(p.chapter?.title?.takeIf { p.chapters.size > 1 } ?: "${p.at} of ${p.parts.size}",
                    maxLines = 1)
            }
            if (p != null) RoundButton(if (p.playing) Glyphs.pause else Glyphs.play, if (p.playing) "Pause" else "Play",
                Space.xxl, primary = true, busy = p.at == 0, onClick = actions::playPause)
        }
        if (p != null && p.parts.size > 1) Track((p.at - 1f).coerceAtLeast(0f) / (p.parts.size - 1),
            made = (p.madeTo - 1f).coerceAtLeast(0f) / (p.parts.size - 1))
    } }
}

/** A book on the shelf: its cover, title and how far through; tap to carry on, ⋮ to remove it. */
@Composable
private fun ShelfRow(b: ShelfBook, onClick: () -> Unit, onRemove: () -> Unit) {
    val t = LocalTokens.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(Corner.m).clickable(role = Role.Button, onClick = onClick).padding(vertical = Space.s, horizontal = Space.s),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Cover(b.title)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(b.title, style = Type.label, color = t.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Track(b.progress, Modifier.weight(1f))
                Dim("${(b.progress * 100).toInt()}%", maxLines = 1)
            }
        }
        Box {
            GlyphButton(Glyphs.more, "More for ${b.title}") { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Remove", style = Type.body) }, onClick = { menu = false; onRemove() },
                    leadingIcon = { Icon(Glyphs.close, null, Modifier.size(Space.l)) })
            }
        }
    }
}
