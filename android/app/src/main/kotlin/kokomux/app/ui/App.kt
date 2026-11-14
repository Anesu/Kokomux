package kokomux.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/** The screens: home, the reader, settings, the voice picker, making a voice, listening stats, saved passages. */
enum class Screen { HOME, READER, SETTINGS, VOICES, NEW_VOICE, STATS, SAVED }

/** What slides up over a screen. */
enum class Sheet { CHAPTERS, SLEEP, SPEED, PAGE, TYPE, SAVED, AUDIOBOOK }

/**
 * The whole app: first run until Kokoro is ready, then home, the reader,
 * settings and voices, a fade between them (233 ms in, 144 ms out), and the
 * sheets. [position] is how far into the part the player is; [link] is a web
 * address on the clipboard, if there is one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KokomuxApp(state: UiState, position: () -> Long, link: () -> String, actions: Actions) {
    KokomuxTheme(state.prefs.theme) {
        val t = LocalTokens.current
        if (state.setup != Setup.Ready) {
            FirstRun(state.setup, actions)
            return@KokomuxTheme
        }
        // Where you've been, home at the bottom: back goes to the one before.
        var stack by rememberSaveable { mutableStateOf(if (state.playing != null) listOf(Screen.HOME, Screen.READER) else listOf(Screen.HOME)) }
        val screen = stack.last()
        var sheet by rememberSaveable { mutableStateOf<Sheet?>(null) }
        var statsOf by rememberSaveable { mutableStateOf<String?>(null) }       // the book the stats open on (null: all)
        fun go(to: Screen) {
            sheet = null
            stack = when (to) {
                Screen.HOME -> listOf(Screen.HOME)
                Screen.READER -> listOf(Screen.HOME, Screen.READER)
                else -> stack.filter { it != to } + to
            }
        }
        fun back() {
            sheet = null
            stack = stack.dropLast(1).filter { it != Screen.READER || state.playing != null || state.opening }
                .ifEmpty { listOf(Screen.HOME) }
        }
        // Which parts to save as an audiobook, while the phone asks where.
        var saveParts by rememberSaveable { mutableStateOf<Pair<Int, Int>?>(null) }
        val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { uri ->
            val parts = saveParts
            if (uri != null && parts != null) actions.saveAudiobook(uri, parts.first, parts.second)
            saveParts = null
        }
        // Something new is shown in the reader, unless a book is playing: then it waits in Up next.
        fun opened() {
            if (state.playing == null) go(Screen.READER)
        }
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) { actions.openFile(uri); opened() }
        }
        // Something being opened (shared to the app, or the widget's play) is shown in the reader.
        LaunchedEffect(state.opening) { if (state.opening && screen != Screen.READER) go(Screen.READER) }
        // A book heard to the end, or stopped: the reader has nothing left to show.
        LaunchedEffect(state.playing == null, state.opening) {
            if (screen == Screen.READER && state.playing == null && !state.opening && state.error.isEmpty()) go(Screen.HOME)
        }
        BackHandler(enabled = stack.size > 1) { back() }

        AnimatedContent(screen, label = "screen",
            transitionSpec = { fadeIn(tween(Motion.MEDIUM)) togetherWith fadeOut(tween(Motion.QUICK)) }) { s ->
            when (s) {
                Screen.HOME -> HomeScreen(state, actions,
                    onReader = { go(Screen.READER) },
                    onOpenFile = { pick.launch(arrayOf("*/*")) },
                    onPage = { sheet = Sheet.PAGE }, onType = { sheet = Sheet.TYPE },
                    onVoices = { go(Screen.VOICES) }, onSettings = { go(Screen.SETTINGS) },
                    onStats = { statsOf = it; actions.loadStats(); go(Screen.STATS) }, onSaved = { go(Screen.SAVED) })
                Screen.READER -> {
                    val p = state.playing
                    if (p == null) Opening(state, actions) { go(Screen.HOME) }
                    else ReaderScreen(p, state.prefs, position, actions, ReaderTo(
                        home = { go(Screen.HOME) }, speed = { sheet = Sheet.SPEED }, sleep = { sheet = Sheet.SLEEP },
                        chapters = { sheet = Sheet.CHAPTERS }, voices = { go(Screen.VOICES) }, settings = { go(Screen.SETTINGS) },
                        stats = { statsOf = p.id; actions.loadStats(); go(Screen.STATS) }, saved = { sheet = Sheet.SAVED },
                        audiobook = { sheet = Sheet.AUDIOBOOK }))
                }
                Screen.SETTINGS -> SettingsScreen(state, actions, onBack = ::back, onVoices = { go(Screen.VOICES) })
                Screen.VOICES -> VoicesScreen(state, actions, onBack = ::back, onNewVoice = { go(Screen.NEW_VOICE) })
                Screen.NEW_VOICE -> NewVoiceScreen(state, actions, onBack = ::back)
                Screen.STATS -> StatsScreen(state.stats, onBack = ::back, book = statsOf)
                Screen.SAVED -> SavedScreen(state.passages, actions, onBack = ::back) { go(Screen.READER) }
            }
        }

        // Something just added to Up next: said for a moment, with the way to hear it now instead.
        val added = state.added
        if (added != null && sheet == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            LaunchedEffect(added) { delay(5000); actions.dismissAdded() }
            Bar("Up next: ${added.title}", "Play now", Modifier.safeDrawingPadding()) { actions.playQueued(added.id); go(Screen.READER) }
        }
        // A word for a moment ("Saved: …", "Up to date: …"), over whichever screen.
        if (state.note.isNotEmpty() && added == null && sheet == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            LaunchedEffect(state.note) { delay(5000); actions.dismissError() }
            Bar(state.note, "OK", Modifier.safeDrawingPadding(), actions::dismissError)
        }

        val open = sheet ?: return@KokomuxTheme
        ModalBottomSheet(
            onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = t.sheet, contentColor = t.fg, shape = Corner.xl,
        ) {
            val p = state.playing
            when (open) {
                Sheet.CHAPTERS -> if (p != null) {
                    LaunchedEffect(p.id) { if (p.novel) actions.loadToc() }
                    ChaptersSheet(p, state.toc, state.tocLoading, { actions.jump(it); sheet = null }) { actions.chapter(it); sheet = null }
                }
                Sheet.SLEEP -> if (p != null) SleepSheet(p) { actions.sleep(it); sheet = null }
                Sheet.SPEED -> SpeedSheet(p?.speed ?: state.prefs.speed, actions::speed)
                Sheet.PAGE -> WebSheet(link(), state, actions) { actions.openPage(it); sheet = null; opened() }
                Sheet.TYPE -> TypeSheet { actions.readTyped(it); sheet = null; opened() }
                Sheet.SAVED -> SavedSheet(state.passages.filter { it.book == p?.id }, actions) { sheet = null }
                Sheet.AUDIOBOOK -> if (p != null) AudiobookSheet(p, state.saving) { what, from, to ->
                    saveParts = from to to
                    sheet = null
                    saveTo.launch(kokomux.app.Export.fileName(p.title, what))
                }
            }
        }
    }
}

/** A book or page being opened, the mark's bars moving (or what went wrong, and back). */
@Composable
private fun Opening(state: UiState, actions: Actions, onHome: () -> Unit) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopBar("", onHome)
        GoldenColumn(Modifier.fillMaxSize()) {
            Logo(Space.xl, live = state.error.isEmpty())
            Spacer(Modifier.height(Space.l))
            if (state.error.isNotEmpty()) {
                Text(state.error, style = Type.body, color = t.red)
                Spacer(Modifier.height(Space.l))
                MainButton("Back") { actions.dismissError(); onHome() }
            } else Text("Opening…", style = Type.title, color = t.fg)
        }
    }
}
