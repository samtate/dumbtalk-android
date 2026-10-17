package dev.dumbtalk.app

import android.view.KeyEvent
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Test
import org.robolectric.annotation.Config

@Config(qualifiers = "w256dp-h400dp-hdpi")
class SettingsFlowTest : KeypadTest() {
    @Test
    fun settingsCanBeOpenedAndUsedEntirelyWithTheDpad() {
        waitFor("Maya Chen")
        press(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        waitUntil("Settings menu item focused") {
            compose.onAllNodes(hasText("Settings") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Left action key")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitUntil("Left action key focused") {
            compose.onAllNodes(hasText("Left action key") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Current: 1")
    }

    @Test
    fun captureCancelTestAndSaveHaveSeparateSteps() {
        waitFor("Maya Chen")
        press(KeyEvent.KEYCODE_1)
        compose.onNodeWithText("Settings").performScrollTo().performClick()
        waitFor("Left action key")
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Current: 1")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Press your preferred key.")
        press(KeyEvent.KEYCODE_BACK)
        waitFor("Current: 1") // Cancel stays in the picker.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Press your preferred key.")
        press(KeyEvent.KEYCODE_POUND)
        waitFor("Use #?")
        waitFor("Press # again to test")
        press(KeyEvent.KEYCODE_POUND)
        waitFor("Key received")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Key setting saved.")
        press(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BACK)
        waitFor("Maya Chen")
        press(KeyEvent.KEYCODE_POUND)
        waitFor("Settings") // Saved shortcut now opens the app menu.
    }
}
