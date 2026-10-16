package dev.dumbtalk.app

import android.view.KeyEvent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import org.junit.Assert.assertFalse
import org.junit.Test

class NewMessageFlowTest : KeypadTest() {
    @Test
    fun contactsFilterAndOpenAChat() {
        waitFor("Maya Chen")
        press(KeyEvent.KEYCODE_1) // Menu
        waitFor("New message")
        press(KeyEvent.KEYCODE_DPAD_CENTER) // first tile
        waitFor("Search contacts")
        waitFor("Alex Kim") // a contact with no chat yet, from every demo service

        // Typing narrows the list (the search box has focus on arrival).
        compose.onNode(hasSetTextAction()).performTextInput("alex")
        waitUntil("the list to narrow") { !exists(androidx.compose.ui.test.hasText("Jamie Patel", substring = true)) }
        waitFor("Alex Kim")

        // Robolectric's text field keeps the D-pad for its cursor (on the
        // phone down leaves it), so focus the match directly and open it.
        compose.onAllNodesWithText("Alex Kim", substring = true)[0].requestFocus() // one per demo service
        step()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("No messages yet")
        waitFor("Alex Kim")
        assertFalse("the app closed", compose.activity.isFinishing)

        // Back goes to the inbox, not the picker.
        press(KeyEvent.KEYCODE_BACK)
        waitFor("Maya Chen")
    }

    @Test
    fun enterANumberStillWorks() {
        waitFor("Maya Chen")
        press(KeyEvent.KEYCODE_1)
        waitFor("New message")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Enter a number…")
        compose.onNodeWithText("Enter a number…").requestFocus()
        step()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Send with")
        // Back out to the picker, then the inbox.
        press(KeyEvent.KEYCODE_BACK)
        waitFor("Search contacts")
        press(KeyEvent.KEYCODE_BACK)
        waitFor("Maya Chen")
    }
}
