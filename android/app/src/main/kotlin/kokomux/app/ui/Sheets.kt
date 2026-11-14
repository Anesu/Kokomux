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
import androidx.compose.runtime.remember
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

/** A sheet's frame: its name (with its state on the right, if it has one), then its content, in the reading column, on the sheet's colour. */
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
            OnSurface(t.sheet) { content() }
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
            if (caption.isNotEmpty()) OnSurface(if (chosen) t.sel else LocalSurface.current ?: t.bg) { Dim(caption, maxLines = 1) }
        }
        if (chosen) Icon(Glyphs.check, null, Modifier.size(Space.l - 3.dp), tint = t.accent)
    }
}

/** A chapter in a list: its number, its name, whether it's been heard, and whether it's the one playing. */
data class ChapterItem(val number: Int, val title: String, val heard: Boolean, val now: Boolean)

/** Chapters, numbered: those heard quieter, the one playing marked; pick one to go there. Opens at the one playing. */
@Composable
fun ChapterList(items: List<ChapterItem>, onPick: (Int) -> Unit) {
    val t = LocalTokens.current
    val here = items.indexOfFirst { it.now }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (here - 2).coerceAtLeast(0))
    LazyColumn(Modifier.heightIn(max = 480.dp), state = list, contentPadding = PaddingValues(bottom = Space.s)) {
        itemsIndexed(items) { i, c ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = Space.touch).clip(Corner.m)
                    .background(if (c.now) t.sel else t.bg.copy(alpha = 0f))
                    .clickable(role = Role.Button) { onPick(i) }.padding(end = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(3.dp).height(Space.l).clip(CircleShape).background(if (c.now) t.accent else t.bg.copy(alpha = 0f)))
                Text("${c.number}", style = Type.small, color = if (c.now) t.accent else quiet(),
                    textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(Space.xl + Space.s).padding(end = Space.m))
                Text(c.title, style = Type.label, color = if (c.heard) quiet() else t.fg, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = Space.s))
            }
        }
    }
}

/**
 * The book's chapters (tui_chapters). A web novel's are all its chapters, from its own page
 * ([toc], fetched as the sheet opens); one not in this listen starts the novel again there.
 */
@Composable
fun ChaptersSheet(p: Playing, toc: List<TocEntry>, loading: Boolean, onPick: (Int) -> Unit, onChapter: (String) -> Unit) {
    if (p.novel && toc.isNotEmpty()) {
        val here = toc.indexOfFirst { it.now }
        SheetFrame("Chapters", if (here >= 0) "${toc[here].number} of ${toc.last().number}" else "") {
            ChapterList(toc.map { ChapterItem(it.number, it.label, it.heard, it.now) }) { onChapter(toc[it].url) }
        }
        return
    }
    val current = p.chapter
    val here = p.chapters.indexOf(current)
    SheetFrame("Chapters", if (loading) "…" else if (here >= 0) "${here + 1} of ${p.chapters.size}" else "") {
        ChapterList(p.chapters.mapIndexed { i, c -> ChapterItem(if (p.novel) c.number else i + 1, c.title, i < here, c == current) }) {
            onPick(p.chapters[it].first)
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

/**
 * A web page to read, or a web novel to find: an address reads that page (a copied link is
 * filled in); words find novels by name, then the one picked shows its chapters to start from.
 */
@Composable
fun WebSheet(suggested: String, state: UiState, actions: Actions, onRead: (String) -> Unit) {
    val t = LocalTokens.current
    var entry by rememberSaveable { mutableStateOf(suggested) }
    var novel by remember { mutableStateOf<Found?>(null) }
    val address = entry.trim().matches(Regex("^https?://\\S+$"))
    val n = novel
    if (n != null) {
        SheetFrame(n.title, n.latest) {
            if (state.tocLoading) Logo(Space.l, live = true)
            ChapterList(state.toc.map { ChapterItem(it.number, it.label, it.heard, it.now) }) { onRead(state.toc[it].url) }
        }
        return
    }
    SheetFrame("Web page or novel") {
        Field(entry, { entry = it }, "Address, or a novel's name", glyph = Glyphs.globe,
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = if (address) ImeAction.Go else ImeAction.Search),
            actions = KeyboardActions(onGo = { if (address) onRead(entry.trim()) },
                onSearch = { if (entry.isNotBlank()) actions.search(entry) }))
        Spacer(Modifier.height(Space.m))
        if (address || entry.isBlank()) MainButton("Read", enabled = address) { onRead(entry.trim()) }
        else if (state.searching) ProgressFill(0, 1, "Finding “${entry.trim()}”…")
        else MainButton("Find") { actions.search(entry) }
        if (!address && state.found.isNotEmpty()) {
            Spacer(Modifier.height(Space.s))
            for (f in state.found) Choice(f.title, f.latest) { novel = f; actions.novelToc(f) }
        }
    }
}

/** Something to type and hear. */
@Composable
fun TypeSheet(onRead: (String) -> Unit) {
    val t = LocalTokens.current
    var text by rememberSaveable { mutableStateOf("") }
    SheetFrame("Type") {
        Field(text, { text = it }, "Something to hear", minLines = 5, reading = true)
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

/**
 * Saving as an audiobook: this chapter, from here to the end, or the whole book (one .m4a, where
 * you choose); while one is being saved, how far it's got.
 */
@Composable
fun AudiobookSheet(p: Playing, saving: Pair<Int, Int>?, onPick: (name: String, from: Int, to: Int) -> Unit) {
    SheetFrame("Save as audiobook") {
        if (saving != null) {
            ProgressFill(saving.first.toLong(), saving.second.toLong(), "${saving.first} of ${saving.second} parts")
            return@SheetFrame
        }
        val n = p.parts.size
        val at = p.at.coerceAtLeast(1)
        val c = p.chapter
        if (c != null && p.chapters.size > 1) {
            val end = p.chapters.firstOrNull { it.first > c.first }?.first?.minus(1) ?: n
            Choice("This chapter", c.title) { onPick(c.title, c.first, end) }
        }
        if (at > 1) Choice("From here to the end", "${n - at + 1} of $n parts") { onPick("from ${p.chapter?.title ?: "part $at"}", at, n) }
        Choice(if (p.novel) "The chapters loaded" else "The whole book", "$n parts") { onPick("", 1, n) }
    }
}
