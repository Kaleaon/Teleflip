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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.Theme_TeleFlip)
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
