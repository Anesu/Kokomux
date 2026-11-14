package kokomux.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every screen, drawn with made-up books, at the S26 Ultra's width (384 dp)
 * and on a wide DeX screen, dark and light: build/screens/NAME.png, to look
 * at after any change to the screens.
 *   ./gradlew :app:testDebugUnitTest --tests kokomux.app.ui.ScreensTest
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE)
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, mode: ThemeMode, content: @Composable () -> Unit) {
        // The clock is moved by hand: the reader lights words every frame while it plays.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KokomuxTheme(mode) { Box(Modifier.fillMaxSize().background(LocalTokens.current.bg)) { content() } }
        }
        compose.mainClock.advanceTimeBy(1597)
        val out = File(System.getProperty("kokomux.screens")!!, "$name.png").apply { parentFile!!.mkdirs() }
        out.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val reader: @Composable () -> Unit = {
        ReaderScreen(Fake.playing(), Fake.state.prefs, { 1000L }, Fake.actions, ReaderTo())
    }

    private val home: @Composable () -> Unit = {
        HomeScreen(Fake.state, Fake.actions, {}, {}, {}, {}, {}, {})
    }

    /** A sheet as it slides up: on its own surface. */
    private fun sheet(content: @Composable () -> Unit): @Composable () -> Unit = {
        val t = LocalTokens.current
        Box(Modifier.fillMaxSize().background(t.ghost)) {
            Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth()
                .background(t.sheet, Corner.xl).padding(top = Space.l)) { content() }
        }
    }

    @Test fun readerDark() = shot("reader-dark", ThemeMode.DARK, reader)
    @Test fun readerLight() = shot("reader-light", ThemeMode.LIGHT, reader)
    @Test @Config(qualifiers = WIDE) fun readerWide() = shot("reader-wide", ThemeMode.DARK, reader)
    @Test fun readerPaused() = shot("reader-paused", ThemeMode.DARK) {
        ReaderScreen(Fake.playing(at = 8, playing = false, sleep = Sleep.Minutes(12 * 60_000L)), Fake.state.prefs, { 0L },
            Fake.actions, ReaderTo())
    }
    @Test fun readerGettingReady() = shot("reader-getting-ready", ThemeMode.LIGHT) {
        ReaderScreen(Fake.playing(at = 0, playing = false).copy(madeTo = 0), Fake.state.prefs, { 0L }, Fake.actions, ReaderTo())
    }

    @Test fun homeDark() = shot("home-dark", ThemeMode.DARK, home)
    @Test fun homeLight() = shot("home-light", ThemeMode.LIGHT, home)
    @Test @Config(qualifiers = WIDE) fun homeWide() = shot("home-wide", ThemeMode.LIGHT, home)
    @Test fun homeUpNext() = shot("home-up-next", ThemeMode.DARK) {
        HomeScreen(Fake.state.copy(upNext = Fake.upNext, shelf = Fake.shelf.take(2)), Fake.actions, {}, {}, {}, {}, {}, {})
    }
    @Test fun homeEmpty() = shot("home-empty", ThemeMode.DARK) {
        HomeScreen(Fake.state.copy(shelf = emptyList(), playing = null), Fake.actions, {}, {}, {}, {}, {}, {})
    }

    @Test fun firstRun() = shot("first-run", ThemeMode.DARK) { FirstRun(Setup.Missing, Fake.actions) }
    @Test fun firstRunDownloading() = shot("first-run-downloading", ThemeMode.LIGHT) {
        FirstRun(Setup.Downloading("Kokoro", 120_000_000, 354_000_000), Fake.actions)
    }
    @Test fun firstRunLoading() = shot("first-run-loading", ThemeMode.DARK) { FirstRun(Setup.Loading("misaki"), Fake.actions) }

    @Test fun settings() = shot("settings", ThemeMode.DARK) { SettingsScreen(Fake.state, Fake.actions, {}, {}) }
    @Test fun settingsPlain() = shot("settings-plain", ThemeMode.LIGHT) {
        SettingsScreen(Fake.state.copy(prefs = Fake.state.prefs.copy(focus = false, words = false)), Fake.actions, {}, {})
    }
    @Test fun voices() = shot("voices", ThemeMode.DARK) { VoicesScreen(Fake.state.copy(hearing = "af_heart"), Fake.actions, {}, {}) }
    @Test fun voicesLight() = shot("voices-light", ThemeMode.LIGHT) { VoicesScreen(Fake.state, Fake.actions, {}, {}) }
    @Test fun voicesMing() = shot("voices-ming-downloading", ThemeMode.DARK) {
        VoicesScreen(Fake.state.copy(prefs = Fake.state.prefs.copy(voice = "ming:UK_George")), Fake.actions, {}, {})
    }
    @Test fun voicesQwen() = shot("voices-qwen", ThemeMode.LIGHT) {
        VoicesScreen(Fake.state.copy(prefs = Fake.state.prefs.copy(voice = "qwen:en-us:Anesu")), Fake.actions, {}, {})
    }
    @Test fun newVoice() = shot("new-voice", ThemeMode.DARK) { NewVoiceScreen(Fake.state, Fake.actions) {} }
    @Test fun newVoiceRecording() = shot("new-voice-recording", ThemeMode.LIGHT) {
        NewVoiceScreen(Fake.state.copy(recording = 6.4 to 0.62f), Fake.actions) {}
    }
    @Test fun newVoiceClip() = shot("new-voice-clip", ThemeMode.DARK) {
        NewVoiceScreen(Fake.state.copy(clip = Clip(7.2)), Fake.actions) {}
    }

    @Test fun sheetChapters() = shot("sheet-chapters", ThemeMode.DARK, sheet { ChaptersSheet(Fake.playing(), emptyList(), false, {}) {} })
    @Test fun sheetNovelChapters() = shot("sheet-novel-chapters", ThemeMode.LIGHT, sheet {
        ChaptersSheet(Fake.playing().copy(novel = true), Fake.toc, false, {}) {}
    })
    @Test fun sheetFindNovel() = shot("sheet-find-novel", ThemeMode.DARK, sheet {
        WebSheet("goddess summon", Fake.state.copy(found = Fake.found), Fake.actions) {}
    })
    @Test fun sheetSleep() = shot("sheet-sleep", ThemeMode.DARK, sheet { SleepSheet(Fake.playing(sleep = Sleep.EndOfChapter)) {} })
    // A narrow screen (a larger display size, or the CI emulator's 320 dp) and large text: nothing cut off.
    @Test @Config(qualifiers = NARROW) fun homeNarrow() = shot("narrow-home", ThemeMode.DARK, home)
    @Test @Config(qualifiers = NARROW) fun readerNarrow() = shot("narrow-reader", ThemeMode.DARK, reader)
    @Test @Config(qualifiers = NARROW) fun settingsNarrow() = shot("narrow-settings", ThemeMode.LIGHT) { SettingsScreen(Fake.state, Fake.actions, {}, {}) }
    @Test @Config(qualifiers = NARROW) fun speedNarrow() = shot("narrow-speed", ThemeMode.DARK, sheet { SpeedSheet(1.2f) {} })
    @Test @Config(fontScale = 1.3f) fun homeLargeText() = shot("large-text-home", ThemeMode.LIGHT, home)
    @Test @Config(fontScale = 1.3f) fun readerLargeText() = shot("large-text-reader", ThemeMode.LIGHT, reader)
    @Test @Config(fontScale = 1.3f) fun voicesLargeText() = shot("large-text-voices", ThemeMode.DARK) { VoicesScreen(Fake.state, Fake.actions, {}, {}) }
    @Test @Config(fontScale = 1.3f) fun speedLargeText() = shot("large-text-speed", ThemeMode.LIGHT, sheet { SpeedSheet(1.2f) {} })

    @Test fun stats() = shot("stats", ThemeMode.DARK) { StatsScreen(Fake.stats, {}) }
    @Test fun statsLight() = shot("stats-light", ThemeMode.LIGHT) { StatsScreen(Fake.stats, {}) }
    @Test fun statsBook() = shot("stats-book", ThemeMode.LIGHT) { StatsScreen(Fake.stats, {}, book = "2") }
    @Test fun statsNone() = shot("stats-none", ThemeMode.DARK) { StatsScreen(null, {}) }
    @Test fun sheetSaved() = shot("sheet-saved", ThemeMode.DARK, sheet {
        SavedSheet(Fake.passages.filter { it.book == "0123456789abcdef" }, Fake.actions) {}
    })
    @Test fun saved() = shot("saved", ThemeMode.LIGHT) { SavedScreen(Fake.passages, Fake.actions, {}) {} }
    @Test fun sheetAudiobook() = shot("sheet-audiobook", ThemeMode.LIGHT, sheet { AudiobookSheet(Fake.playing(), null) { _, _, _ -> } })
    @Test fun sheetAudiobookSaving() = shot("sheet-audiobook-saving", ThemeMode.DARK, sheet { AudiobookSheet(Fake.playing(), 13 to 21) { _, _, _ -> } })
    @Test @Config(qualifiers = NARROW) fun voicesNarrow() = shot("narrow-voices-qwen", ThemeMode.DARK) {
        VoicesScreen(Fake.state.copy(prefs = Fake.state.prefs.copy(voice = "qwen:en-us:Anesu")), Fake.actions, {}, {})
    }
    @Test @Config(qualifiers = NARROW) fun statsNarrow() = shot("narrow-stats", ThemeMode.LIGHT) { StatsScreen(Fake.stats, {}) }
    @Test @Config(qualifiers = NARROW) fun newVoiceNarrow() = shot("narrow-new-voice", ThemeMode.DARK) {
        NewVoiceScreen(Fake.state.copy(clip = Clip(7.2)), Fake.actions) {}
    }
    @Test @Config(fontScale = 1.3f) fun statsLargeText() = shot("large-text-stats", ThemeMode.DARK) { StatsScreen(Fake.stats, {}) }
    @Test @Config(fontScale = 1.3f) fun findLargeText() = shot("large-text-find-novel", ThemeMode.LIGHT, sheet {
        WebSheet("goddess summon", Fake.state.copy(found = Fake.found), Fake.actions) {}
    })
    @Test @Config(qualifiers = NARROW, fontScale = 1.3f) fun readerNarrowLarge() = shot("narrow-large-reader", ThemeMode.DARK, reader)
    @Test fun readerPreparingMing() = shot("reader-preparing-ming", ThemeMode.DARK) {
        ReaderScreen(Fake.playing(at = 0).copy(voice = "ming:UK_George", preparing = "Getting Ming ready for this phone · 2 of 5"),
            Fake.state.prefs, { 0L }, Fake.actions, ReaderTo())
    }
    @Test fun sheetSpeed() = shot("sheet-speed", ThemeMode.LIGHT, sheet { SpeedSheet(1.2f) {} })
    @Test fun sheetPage() = shot("sheet-page", ThemeMode.DARK, sheet { WebSheet("https://example.com/the-long-road", Fake.state, Fake.actions) {} })
    @Test fun sheetType() = shot("sheet-type", ThemeMode.LIGHT, sheet { TypeSheet {} })
}

/** The S26 Ultra held upright (One UI's default resolution), drawn at 2× to keep the files small. */
const val PHONE = "w384dp-h832dp-xhdpi"
/** The narrowest the phone gets (the largest display size); the CI emulator's screen too. */
const val NARROW = "w320dp-h640dp-mdpi"
/** Samsung DeX on a 1080p screen. */
const val WIDE = "w1280dp-h720dp-mdpi"
