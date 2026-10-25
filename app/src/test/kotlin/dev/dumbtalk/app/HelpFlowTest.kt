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
        waitFor("Menu, or a message")
        // ▶ turns the page; ◀ (the left softkey) turns it back.
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("Pick a message")
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor("Menu, or a message")
        // Back returns to Keys & help; About is five rows further (Back up,
        // Restore, Share debug log, Try the demo, About).
        press(KeyEvent.KEYCODE_BACK)
        waitFor("About")
        repeat(5) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("GNU AGPL 3.0")
    }
}

@Config(application = FirstRunTestApp::class)
class FirstRunFlowTest : KeypadTest() {
    @Test
    fun welcomeComesFirstAndContinueReachesTheInbox() {
        waitFor("No tracking")
        // Five pages: centre turns each one, and Start on the last.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Menu, or a message")
        press(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("START")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Maya Chen")
    }
}
