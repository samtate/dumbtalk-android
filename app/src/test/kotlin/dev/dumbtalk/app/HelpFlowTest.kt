package dev.dumbtalk.app

import android.view.KeyEvent
import org.junit.Test
import org.robolectric.annotation.Config

class HelpFlowTest : KeypadTest() {
    @Test
    fun helpAndAboutOpenFromSettings() {
        waitFor("Maya Chen")
        // Left opens the menu; Settings is the fourth tile (second row, right).
        press(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Keys & help")
        // Services is focused; Keys & help is two rows down.
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("KEYS")
        // Back returns to Keys & help; About is three rows further.
        press(KeyEvent.KEYCODE_BACK)
        waitFor("About")
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("GNU Affero General Public License")
    }
}

@Config(application = FirstRunTestApp::class)
class FirstRunFlowTest : KeypadTest() {
    @Test
    fun welcomeComesFirstAndContinueReachesTheInbox() {
        waitFor("Welcome")
        waitFor("STAYING CONNECTED")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Maya Chen")
    }
}
