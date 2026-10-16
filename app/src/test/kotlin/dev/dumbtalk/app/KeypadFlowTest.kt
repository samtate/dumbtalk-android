package dev.dumbtalk.app

import android.view.KeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the demo build with hardware key events through MainActivity, the
 * same path a keypad phone uses, and records screenshots of each screen.
 * The screen matches the test phone: 640×960 at 240 dpi (427×640 dp).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w427dp-h640dp-hdpi", application = TestApp::class)
class KeypadFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun manualClock() {
        // Text fields animate their cursor forever, so Compose never reports
        // idle under Robolectric. Step the clock by hand instead of waiting.
        compose.mainClock.autoAdvance = false
    }

    private fun step(millis: Long = 50) {
        compose.mainClock.advanceTimeBy(millis)
        Thread.sleep(10) // let demo work on Dispatchers.Default run
    }

    private fun press(vararg codes: Int) {
        for (code in codes) {
            compose.runOnUiThread {
                compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
            step(100)
        }
    }

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String, timeoutMillis: Long = 15_000) {
        val matcher = hasText(text, substring = true)
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!exists(matcher)) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for \"$text\"" }
            step()
        }
    }

    private fun shoot(name: String) = step(500).let { compose.onRoot().captureRoboImage("../docs/screenshots/$name.png") }

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

        // Centre (Type) focuses the composer and centre (Send) sends. Back
        // leaves the composer and the softkeys come back (they once stuck).
        press(KeyEvent.KEYCODE_DPAD_CENTER)
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
        press(KeyEvent.KEYCODE_DPAD_CENTER)
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
