package dev.dumbtalk.app

import android.view.KeyEvent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import org.junit.Test

class EmojiFlowTest : KeypadTest() {
    @Test
    fun moreReactionsPicksAnyEmoji() {
        waitFor("Maya Chen")
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER) // Family
        waitFor("Lunch at ours on Sunday?") // only in the chat; "Count me in!" is also an inbox preview
        press(KeyEvent.KEYCODE_1) // the message panel
        waitFor("More emoji")
        compose.onNodeWithText("More emoji").requestFocus()
        step()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("React")
        // The first emoji in the grid is 😀; picking it reacts and returns to the chat.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil("the reaction in the chat") { exists(hasText("😀", substring = true)) }
        waitFor("Lunch at ours on Sunday?")
    }

    @Test
    fun emojiButtonLandsInTheDraft() {
        waitFor("Maya Chen")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Perfect! Meet at the station")
        // The composer's emoji button relabels the centre key and opens the grid.
        compose.onNodeWithContentDescription("Emoji").requestFocus()
        waitFor("EMOJI")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("🥺") // the emoji grid is open
        press(KeyEvent.KEYCODE_DPAD_CENTER) // 😀
        waitUntil("the emoji in the draft") { exists(hasSetTextAction() and hasText("😀")) }
    }
}
