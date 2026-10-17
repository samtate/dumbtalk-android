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
    fun textBindingsYieldToTypingButHardwareBindingsStillWork() {
        val controller = SoftkeyController()
        var opened = 0
        compose.setContent {
            CompositionLocalProvider(LocalSoftkeys provides controller) {
                Softkeys(left = SoftkeyAction("Menu") { opened++ })
            }
        }
        compose.runOnIdle {
            for (code in listOf(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_POUND, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_SPACE)) {
                controller.leftShortcutCode = code
                controller.typing = true
                assertEquals(SoftkeyController.Result.IGNORED, controller.onKey(KeyEvent(KeyEvent.ACTION_DOWN, code)))
                assertEquals(SoftkeyController.Result.IGNORED, controller.onKey(KeyEvent(KeyEvent.ACTION_UP, code)))
                controller.typing = false
                controller.onKey(KeyEvent(KeyEvent.ACTION_DOWN, code))
                controller.onKey(KeyEvent(KeyEvent.ACTION_UP, code))
            }
            assertEquals(4, opened)
            controller.leftShortcutCode = KeyEvent.KEYCODE_MENU
            controller.typing = true
            controller.onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU))
            assertEquals(5, opened)
        }
    }

    @Test
    fun captureBackInvokesCancelAndDoesNotCaptureNavigation() {
        val controller = SoftkeyController()
        var cancelled = false
        compose.setContent {
            CompositionLocalProvider(LocalSoftkeys provides controller) {
                Softkeys(right = SoftkeyAction("Cancel") { controller.cancelLeftShortcutCapture(); cancelled = true })
            }
        }
        compose.runOnIdle {
            controller.beginLeftShortcutCapture()
            controller.onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            assertNull(controller.capturedKeyCode)
            assertNotNull(controller.captureError)
            controller.onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            assertTrue(cancelled)
            assertFalse(controller.captureNextKey)
        }
    }

    @Test
    fun captureConsumesBothHalvesAndTestingDoesNotApplyTheNewBinding() {
        val controller = SoftkeyController()
        controller.beginLeftShortcutCapture()
        controller.onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CALL))
        assertEquals(KeyEvent.KEYCODE_CALL, controller.capturedKeyCode)
        controller.onKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CALL))
        controller.beginLeftShortcutCapture()
        controller.onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POUND))
        assertEquals(KeyEvent.KEYCODE_POUND, controller.capturedKeyCode)
        assertEquals(SoftkeyController.Result.HANDLED, controller.onKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_POUND)))
        controller.testShortcutCode = KeyEvent.KEYCODE_POUND
        controller.onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POUND))
        assertTrue(controller.shortcutTestPassed)
        assertEquals(KeyEvent.KEYCODE_1, controller.leftShortcutCode)
        assertTrue(SoftkeyController.isBindable(KeyEvent.KEYCODE_HOME))
        assertTrue(SoftkeyController.isBindable(KeyEvent.KEYCODE_CALL))
        assertTrue(SoftkeyController.isBindable(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(SoftkeyController.isBindable(KeyEvent.KEYCODE_DPAD_UP))
    }
}
