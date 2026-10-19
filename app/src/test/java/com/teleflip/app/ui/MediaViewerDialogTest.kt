package com.teleflip.app.ui

import android.app.Application
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teleflip.app.R
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaViewerDialogTest {
    private lateinit var context: Application
    private lateinit var samplePhotoMessage: MessageModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.Theme_TeleFlip)
        samplePhotoMessage =
            MessageModel(
                id = 1L,
                chatId = 100L,
                senderName = "Test",
                text = "Photo caption",
                time = "12:00",
                isOutbound = false,
                mediaType = MediaType.PHOTO,
            )
    }

    @Test
    fun panBy_returnsFalseWhenUnzoomed_andTrueWhenZoomedIn() {
        val dialog = MediaViewerDialog(context, samplePhotoMessage, {}, {})
        dialog.show()

        assertEquals(1.0f, dialog.zoomLevel)
        assertFalse("panBy should return false when zoomLevel is 1.0f", dialog.panBy(10f, 10f))
        assertEquals(0f, dialog.panX)
        assertEquals(0f, dialog.panY)

        dialog.cycleZoomIn()
        assertTrue("zoomLevel should be greater than 1.0f after cycleZoomIn", dialog.zoomLevel > 1.0f)
        assertTrue("panBy should return true when zoomed in", dialog.panBy(10f, 10f))
        assertEquals(10f, dialog.panX)
        assertEquals(10f, dialog.panY)
    }

    @Test
    fun onKeyDown_dpadKeys_passedThroughWhenUnzoomed() {
        val dialog = MediaViewerDialog(context, samplePhotoMessage, {}, {})
        dialog.show()

        assertEquals(1.0f, dialog.zoomLevel)

        val dpadKeys =
            listOf(
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
            )

        for (keyCode in dpadKeys) {
            val event = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val handled = dialog.onKeyDown(keyCode, event)
            assertFalse("D-Pad key $keyCode should not be consumed when unzoomed", handled)
            assertEquals(0f, dialog.panX)
            assertEquals(0f, dialog.panY)
        }
    }

    @Test
    fun onKeyDown_dpadKeys_consumedWhenZoomedIn() {
        val dialog = MediaViewerDialog(context, samplePhotoMessage, {}, {})
        dialog.show()

        dialog.cycleZoomIn()
        assertTrue(dialog.zoomLevel > 1.0f)

        val upEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)
        val upHandled = dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, upEvent)
        assertTrue("KEYCODE_DPAD_UP should be consumed when zoomed in", upHandled)
        assertEquals(48f, dialog.panY)

        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
        val downHandled = dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, downEvent)
        assertTrue("KEYCODE_DPAD_DOWN should be consumed when zoomed in", downHandled)
        assertEquals(0f, dialog.panY)

        val leftEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
        val leftHandled = dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, leftEvent)
        assertTrue("KEYCODE_DPAD_LEFT should be consumed when zoomed in", leftHandled)
        assertEquals(48f, dialog.panX)

        val rightEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        val rightHandled = dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, rightEvent)
        assertTrue("KEYCODE_DPAD_RIGHT should be consumed when zoomed in", rightHandled)
        assertEquals(0f, dialog.panX)
    }

    @Test
    fun onKeyDown_dpadKeys_switchStateWhenZoomChanges() {
        val dialog = MediaViewerDialog(context, samplePhotoMessage, {}, {})
        dialog.show()

        // Unzoomed initially
        val rightEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFalse(dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, rightEvent))

        // Zoom in
        dialog.cycleZoomIn()
        assertTrue(dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, rightEvent))

        // Reset zoom back to 1.0f
        dialog.resetZoomAndPan()
        assertEquals(1.0f, dialog.zoomLevel)
        assertFalse(dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, rightEvent))
    }
}
