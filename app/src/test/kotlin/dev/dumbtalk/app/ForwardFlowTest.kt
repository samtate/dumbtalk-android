package dev.dumbtalk.app

import android.view.KeyEvent
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import org.junit.Assert.assertFalse
import org.junit.Test

class ForwardFlowTest : KeypadTest() {
    @Test
    fun forwardingSendsTheMessageToAnotherChat() {
        waitFor("Maya Chen")
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER) // Family (WhatsApp)
        waitFor("Lunch at ours on Sunday?")
        press(KeyEvent.KEYCODE_DPAD_LEFT) // the latest message's panel
        waitFor("Forward")
        compose.onNodeWithText("Forward").requestFocus()
        step()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Forward to")

        // The first target is the newest other chat; picking it returns to the chat.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Lunch at ours on Sunday?")
        assertFalse("the app closed", compose.activity.isFinishing)

        // Back in the inbox the target's preview shows the forwarded text.
        press(KeyEvent.KEYCODE_BACK)
        waitFor("Forwarded from")
    }
}
