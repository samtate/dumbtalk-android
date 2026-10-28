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
    fun smsDemoWalkthrough() {
        waitFor("Dentist")
        compose.onNode(hasText("Dentist")).requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Please arrive ten minutes early.")
        shoot("sms")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(hasSetTextAction()).performTextInput("See you tomorrow.")
        waitFor("SEND")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("See you tomorrow.")
        val app = compose.activity.application as DumbTalkApp
        check(app.registry.byId(dev.dumbtalk.core.ServiceId.SMS) is dev.dumbtalk.core.demo.DemoService)
    }

    @Test
    fun keypadWalkthrough() {
        waitFor("Maya Chen")
        shoot("inbox")

        // Second row is Family (favourite); Left opens the menu for it.
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor("Unstar")
        waitFor("CHAT · FAMILY")
        waitFor("DUMBTALK")
        shoot("menu")

        // Back returns to the inbox and the centre key opens Family.
        press(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Count me in!")
        shoot("chat")

        // The latest message starts selected; Left opens its actions.
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor("QUICK REACTION")
        shoot("message-actions")

        // Centre selects Reply; Cancel leaves the draft, then Back returns to messages.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Replying to")
        press(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BACK)
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
        waitFor("◀ ACTIONS")

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
        press(KeyEvent.KEYCODE_BACK) // from the composer to the messages
        waitFor("◀ ACTIONS")

        // Back to the inbox. The same press must not also trigger the
        // inbox's Exit (it once closed the app on key-up).
        press(KeyEvent.KEYCODE_BACK)
        waitFor("Maya Chen")
        check(!compose.activity.isFinishing) { "Back from a chat closed the app" }

        // Open Settings from the menu, then Services.
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor("Settings")
        // The selected chat's section now precedes app actions. Focus Settings
        // directly so this walkthrough does not depend on the chat's tile count.
        compose.onNode(hasText("Settings")).requestFocus()
        step()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Services")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
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
        press(KeyEvent.KEYCODE_DPAD_CENTER) // back to Settings, where setup began
        waitFor("Background delivery")
    }
}
