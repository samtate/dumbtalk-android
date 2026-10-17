package dev.dumbtalk.app

import android.view.KeyEvent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import org.junit.Assert.assertFalse
import org.junit.Test

class MediaFlowTest : KeypadTest() {
    private fun openMayaAndSelectThePhoto() {
        waitFor("Maya Chen")
        press(KeyEvent.KEYCODE_DPAD_CENTER) // open the first chat
        waitFor("Perfect! Meet at the station")
        waitUntil("the inline photo") { exists(hasContentDescription("Photo")) }
        // Maya's photo and last message are unread, so the chat opens on the
        // last one read; the first unread, the photo, is just below it.
        press(KeyEvent.KEYCODE_DPAD_DOWN)
    }

    @Test
    fun centreOnAPhotoViewsItAndZoomsAndBackReturns() {
        openMayaAndSelectThePhoto()
        waitFor("VIEW PHOTO") // the centre key says what it will do
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        // The viewer replaces the chat; centre toggles Fit and Zoom.
        waitUntil("the viewer") { !exists(hasSetTextAction()) && exists(hasContentDescription("Photo")) }
        waitFor("ZOOM")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("FIT")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        waitFor("ZOOM")

        // Back closes the viewer, not the chat or the app.
        press(KeyEvent.KEYCODE_BACK)
        waitUntil("the chat again") { exists(hasSetTextAction()) }
        assertFalse("Back closed the app", compose.activity.isFinishing)
    }

    @Test
    fun theMessagePanelAlsoOffersView() {
        openMayaAndSelectThePhoto()
        press(KeyEvent.KEYCODE_1)
        waitFor("QUICK REACTION")
        waitFor("View")
        // The first action tile sits directly below the reaction rows.
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil("the viewer") { !exists(hasSetTextAction()) && exists(hasContentDescription("Photo")) }
    }
}
