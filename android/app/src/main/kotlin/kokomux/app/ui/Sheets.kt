package kokomux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A sheet's frame: its name, a line saying what it's for, then its content, in the reading column. */
@Composable
fun SheetFrame(title: String, about: String = "", content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTokens.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Space.column).fillMaxWidth().padding(horizontal = Space.l).padding(bottom = Space.xl)) {
            Text(title, style = Type.title, color = t.fg)
            if (about.isNotEmpty()) Dim(about, Modifier.padding(top = Space.xs))
            Spacer(Modifier.height(Space.m))
            content()
        }
    }
}

/** A choice in a sheet, the one in effect marked with the accent's bar and a tick. */
@Composable
fun Choice(title: String, caption: String = "", chosen: Boolean = false, onClick: () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.touch).clip(Corner.m).background(if (chosen) t.sel else t.bg.copy(alpha = 0f))
            .clickable(role = Role.Button, onClick = onClick).padding(end = Space.m),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Box(Modifier.width(3.dp).height(Space.l).clip(CircleShape).background(if (chosen) t.accent else t.bg.copy(alpha = 0f)))
        Column(Modifier.weight(1f).padding(vertical = Space.s)) {
            Text(title, style = Type.label, color = t.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (caption.isNotEmpty()) Dim(caption, maxLines = 1)
        }
        if (chosen) Icon(Glyphs.check, null, Modifier.size(Space.l - 3.dp), tint = t.accent)
    }
}

/** The book's chapters (tui_chapters): the one playing marked; pick one to go there. */
@Composable
fun ChaptersSheet(p: Playing, onPick: (Int) -> Unit) {
    val current = p.chapter
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (p.chapters.indexOf(current) - 2).coerceAtLeast(0))
    SheetFrame("Chapters", "${p.chapters.size} in ${p.title}") {
        LazyColumn(Modifier.heightIn(max = 480.dp), state = list, contentPadding = PaddingValues(bottom = Space.s)) {
            itemsIndexed(p.chapters) { i, c ->
                val last = (p.chapters.getOrNull(i + 1)?.first ?: (p.parts.size + 1)) - 1
                Choice(c.title, "Parts ${c.first} to $last", chosen = c == current) { onPick(c.first) }
            }
        }
    }
}

/** The sleep timer: 15, 30 or 60 minutes, the end of the chapter, or off. */
@Composable
fun SleepSheet(p: Playing, onPick: (Sleep?) -> Unit) {
    SheetFrame("Sleep timer", "The voice fades out over its last 13 seconds, then stops and keeps your place. " +
        "Time spent paused doesn't count.") {
        val now = p.sleep
        for (m in listOf(15, 30, 60)) Choice("$m minutes", chosen = now is Sleep.Minutes && sleepLabel(now) == "$m min") {
            onPick(Sleep.Minutes(m * 60_000L))
        }
        if (p.chapters.size > 1) Choice("End of chapter", p.chapter?.title ?: "", chosen = now == Sleep.EndOfChapter) {
            onPick(Sleep.EndOfChapter)
        }
        Choice("Off", chosen = now == null) { onPick(null) }
    }
}

/** The reader's menu (tui_menu): the voice, settings, the sleep timer, home, and stop. */
@Composable
fun MenuSheet(p: Playing, onVoices: () -> Unit, onSettings: () -> Unit, onSleep: () -> Unit, onHome: () -> Unit,
              onStop: () -> Unit) {
    SheetFrame("Menu") {
        ListRow(Glyphs.hear, "Voice", "${Voices.name(p.voice)} · ${Voices.describe(p.voice)}", onClick = onVoices)
        ListRow(Glyphs.settings, "Settings", "Speed, colours, focus, highlighting", onClick = onSettings)
        ListRow(Glyphs.moon, "Sleep timer", p.sleep?.let { sleepLabel(it) } ?: "Off", onClick = onSleep)
        ListRow(Glyphs.back, "Home screen", "The bookshelf; this keeps playing", onClick = onHome)
        ListRow(Glyphs.stop, "Stop", "Keeps your place on the bookshelf", onClick = onStop)
    }
}

/** A web page to read: its address (a copied link is filled in). */
@Composable
fun PageSheet(suggested: String, onRead: (String) -> Unit) {
    val t = LocalTokens.current
    var address by rememberSaveable { mutableStateOf(suggested) }
    val ok = address.trim().matches(Regex("^https?://\\S+$"))
    SheetFrame("Read a web page", "Its article, up to where the links start; a web novel's chapter is read as a chapter.") {
        OutlinedTextField(
            value = address, onValueChange = { address = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://…", style = Type.body, color = t.line) }, textStyle = Type.body, shape = Corner.l,
            leadingIcon = { Icon(Glyphs.globe, null, Modifier.size(Space.l - 3.dp), tint = t.dim) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (ok) onRead(address.trim()) }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = t.accent, unfocusedBorderColor = t.line),
        )
        Spacer(Modifier.height(Space.m))
        MainButton("Read it", enabled = ok) { onRead(address.trim()) }
    }
}

/** Something to type and hear. */
@Composable
fun TypeSheet(onRead: (String) -> Unit) {
    val t = LocalTokens.current
    var text by rememberSaveable { mutableStateOf("") }
    SheetFrame("Type something", "It's kept on the bookshelf until you've heard it to the end.") {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, minLines = 5, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("What should Kokoro read?", style = Type.reading, color = t.line) },
            textStyle = Type.reading.copy(color = t.fg), shape = Corner.l,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = t.accent, unfocusedBorderColor = t.line),
        )
        Spacer(Modifier.height(Space.m))
        MainButton("Read it", enabled = text.isNotBlank()) { onRead(text) }
    }
}

/** The one thing to do here: the accent, full width, a thumb's height. */
@Composable
fun MainButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val t = LocalTokens.current
    Button(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch), shape = Corner.l,
        colors = ButtonDefaults.buttonColors(containerColor = t.accent, contentColor = t.bg, disabledContainerColor = t.surf,
            disabledContentColor = t.line),
    ) { Text(label, style = Type.label) }
}
