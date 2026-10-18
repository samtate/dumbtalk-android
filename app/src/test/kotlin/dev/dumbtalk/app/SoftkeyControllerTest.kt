package dev.dumbtalk.app

import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import dev.dumbtalk.app.ui.LocalSoftkeys
import dev.dumbtalk.app.ui.SoftkeyAction
import dev.dumbtalk.app.ui.SoftkeyController
import dev.dumbtalk.app.ui.Softkeys
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoftkeyControllerTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun handledCenterReleaseDoesNotActivateTheNextFocusedItem() {
        val controller = SoftkeyController()
        var activated = 0
        compose.setContent {
            CompositionLocalProvider(LocalSoftkeys provides controller) {
                Softkeys(center = SoftkeyAction("Save") { activated++ })
            }
        }
        compose.runOnIdle {
            controller.onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            controller.claimCenter(Any(), true)
            assertEquals(SoftkeyController.Result.HANDLED,
                controller.onKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)))
            assertEquals(1, activated)
        }
    }

    @Test
    fun unhandledLeftIsTheLeftSoftkeyExceptWhileTyping() {
        val controller = SoftkeyController()
        var opened = 0
        compose.setContent {
            CompositionLocalProvider(LocalSoftkeys provides controller) {
                Softkeys(left = SoftkeyAction("Menu") { opened++ })
            }
        }
        compose.runOnIdle {
            val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
            val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT)
            // The screen sees Left first; the controller only acts once it's passed on.
            assertEquals(SoftkeyController.Result.IGNORED, controller.onKey(down))
            assertTrue(controller.onUnhandled(down))
            assertEquals(SoftkeyController.Result.HANDLED, controller.onKey(up))
            assertEquals(1, opened)
            // In a text field Left moves the cursor instead.
            controller.typing = true
            assertFalse(controller.onUnhandled(down))
            assertEquals(1, opened)
            // The hardware Menu key still works.
            controller.typing = false
            controller.onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU))
            assertEquals(2, opened)
        }
    }
}
