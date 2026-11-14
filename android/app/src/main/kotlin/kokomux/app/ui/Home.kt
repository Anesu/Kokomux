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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The home screen (tui.sh's tui_home): what's playing, the bookshelf of books
 * in progress, ways to start something new, and the voice. On the optical
 * centre when there's room.
 */
@Composable
fun HomeScreen(
    state: UiState, actions: Actions,
    onReader: () -> Unit, onOpenFile: () -> Unit, onPage: () -> Unit, onType: () -> Unit,
    onVoices: () -> Unit, onSettings: () -> Unit,
) {
    val t = LocalTokens.current
    GoldenColumn(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Logo(Space.xl)
            Column(Modifier.weight(1f)) {
                Text("kokomux", style = Type.headline, color = t.accent)
                Dim("Kokoro text-to-speech")
            }
            QuietButton(Glyphs.settings, "Settings", onClick = onSettings)
        }
        Spacer(Modifier.height(Space.l))
        if (state.error.isNotEmpty()) {
            ErrorLine(state.error, actions::dismissError)
            Spacer(Modifier.height(Space.m))
        }
        if (state.opening) NowCard(null, actions, onReader)
        else state.playing?.let { NowCard(it, actions, onReader) }

        val shelf = state.shelf.filter { it.id != state.playing?.id }
        if (shelf.isNotEmpty()) {
            SectionTitle("Bookshelf", if (shelf.size == 1) "1 book" else "${shelf.size} books")
            for (b in shelf) ShelfRow(b, onClick = { actions.resume(b.id); onReader() }, onRemove = { actions.remove(b.id) })
        }

        SectionTitle("Start something new")
        val ready = state.setup == Setup.Ready
        ListRow(Glyphs.file, "Open a file", "An EPUB, a PDF or a text file", enabled = ready, onClick = onOpenFile)
        ListRow(Glyphs.globe, "Read a web page", "Its article, or a web novel's chapter", enabled = ready, onClick = onPage)
        ListRow(Glyphs.paste, "Read the clipboard", "Its text, or the page a copied link goes to", enabled = ready) {
            actions.readClipboard(); onReader()
        }
        ListRow(Glyphs.type, "Type something", "And hear it read", enabled = ready, onClick = onType)

        SectionTitle("Voice")
        ListRow(Glyphs.hear, Voices.name(state.prefs.voice),
            "${Voices.describe(state.prefs.voice)} · ${speedLabel(state.prefs.speed)}", accent = true,
            trailing = { Icon(Glyphs.forward, null, Modifier.size(Space.l - 3.dp), tint = t.dim) }, onClick = onVoices)
    }
}

/** What's playing (or opening): its cover, where it is, play or pause; tap it for the reader. */
@Composable
private fun NowCard(p: Playing?, actions: Actions, onReader: () -> Unit) {
    val t = LocalTokens.current
    Column(
        Modifier.fillMaxWidth().clip(Corner.l).background(t.surf).clickable(role = Role.Button, onClick = onReader)
            .padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Cover(p?.title ?: "·")
            Column(Modifier.weight(1f)) {
                Text(if (p == null) "Opening…" else p.title, style = Type.label, color = t.fg, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                if (p != null) {
                    val n = p.parts.size
                    Dim(listOfNotNull(if (p.at > 0) "Part ${p.at} of $n" else "Getting ready",
                        p.chapter?.title?.takeIf { p.chapters.size > 1 }).joinToString(" · "), maxLines = 1)
                }
            }
            if (p != null) RoundButton(if (p.playing) Glyphs.pause else Glyphs.play, if (p.playing) "Pause" else "Play",
                Space.xxl, primary = true, onClick = actions::playPause)
        }
        if (p != null && p.parts.size > 1) Track((p.at - 1f).coerceAtLeast(0f) / (p.parts.size - 1),
            made = (p.madeTo - 1f).coerceAtLeast(0f) / (p.parts.size - 1))
    }
}

/** A book on the shelf: its cover, title, how far through and in which voice; tap to carry on. */
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
            Track(b.progress)
            Dim("Part ${b.part} of ${b.parts} · ${(b.progress * 100).toInt()}% · ${Voices.name(b.voice)}", maxLines = 1)
        }
        Box {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { menu = true }
                    .semantics { contentDescription = "More for ${b.title}" },
                contentAlignment = Alignment.Center,
            ) { Icon(Glyphs.more, null, Modifier.size(Space.l - 3.dp), tint = t.dim) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Take off the shelf", style = Type.body) },
                    onClick = { menu = false; onRemove() })
            }
        }
    }
}
