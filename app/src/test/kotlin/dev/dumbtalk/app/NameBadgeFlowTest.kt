package dev.dumbtalk.app

import android.view.KeyEvent
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import org.junit.Assert.assertTrue
import org.junit.Test

/** In a group, someone's name stays in view above their messages while scrolling through a run of them. */
class NameBadgeFlowTest : KeypadTest() {
    private fun tops(text: String) =
        compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot.top }

    /** The bottom of the chat header: anything above it is scrolled away. */
    private fun listTop() = compose.onAllNodes(hasText("Sunday Hikers"), useUnmergedTree = true)
        .fetchSemanticsNodes().maxOf { it.boundsInRoot.bottom }

    private fun openHikers() {
        waitFor("Maya Chen")
        repeat(10) {
            if (exists(hasAnyDescendant(hasText("Sunday Hikers")) and isFocused())) return@repeat
            press(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("See everyone at 8!")
        step(500)
    }

    private fun assertNameShown(where: String) {
        val top = listTop()
        assertTrue("no Priya name in view $where; on screen: ${visibleTexts()}", tops("Priya").any { it >= top })
    }

    @Test
    fun theTopMessageOfARunNamesItsSender() {
        openHikers()
        press(KeyEvent.KEYCODE_DPAD_UP) // Priya's last message; her first is far above
        step(500)
        assertNameShown("on the last of her messages")
        repeat(9) {
            press(KeyEvent.KEYCODE_DPAD_UP)
            step(500)
            assertNameShown("after ${it + 2} presses up")
        }
        // The first of the run: its own name is scrolled into view with it.
        waitFor("Bringing snacks")
        val name = tops("Priya").filter { it <= tops("Bringing snacks").first() }.maxOrNull()
        assertTrue("Priya's name above her first message is cut off", name != null && name >= listTop())
    }
}
