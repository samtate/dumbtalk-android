package dev.dumbtalk.app

import android.view.KeyEvent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import org.junit.Assert.assertFalse
import org.junit.Test

class MediaFlowTest : KeypadTest() {
    @Test
    fun photoOpensInTheViewerAndBackReturnsToTheChat() {
        waitFor("Maya Chen")
        press(KeyEvent.KEYCODE_DPAD_CENTER) // open the first chat
        waitFor("Perfect! Meet at the station")

        // The photo sits above the latest message and shows inline.
        waitUntil("the inline photo") { exists(hasContentDescription("Photo")) }

        // Select it, open its panel (1), and choose View: the first action,
        // two rows below the first reaction.
        press(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_1)
        waitFor("QUICK REACTION")
        waitFor("View")
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)

        // The viewer replaces the chat: no composer, photo on screen.
        waitUntil("the viewer") { !exists(hasSetTextAction()) && exists(hasContentDescription("Photo")) }

        // Back closes the viewer, not the chat or the app.
        press(KeyEvent.KEYCODE_BACK)
        waitUntil("the chat again") { exists(hasSetTextAction()) }
        waitFor("Perfect! Meet at the station")
        assertFalse("Back closed the app", compose.activity.isFinishing)
    }
}
