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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A sheet's frame: its name (with its state on the right, if it has one), then its content, in the reading column. */
@Composable
fun SheetFrame(title: String, aside: String = "", content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTokens.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Space.column).fillMaxWidth().padding(horizontal = Space.l).padding(bottom = Space.xl)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(title, style = Type.title, color = t.fg, modifier = Modifier.weight(1f))
                if (aside.isNotEmpty()) Text(aside, style = Type.small, color = t.accent2)
            }
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

/** The book's chapters (tui_chapters), numbered: those heard quieter, the one playing marked; pick one to go there. */
@Composable
fun ChaptersSheet(p: Playing, onPick: (Int) -> Unit) {
    val t = LocalTokens.current
    val current = p.chapter
    val here = p.chapters.indexOf(current)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (here - 2).coerceAtLeast(0))
    SheetFrame("Chapters", if (here >= 0) "${here + 1} of ${p.chapters.size}" else "") {
        LazyColumn(Modifier.heightIn(max = 480.dp), state = list, contentPadding = PaddingValues(bottom = Space.s)) {
            itemsIndexed(p.chapters) { i, c ->
                val chosen = c == current
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Space.touch).clip(Corner.m)
                        .background(if (chosen) t.sel else t.bg.copy(alpha = 0f))
                        .clickable(role = Role.Button) { onPick(c.first) }.padding(end = Space.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).height(Space.l).clip(CircleShape).background(if (chosen) t.accent else t.bg.copy(alpha = 0f)))
                    Text("${i + 1}", style = Type.small, color = if (chosen) t.accent else t.dim,
                        textAlign = TextAlign.End, modifier = Modifier.width(Space.xl).padding(end = Space.m))
                    Text(c.title, style = Type.label, color = if (i < here) t.dim else t.fg, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = Space.s))
                }
            }
        }
    }
}

/** The sleep timer: 15, 30 or 60 minutes, the end of the chapter, or off; the time left on the right. */
@Composable
fun SleepSheet(p: Playing, onPick: (Sleep?) -> Unit) {
    val now = p.sleep
    SheetFrame("Sleep timer", if (now is Sleep.Minutes) "${sleepLabel(now)} left" else "") {
        for (m in listOf(15, 30, 60)) Choice("$m min") { onPick(Sleep.Minutes(m * 60_000L)) }
        if (p.chapters.size > 1) Choice("End of chapter", p.chapter?.title ?: "", chosen = now == Sleep.EndOfChapter) {
            onPick(Sleep.EndOfChapter)
        }
        Choice("Off", chosen = now == null) { onPick(null) }
    }
}

/** The speed, heard at once: slower and faster a tenth at a time, and the usual ones a tap away. */
@Composable
fun SpeedSheet(speed: Float, onPick: (Float) -> Unit) {
    val t = LocalTokens.current
    SheetFrame("Speed") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            RoundButton(Glyphs.minus, "Slower", Space.xxl, enabled = speed > 0.55f) {
                onPick(((speed * 10 + 0.5f).toInt() - 1).coerceAtLeast(5) / 10f)
            }
            Text(speedLabel(speed), style = Type.display, color = t.accent2, textAlign = TextAlign.Center,
                modifier = Modifier.width(Space.xxxl + Space.xl))
            RoundButton(Glyphs.plus, "Faster", Space.xxl, enabled = speed < 1.95f) {
                onPick(((speed * 10 + 0.5f).toInt() + 1).coerceAtMost(20) / 10f)
            }
        }
        Spacer(Modifier.height(Space.l))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            for (v in listOf(0.8f, 1f, 1.2f, 1.5f, 2f)) {
                val on = Math.round(speed * 100) == Math.round(v * 100)
                Box(
                    Modifier.weight(1f).heightIn(min = 48.dp).clip(CircleShape).background(if (on) t.psel else t.surf)
                        .clickable(role = Role.Button) { onPick(v) },
                    contentAlignment = Alignment.Center,
                ) { Text(speedLabel(v), style = Type.label, color = if (on) t.accent else t.fg) }
            }
        }
    }
}

/** A web page to read: its address (a copied link is filled in). */
@Composable
fun PageSheet(suggested: String, onRead: (String) -> Unit) {
    val t = LocalTokens.current
    var address by rememberSaveable { mutableStateOf(suggested) }
    val ok = address.trim().matches(Regex("^https?://\\S+$"))
    SheetFrame("Web page") {
        OutlinedTextField(
            value = address, onValueChange = { address = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://…", style = Type.body, color = t.line) }, textStyle = Type.body, shape = Corner.l,
            leadingIcon = { Icon(Glyphs.globe, null, Modifier.size(Space.l - 3.dp), tint = t.dim) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (ok) onRead(address.trim()) }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = t.accent, unfocusedBorderColor = t.line),
        )
        Spacer(Modifier.height(Space.m))
        MainButton("Read", enabled = ok) { onRead(address.trim()) }
    }
}

/** Something to type and hear. */
@Composable
fun TypeSheet(onRead: (String) -> Unit) {
    val t = LocalTokens.current
    var text by rememberSaveable { mutableStateOf("") }
    SheetFrame("Type") {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, minLines = 5, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("What should Kokoro read?", style = Type.reading, color = t.line) },
            textStyle = Type.reading.copy(color = t.fg), shape = Corner.l,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = t.accent, unfocusedBorderColor = t.line),
        )
        Spacer(Modifier.height(Space.m))
        MainButton("Read", enabled = text.isNotBlank()) { onRead(text) }
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
