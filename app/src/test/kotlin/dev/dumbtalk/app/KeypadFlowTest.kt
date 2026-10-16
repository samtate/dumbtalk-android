package dev.dumbtalk.app

import android.view.KeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the demo build with hardware key events through MainActivity, the
 * same path a keypad phone uses, and records screenshots of each screen.
 * The screen size approximates the Qin F22 Pro (480×640).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h427dp-hdpi")
class KeypadFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun press(vararg codes: Int) {
        for (code in codes) {
            compose.runOnUiThread {
                compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
            compose.waitForIdle()
        }
    }

    private fun waitFor(text: String) = compose.waitUntilAtLeastOneExists(hasText(text, substring = true), 10_000)

    private fun shoot(name: String) = compose.onRoot().captureRoboImage("../docs/screenshots/$name.png")

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

        // Back to the inbox, open the menu, move to Services.
        press(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_1)
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
        waitFor("Signal connected")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("Maya Chen")
    }
}
