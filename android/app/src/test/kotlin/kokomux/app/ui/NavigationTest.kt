package kokomux.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipe
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The app's flows, tapped through: where each tap goes, and back again. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = PHONE)
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Actions that note what was asked. */
    private class Asked : Actions by Fake.actions {
        val log = mutableListOf<String>()
        override fun jump(part: Int) { log += "jump $part" }
        override fun openPage(address: String) { log += "page $address" }
        override fun voice(voice: String) { log += "voice $voice" }
        override fun resume(id: String) { log += "resume $id" }
        override fun speed(speed: Float) { log += "speed $speed" }
        override fun stop() { log += "stop" }
        override fun previous() { log += "previous" }
        override fun sleep(choice: Sleep?) { log += "sleep $choice" }
        override fun hear(voice: String) { log += "hear $voice" }
        override fun readTyped(text: String) { log += "typed $text" }
        override fun readClipboard() { log += "clipboard" }
        override fun remove(id: String) { log += "remove $id" }
        override fun playPause() { log += "playPause" }
        override fun next() { log += "next" }
        override fun again() { log += "again" }
        override fun search(query: String) { log += "search $query" }
        override fun chapter(url: String) { log += "chapter $url" }
        override fun loadToc() { log += "toc" }
        override fun novelToc(found: Found) { log += "novel ${found.title}" }
        override fun savePassage() { log += "save" }
        override fun goToPassage(p: Saved) { log += "passage ${p.part}" }
    }

    private fun show(state: UiState, asked: Asked) =
        compose.setContent { KokomuxApp(state, { 0L }, { "" }, asked) }

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun settingsThenVoiceThenBackTwiceIsHome() {
        val asked = Asked()
        show(Fake.state.copy(playing = null), asked)
        compose.onNodeWithText("Bookshelf").assertExists()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Reading").assertExists()
        compose.onNodeWithText("Emma").performClick()                         // settings' voice row
        compose.onNodeWithText("All").assertExists()
        back()
        compose.onNodeWithText("Reading").assertExists()
        back()
        compose.onNodeWithText("Bookshelf").assertExists()
    }

    @Test
    fun choosingAVoiceGoesBackAndKeepsIt() {
        val asked = Asked()
        show(Fake.state.copy(playing = null), asked)
        compose.onNodeWithContentDescription("Voice Emma").performClick()     // the home screen's voice
        compose.onNodeWithText("George").performClick()
        assertEquals(listOf("voice bm_george"), asked.log)
        compose.onNodeWithText("Bookshelf").assertExists()
    }

    @Test
    fun aLanguageShowsOnlyItsVoices() {
        show(Fake.state.copy(playing = null), Asked())
        compose.onNodeWithContentDescription("Voice Emma").performClick()
        compose.onNode(hasText("Spanish") and isSelectable()).performClick()
        compose.onNodeWithText("Dora").assertExists()
        compose.onNodeWithText("George").assertDoesNotExist()
    }

    @Test
    fun aWebPageIsTypedAndRead() {
        val asked = Asked()
        show(Fake.state.copy(playing = null), asked)
        compose.onNodeWithText("Web page").performClick()
        compose.onNodeWithText("Address, or a novel's name").performTextInput("https://example.com/a")
        compose.onNodeWithText("Read").performClick()
        assertEquals(listOf("page https://example.com/a"), asked.log)
    }

    @Test
    fun aNovelIsFoundByNameAndStartedAtAChapter() {
        val asked = Asked()
        show(Fake.state.copy(playing = null, shelf = emptyList(), found = Fake.found, toc = Fake.toc), asked)
        compose.onNodeWithText("Web page").performClick()
        compose.onNodeWithText("Address, or a novel's name").performTextInput("goddess summon")
        compose.onNodeWithText("Find").performClick()                    // words find novels; an address would read
        compose.onNodeWithText("Starting with an SSS-Rank Goddess Summon").performClick()
        compose.onNodeWithText("Chapter 8: The Summoning").performClick()
        assertEquals(listOf("search goddess summon", "novel Starting with an SSS-Rank Goddess Summon",
            "page https://freewebnovel.com/novel/starting-with-an-sss-rank-goddess-summon/chapter-8"), asked.log)
    }

    @Test
    fun theSentenceJustHeardIsSavedAndFoundAgain() {
        val asked = Asked()
        show(Fake.state.copy(playing = Fake.playing(playing = false)), asked)
        compose.onNodeWithContentDescription("Save the sentence just heard").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Saved").performClick()
        compose.onNodeWithText("Nobody asked her", substring = true).performClick()
        assertEquals(listOf("save", "passage 9"), asked.log)
    }

    @Test
    fun aNovelsChaptersAreAllOfItsChapters() {
        val asked = Asked()
        show(Fake.state.copy(playing = Fake.playing(playing = false).copy(novel = true), toc = Fake.toc), asked)
        compose.onNodeWithContentDescription("Chapters").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("7 of 21").assertExists()
        compose.onNodeWithText("Chapter 9: S-Rank").performClick()
        assertEquals(listOf("toc", "chapter https://freewebnovel.com/novel/starting-with-an-sss-rank-goddess-summon/chapter-9"), asked.log)
    }

    @Test
    fun theReadersChaptersJump() {
        val asked = Asked()
        show(Fake.state.copy(playing = Fake.playing(playing = false)), asked)  // (opens on the reader: a book is playing)
        compose.onNodeWithContentDescription("Play").assertExists()
        compose.onNodeWithContentDescription("Chapters").performClick()
        compose.onNodeWithText("Chapter Two: The Harbour").performClick()
        assertEquals(listOf("jump 7"), asked.log)
    }

    @Test
    fun theReadersSpeedShowsItAndChangesIt() {
        val asked = Asked()
        show(Fake.state.copy(playing = Fake.playing(playing = false)), asked)
        compose.onNodeWithContentDescription("Speed 1.1×").performClick()
        compose.onNodeWithText("1.5×").performClick()
        compose.onNodeWithContentDescription("Faster").performClick()
        assertEquals(listOf("speed 1.5", "speed 1.2"), asked.log)               // (the fake keeps 1.1×: one more is 1.2×)
    }

    @Test
    fun stoppingFromTheReaderGoesHome() {
        val asked = Asked()
        show(Fake.state.copy(playing = Fake.playing(playing = false)), asked)
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Stop").performClick()
        assertEquals(listOf("stop"), asked.log)
        compose.onNodeWithText("Bookshelf").assertExists()
    }

    @Test
    fun aBookOnTheShelfCarriesOn() {
        val asked = Asked()
        show(Fake.state.copy(playing = null), asked)
        compose.onNodeWithText("A Short History of Nearly Everything").performClick()
        assertEquals(listOf("resume 3"), asked.log)
    }

    @Test
    fun theBarIsTappedOrDraggedToAPart() {
        val asked = Asked()
        show(Fake.state.copy(playing = Fake.playing(playing = false)), asked)
        val bar = compose.onNodeWithContentDescription("Position")
        bar.performTouchInput { click(percentOffset(0.75f, 0.5f)) }              // 1 + round(0.75 × 11) = 9
        bar.performTouchInput { swipe(percentOffset(0.1f, 0.5f), percentOffset(0.55f, 0.5f), 377) }   // 1 + round(0.55 × 11) = 7
        assertEquals(listOf("jump 9", "jump 7"), asked.log)
    }

    @Test
    fun previousSleepAndAVoiceFromTheReader() {
        val asked = Asked()
        show(Fake.state.copy(playing = Fake.playing(playing = false)), asked)
        compose.onNodeWithContentDescription("Previous").performClick()
        compose.onNodeWithContentDescription("Sleep timer").performClick()
        compose.onNodeWithText("30 min").performClick()
        compose.onNodeWithContentDescription("Voice Emma").performClick()
        compose.onNodeWithContentDescription("Hear George").performClick()      // heard, not chosen: still on the picker
        compose.onNodeWithText("All").assertExists()
        assertEquals(listOf("previous", "sleep Minutes(leftMs=1800000)", "hear bm_george"), asked.log)
    }

    @Test
    fun foldingTheReaderAwayAndBack() {
        val asked = Asked()
        show(Fake.state.copy(playing = Fake.playing(playing = false)), asked)
        compose.onNodeWithContentDescription("Home").performClick()
        compose.onNodeWithText("Bookshelf").assertExists()
        compose.onNodeWithText("The Long Road").performClick()                  // the card of what's playing
        compose.onNodeWithContentDescription("Chapters").assertExists()
        assertEquals(emptyList<String>(), asked.log)
    }

    @Test
    fun somethingTypedIsRead() {
        val asked = Asked()
        show(Fake.state.copy(playing = null), asked)
        compose.onNodeWithText("Type").performClick()
        compose.onNodeWithText("What should Kokoro read?").performTextInput("Hello there.")
        compose.onNodeWithText("Read").performClick()
        compose.onNodeWithText("Opening…").assertExists()                        // (the fake never opens it)
        assertEquals(listOf("typed Hello there."), asked.log)
    }

    @Test
    fun theClipboardIsReadInOneTap() {
        val asked = Asked()
        show(Fake.state.copy(playing = null), asked)
        compose.onNodeWithText("Clipboard").performClick()
        assertEquals(listOf("clipboard"), asked.log)
    }

    @Test
    fun aBookTakenOffTheShelfCanComeBack() {
        val asked = Asked()
        show(Fake.state.copy(playing = null), asked)
        compose.onNodeWithContentDescription("More for A Short History of Nearly Everything").performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("A Short History of Nearly Everything").assertDoesNotExist()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("A Short History of Nearly Everything").assertExists()
        assertEquals(emptyList<String>(), asked.log)

        compose.onNodeWithContentDescription("More for A Short History of Nearly Everything").performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(5_500)                                   // the undo bar goes, and the book with it
        compose.onNodeWithText("Undo").assertDoesNotExist()
        assertEquals(listOf("remove 3"), asked.log)
    }

    @Test
    fun termuxKeysWorkInTheReader() {
        val asked = Asked()
        show(Fake.state.copy(playing = Fake.playing(playing = false)), asked)
        compose.onRoot().performKeyInput {
            pressKey(Key.Spacebar); pressKey(Key.DirectionRight); pressKey(Key.DirectionLeft)
            pressKey(Key.RightBracket); pressKey(Key.Equals)
        }
        assertEquals(listOf("playPause", "next", "again", "jump 7", "speed 1.2"), asked.log)
        compose.onRoot().performKeyInput { pressKey(Key.C) }
        compose.onNodeWithText("Chapter Two: The Harbour").assertExists()
    }
}
