package com.teleflip.app.ui

import android.app.Application
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.view.KeyEvent
import android.widget.TextView
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
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

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

    private fun createTestMessage(mediaType: MediaType): MessageModel {
        return MessageModel(
            id = 1,
            chatId = 100L,
            senderName = "Test User",
            text = "Test media caption",
            time = "12:00 PM",
            isOutbound = false,
            mediaType = mediaType,
            mediaFilePath = null,
        )
    }

    @Test
    fun panBy_returnsFalseWhenUnzoomed_andTrueWhenZoomedIn() {
        val dialog = MediaViewerDialog(context, samplePhotoMessage, {}, {})
        dialog.show()

        assertEquals(1.0f, dialog.zoomLevel)
        assertFalse("panBy should return false when unzoomed", dialog.panBy(24f, 24f))
        assertEquals(0f, dialog.panX)
        assertEquals(0f, dialog.panY)

        dialog.cycleZoomIn()
        assertTrue(dialog.zoomLevel > 1.0f)
        assertTrue("panBy should return true when zoomed in", dialog.panBy(24f, 24f))
        assertEquals(24f, dialog.panX)
        assertEquals(24f, dialog.panY)
    }

    @Test
    fun onKeyDown_dpadKeys_notConsumedWhenUnzoomed() {
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

    @Test
    fun testPlaybackSpeedCyclingSequence() {
        val message = createTestMessage(MediaType.VIDEO)
        val dialog = MediaViewerDialog(context, message, {}, {})

        assertEquals(1.0f, dialog.playbackSpeed, 0.001f)

        // 1.0x -> 1.25x
        val s1 = dialog.cyclePlaybackSpeed()
        assertEquals(1.25f, s1, 0.001f)
        assertEquals(1.25f, dialog.playbackSpeed, 0.001f)

        // 1.25x -> 1.5x
        val s2 = dialog.cyclePlaybackSpeed()
        assertEquals(1.5f, s2, 0.001f)
        assertEquals(1.5f, dialog.playbackSpeed, 0.001f)

        // 1.5x -> 2.0x
        val s3 = dialog.cyclePlaybackSpeed()
        assertEquals(2.0f, s3, 0.001f)
        assertEquals(2.0f, dialog.playbackSpeed, 0.001f)

        // 2.0x -> 1.0x
        val s4 = dialog.cyclePlaybackSpeed()
        assertEquals(1.0f, s4, 0.001f)
        assertEquals(1.0f, dialog.playbackSpeed, 0.001f)
    }

    @Test
    fun testSpeedBadgeLabelFormatting() {
        val message = createTestMessage(MediaType.VIDEO)
        val dialog = MediaViewerDialog(context, message, {}, {})

        assertEquals("1.0x", dialog.formatSpeedLabel(1.0f))
        assertEquals("1.25x", dialog.formatSpeedLabel(1.25f))
        assertEquals("1.5x", dialog.formatSpeedLabel(1.5f))
        assertEquals("2.0x", dialog.formatSpeedLabel(2.0f))
    }

    @Test
    fun testSpeedBadgeOverlayUpdatesOnCycle() {
        val message = createTestMessage(MediaType.VIDEO)
        val dialog = MediaViewerDialog(context, message, {}, {})
        dialog.show()

        val badgeTextView = dialog.findViewById<TextView>(R.id.tv_viewer_zoom_badge)
        assertEquals("1.0x", badgeTextView.text.toString())

        dialog.cyclePlaybackSpeed()
        assertEquals("1.25x", badgeTextView.text.toString())

        dialog.cyclePlaybackSpeed()
        assertEquals("1.5x", badgeTextView.text.toString())

        dialog.cyclePlaybackSpeed()
        assertEquals("2.0x", badgeTextView.text.toString())

        dialog.cyclePlaybackSpeed()
        assertEquals("1.0x", badgeTextView.text.toString())

        dialog.dismiss()
    }

    @Test
    fun testKeycode3CyclesSpeedInVideoMode() {
        val message = createTestMessage(MediaType.VIDEO)
        val dialog = MediaViewerDialog(context, message, {}, {})
        dialog.show()

        val key3Event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_3)
        val handled = dialog.dispatchKeyEvent(key3Event)

        assertTrue(handled)
        assertEquals(1.25f, dialog.playbackSpeed, 0.001f)

        val badgeTextView = dialog.findViewById<TextView>(R.id.tv_viewer_zoom_badge)
        assertEquals("1.25x", badgeTextView.text.toString())

        dialog.dismiss()
    }

    @Test
    fun testKeycode3DoesNotCycleSpeedInPhotoMode() {
        val message = createTestMessage(MediaType.PHOTO)
        val dialog = MediaViewerDialog(context, message, {}, {})
        dialog.show()

        val key3Event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_3)
        dialog.dispatchKeyEvent(key3Event)

        assertEquals(1.0f, dialog.playbackSpeed, 0.001f)
        dialog.dismiss()
    }

    @Test
    fun testApplyPlaybackSpeedWithMockMediaPlayer() {
        val message = createTestMessage(MediaType.VIDEO)
        val dialog = MediaViewerDialog(context, message, {}, {})

        val mockMediaPlayer = mock(MediaPlayer::class.java)
        val mockParams = mock(PlaybackParams::class.java)
        `when`(mockMediaPlayer.playbackParams).thenReturn(mockParams)
        `when`(mockParams.setSpeed(anyFloat())).thenReturn(mockParams)

        dialog.mediaPlayer = mockMediaPlayer
        dialog.sdkVersion = android.os.Build.VERSION_CODES.M

        dialog.cyclePlaybackSpeed()
        verify(mockParams).setSpeed(1.25f)
        verify(mockMediaPlayer).playbackParams = mockParams
    }

    @Test
    fun testApplyPlaybackSpeedHandlesExceptionGracefully() {
        val message = createTestMessage(MediaType.VIDEO)
        val dialog = MediaViewerDialog(context, message, {}, {})

        val mockMediaPlayer = mock(MediaPlayer::class.java)
        `when`(mockMediaPlayer.playbackParams).thenThrow(IllegalStateException("Driver error"))

        dialog.mediaPlayer = mockMediaPlayer
        dialog.sdkVersion = android.os.Build.VERSION_CODES.M

        // Should not throw
        dialog.cyclePlaybackSpeed()
        assertEquals(1.25f, dialog.playbackSpeed, 0.001f)
    }
}
