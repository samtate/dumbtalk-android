package dev.dumbtalk.app

import android.view.KeyEvent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performTextInput
import org.junit.Test

/** The centre key and the controls around the message box, as in the old DumbTalk. */
class ComposerFlowTest : KeypadTest() {
    private fun openMaya() {
        waitFor("Maya Chen")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Perfect! Meet at the station")
        // It opens on the last message read; the two unread are below it.
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
    }

    private fun focusControl(description: String, label: String) {
        compose.onNodeWithContentDescription(description).requestFocus()
        waitFor(label)
    }

    @Test
    fun rightSelectsFloatingBottomButtonAndLeftReturnsToTheMessage() {
        openMaya()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitUntil("bottom button focused") { exists(hasContentDescription("Return to bottom") and isFocused()) }
        waitFor("BOTTOM")
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor("GOTO QUOTE")
        press(KeyEvent.KEYCODE_DPAD_CENTER) // follow the quote to an older message
        waitFor("ACTIONS")
        press(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil("composer focused after returning to bottom") { exists(hasSetTextAction() and isFocused()) }
        waitUntil("bottom button hidden at bottom") { !exists(hasContentDescription("Return to bottom")) }
        press(KeyEvent.KEYCODE_BACK)
        waitFor("GOTO QUOTE") // Back now returns to the newest message, not the old origin.
    }

    @Test
    fun leftRepliesToTheSelectedMessageAndKeepsTheDraft() {
        openMaya()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(hasSetTextAction()).performTextInput("Existing draft")
        press(KeyEvent.KEYCODE_BACK)
        // Centre follows the quote to an older message; Left must reply to it.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("ACTIONS")
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor("Replying to")
        waitFor("Yes! 10am?")
        waitUntil("reply input focused with draft preserved") {
            exists(hasSetTextAction() and hasText("Existing draft") and isFocused())
        }
    }

    @Test
    fun eachControlRelabelsTheCentreKey() {
        openMaya()
        focusControl("Record voice note", "RECORD")
        focusControl("Send attachment", "ATTACH")
        focusControl("Emoji", "EMOJI")
        focusControl("Send", "SEND")
    }

    @Test
    fun downFromTheNewestMessageReachesTheMessageBox() {
        openMaya()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        // Up from the box is not driven here: Robolectric's text field keeps the D-pad keys.
        waitUntil("the message box to be focused") { exists(hasText("TYPE")) }
    }

    @Test
    fun attachAndVoiceOpenModalPanelsThatBackCloses() {
        openMaya()
        focusControl("Send attachment", "ATTACH")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Send attachment") // the panel's header
        waitFor("Take photo")
        press(KeyEvent.KEYCODE_BACK)
        waitUntil("the chat again") { exists(hasSetTextAction()) }

        focusControl("Record voice note", "RECORD")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Voice note")
        waitFor("Press Record")
        press(KeyEvent.KEYCODE_BACK)
        waitUntil("the chat again") { exists(hasSetTextAction()) }
    }

    @Test
    fun composerOptionsReturnToTheirOriginAndBackReturnsToMessages() {
        openMaya()
        focusControl("Send", "SEND")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        waitFor("CHAT")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Chat options")
        press(KeyEvent.KEYCODE_BACK)
        waitUntil("Send focus restored") { exists(hasContentDescription("Send") and isFocused()) }
        press(KeyEvent.KEYCODE_BACK)
        waitFor("Perfect! Meet at the station")
        waitFor("1 ACTIONS")
    }

    @Test
    fun nestedChatOptionsReturnToTheirMenuItemAndReplyFocusesTyping() {
        openMaya()
        press(KeyEvent.KEYCODE_1)
        waitFor("Message actions")
        compose.onNodeWithText("Chat options").requestFocus()
        step()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Mute")
        press(KeyEvent.KEYCODE_BACK)
        waitFor("Message actions")
        waitUntil("Chat options focus restored") { compose.onAllNodes(hasText("Chat options") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Reply").requestFocus()
        step()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Replying to")
        waitUntil("reply input focus") { exists(hasSetTextAction() and isFocused()) }
    }

    @Test
    fun centreOnAMessageOpensActionsAndGotoQuoteJumps() {
        openMaya()
        // Maya's newest message replies to "Yes! 10am?", so centre jumps there.
        waitFor("GOTO QUOTE")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil("the quoted message to be selected") { exists(hasText("ACTIONS")) }

        // A plain message opens its actions without changing it.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Reply")
        waitFor("QUICK REACTION")
    }

    @Test
    fun typingAtSuggestsGroupMembersAndPickingOneCompletesTheName() {
        waitFor("Maya Chen")
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER) // Family
        waitFor("Count me in!")
        press(KeyEvent.KEYCODE_DPAD_DOWN) // to the message box
        compose.onNode(hasSetTextAction()).performTextInput("Ask @m")
        waitFor("@Mum")
        // Up from the box reaches the suggestion (on a phone; Robolectric's
        // text field keeps Up for its cursor, so focus it directly); centre picks it.
        compose.onNode(hasText("@Mum")).requestFocus()
        step()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil("name completed and typing resumed") {
            exists(hasSetTextAction() and hasText("Ask @Mum ") and isFocused())
        }
        waitUntil("suggestions gone") { !exists(hasText("@Mum")) }
    }
}
