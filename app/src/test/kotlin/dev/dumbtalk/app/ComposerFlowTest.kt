package dev.dumbtalk.app

import android.view.KeyEvent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.requestFocus
import org.junit.Test

/** The centre key and the controls around the message box, as in the old DumbTalk. */
class ComposerFlowTest : KeypadTest() {
    private fun openMaya() {
        waitFor("Maya Chen")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Perfect! Meet at the station")
    }

    private fun focusControl(description: String, label: String) {
        compose.onNodeWithContentDescription(description).requestFocus()
        waitFor(label)
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
    fun centreOnAMessageReactsAndGotoQuoteJumps() {
        openMaya()
        // Maya's newest message replies to "Yes! 10am?", so centre jumps there.
        waitFor("GOTO QUOTE")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil("the quoted message to be selected") { exists(hasText("REACT")) }

        // A plain message: centre reacts with a thumbs up.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil("the reaction") { exists(hasText("👍", substring = true)) }
    }
}
