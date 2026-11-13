package kokomux.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On the phone (or an emulator): misaki, spaCy and espeak-ng give every part
 * of pronunciation.json the sounds engine.py gives it in the container.
 *   ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class PronunciationTest {
    @Test
    fun soundsMatchEnginePy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = JSONObject(Text(context).check())
        val differ = result.getJSONArray("differ")
        println("kokomux: ${result.getInt("cases") - differ.length()} of ${result.getInt("cases")} parts sound the same")
        assertEquals(differ.toString(1), 0, differ.length())
    }
}
