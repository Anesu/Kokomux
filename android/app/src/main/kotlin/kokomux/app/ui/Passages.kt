package kokomux.app.ui

import android.content.ClipData
import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** A book's saved passages, in a sheet over the reader: tap one to hear it again. */
@Composable
fun SavedSheet(passages: List<Saved>, actions: Actions, onGo: () -> Unit) {
    SheetFrame("Saved", if (passages.isEmpty()) "" else "${passages.size}") {
        if (passages.isEmpty()) Dim("Save marks the sentence just heard", style = Type.body)
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(passages, key = { it.saved }) { p -> PassageRow(p, false, actions) { actions.goToPassage(p); onGo() } }
        }
    }
}

/** Every saved passage, book by book, the newest first. */
@Composable
fun SavedScreen(passages: List<Saved>, actions: Actions, onBack: () -> Unit, onReader: () -> Unit) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopBar("Saved", onBack)
        LazyColumn(Modifier.widthIn(max = Space.column).fillMaxWidth().padding(horizontal = Space.l)) {
            for ((title, group) in passages.groupBy { it.title }) {
                item(key = "book-$title") { SectionTitle(title) }
                items(group, key = { it.saved }) { p -> PassageRow(p, false, actions) { actions.goToPassage(p); onReader() } }
            }
            item { Spacer(Modifier.height(Space.xxl)) }
        }
    }
}

/** A passage: its words as the reader sets them, where it is; tap to hear it again, ⋮ to copy, share or remove it. */
@Composable
private fun PassageRow(p: Saved, showBook: Boolean, actions: Actions, onGo: () -> Unit) {
    val t = LocalTokens.current
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val quote = "“${p.text}”\n— ${p.title}${if (p.chapter.isNotEmpty()) ", ${p.chapter}" else ""}"
    Row(
        Modifier.fillMaxWidth().clip(Corner.m).clickable(role = Role.Button, onClick = onGo).padding(vertical = Space.s),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Column(Modifier.weight(1f).padding(start = Space.xs)) {
            Text(p.text, style = Type.reading.copy(fontSize = Type.body.fontSize, lineHeight = Type.body.lineHeight), color = t.fg,
                maxLines = 5, overflow = TextOverflow.Ellipsis)
            Dim(listOfNotNull(p.title.takeIf { showBook }, p.chapter.ifEmpty { null }).joinToString(" · "), maxLines = 1)
        }
        Box {
            GlyphButton(Glyphs.more, "More") { menu = true }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Copy", style = Type.body) }, leadingIcon = { Icon(Glyphs.copy, null, Modifier.size(Space.l)) },
                    onClick = { menu = false; scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(p.title, quote))) } })
                DropdownMenuItem(text = { Text("Share", style = Type.body) }, leadingIcon = { Icon(Glyphs.share, null, Modifier.size(Space.l)) },
                    onClick = {
                        menu = false
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, quote), null))
                    })
                DropdownMenuItem(text = { Text("Remove", style = Type.body) }, leadingIcon = { Icon(Glyphs.trash, null, Modifier.size(Space.l)) },
                    onClick = { menu = false; actions.removePassage(p.saved) })
            }
        }
    }
}
