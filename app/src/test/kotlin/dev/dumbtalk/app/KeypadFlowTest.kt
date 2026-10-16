package dev.dumbtalk.app

import android.view.KeyEvent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import org.junit.Test

class KeypadFlowTest : KeypadTest() {
    @Test
    fun keypadWalkthrough() {
        waitFor("Maya Chen")
        shoot("inbox")

        // Second row is Family (favourite); 1 opens the menu for it.
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_1)
        waitFor("Unstar")
        shoot("menu")

        // Back returns to the inbox and the centre key opens Family.
        press(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Count me in!")
        shoot("chat")

        // The latest message starts selected; 1 opens its actions.
        press(KeyEvent.KEYCODE_1)
        waitFor("QUICK REACTION")
        shoot("message-actions")

        // Centre picks the focused 👍 reaction and returns to the chat.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("👍")
        waitFor("Count me in!")

        // Down from the newest message focuses the message box; centre sends. Back
        // leaves the composer and the softkeys come back (they once stuck).
        press(KeyEvent.KEYCODE_DPAD_DOWN) // from the newest message to the message box
        compose.onNode(hasSetTextAction()).performTextInput("Leaving now")
        waitFor("SEND")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        while (exists(hasSetTextAction() and hasText("Leaving now"))) step()
        press(KeyEvent.KEYCODE_BACK)
        waitFor("Leaving now")
        waitFor("1=MESSAGE")

        // Centre on the focused Send button sends (the Type softkey once took
        // the key). Robolectric's text field keeps D-pad right for its
        // cursor, so focus the button directly, as right does on a phone.
        press(KeyEvent.KEYCODE_DPAD_DOWN) // from the newest message to the message box
        compose.onNode(hasSetTextAction()).performTextInput("Via the button")
        compose.onNodeWithContentDescription("Send").requestFocus()
        step()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        while (exists(hasSetTextAction() and hasText("Via the button"))) step()
        waitFor("Via the button")

        // Back to the inbox. The same press must not also trigger the
        // inbox's Exit (it once closed the app on key-up).
        press(KeyEvent.KEYCODE_BACK)
        waitFor("Maya Chen")
        check(!compose.activity.isFinishing) { "Back from a chat closed the app" }

        // Open the menu and move to Services.
        press(KeyEvent.KEYCODE_1)
        waitFor("Services")
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("+15550100999")
        shoot("services")

        // Disconnect Signal (focused first), then relink it by QR.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Keep connected")
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Not connected")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Scan QR code")
        shoot("setup-qr")

        // The demo "scan" completes on its own after a few seconds.
        waitFor("Signal connected", timeoutMillis = 30_000)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Maya Chen")
    }
}
