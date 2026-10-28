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
        waitFor("Reply, react, forward")
        // ▶ turns the page; ◀ (the left softkey) turns it back.
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("Pick a message")
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor("Reply, react, forward")
        // Back returns to Keys & help; About is six rows further (Menu key,
        // Back up, Restore, Share debug log, Try the demo, About).
        press(KeyEvent.KEYCODE_BACK)
        waitFor("About")
        repeat(6) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("GNU AGPL 3.0")
    }
}

@Config(application = FirstRunTestApp::class)
class FirstRunFlowTest : KeypadTest() {
    @Test
    fun welcomeComesFirstAndContinueReachesTheInbox() {
        // No other Dumb app has asked yet, so the menu key comes first; pressing D-pad Left picks it.
        waitFor("Menu key")
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor("No tracking")
        // Five pages: centre turns each one, and Start on the last.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Reply, react, forward")
        press(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("START")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        // Then the permissions, each explained; the right softkey leaves them for later.
        waitFor("Run in the background")
        press(KeyEvent.KEYCODE_BACK)
        waitFor("Maya Chen")
    }
}
