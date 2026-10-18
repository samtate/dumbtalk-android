package dev.dumbtalk.app

import android.view.KeyEvent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import org.junit.Test
import org.robolectric.annotation.Config

@Config(qualifiers = "w256dp-h400dp-hdpi")
class SettingsFlowTest : KeypadTest() {
    @Test
    fun settingsCanBeOpenedAndUsedEntirelyWithTheDpad() {
        waitFor("Maya Chen")
        // Left on the inbox list is the left softkey: Menu.
        press(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        waitUntil("Settings menu item focused") {
            compose.onAllNodes(hasText("Settings") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Background delivery")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitUntil("Background delivery focused") {
            compose.onAllNodes(hasText("Background delivery") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
