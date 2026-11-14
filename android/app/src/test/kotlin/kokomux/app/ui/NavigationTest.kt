package kokomux.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
    }

    private fun show(state: UiState, asked: Asked) =
        compose.setContent { KokomuxApp(state, { 0L }, { "" }, asked) }

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun settingsThenVoiceThenBackTwiceIsHome() {
        val asked = Asked()
        show(Fake.state.copy(playing = null), asked)
        compose.onNodeWithText("Start something new").assertExists()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Voice and speech").assertExists()
        compose.onNodeWithText("Voice").performClick()
        compose.onNodeWithText("Filter: bf is British and female").assertExists()
        back()
        compose.onNodeWithText("Voice and speech").assertExists()
        back()
        compose.onNodeWithText("Start something new").assertExists()
    }

    @Test
    fun choosingAVoiceGoesBackAndKeepsIt() {
        val asked = Asked()
        show(Fake.state.copy(playing = null), asked)
        compose.onNodeWithText("Emma").performClick()                         // the home screen's voice row
        compose.onNodeWithText("George").performClick()
        assertEquals(listOf("voice bm_george"), asked.log)
        compose.onNodeWithText("Start something new").assertExists()
    }

    @Test
    fun aWebPageIsTypedAndRead() {
        val asked = Asked()
        show(Fake.state.copy(playing = null), asked)
        compose.onNodeWithText("Read a web page").performClick()
        compose.onNodeWithText("https://…").performTextInput("https://example.com/a")
        compose.onNodeWithText("Read it").performClick()
        assertEquals(listOf("page https://example.com/a"), asked.log)
    }

    @Test
    fun theReadersChaptersJump() {
        val asked = Asked()
        show(Fake.state.copy(playing = Fake.playing(playing = false)), asked)  // (opens on the reader: a book is playing)
        compose.onNodeWithContentDescription("Play").assertExists()
        compose.onNodeWithText("Chapters").performClick()
        compose.onNodeWithText("Chapter Two: The Harbour").performClick()
        assertEquals(listOf("jump 7"), asked.log)
    }

    @Test
    fun aBookOnTheShelfCarriesOn() {
        val asked = Asked()
        show(Fake.state.copy(playing = null), asked)
        compose.onNodeWithText("A Short History of Nearly Everything").performClick()
        assertEquals(listOf("resume 3"), asked.log)
    }
}
