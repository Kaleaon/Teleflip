package com.teleflip.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teleflip.app.R
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MediaViewerDialogTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var samplePhotoFile: File
    private lateinit var samplePhotoMessage: MessageModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.Theme_TeleFlip)

        samplePhotoFile = tempFolder.newFile("sample_photo.jpg")
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        FileOutputStream(samplePhotoFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }

        samplePhotoMessage =
            MessageModel(
                id = 1L,
                chatId = 100L,
                senderName = "Test",
                text = "Photo caption",
                time = "12:00",
                isOutbound = false,
                mediaType = MediaType.PHOTO,
                mediaFilePath = samplePhotoFile.absolutePath,
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

        dialog.dismiss()
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

        dialog.dismiss()
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

        dialog.dismiss()
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

        dialog.dismiss()
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

    @Test
    fun setupPhoto_withValidFile_loadsBitmapAsynchronously() {
        val message =
            MessageModel(
                id = 1,
                chatId = 100,
                senderName = "Test Sender",
                text = "Photo Test",
                time = "10:00 AM",
                isOutbound = false,
                mediaType = MediaType.PHOTO,
                mediaFilePath = samplePhotoFile.absolutePath,
                mediaFileId = 1234,
            )

        val dialog =
            MediaViewerDialog(
                context = context,
                message = message,
                onSaveMedia = {},
                onRequestDownload = {},
            )

        dialog.show()

        val ivPhoto = dialog.findViewById<ImageView>(R.id.iv_viewer_photo)
        val tvStatus = dialog.findViewById<TextView>(R.id.tv_viewer_status)

        // Execute background coroutine tasks and main looper tasks
        val startTime = System.currentTimeMillis()
        while (ivPhoto.visibility != View.VISIBLE && System.currentTimeMillis() - startTime < 3000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }

        assertEquals(View.VISIBLE, ivPhoto.visibility)
        assertEquals(View.GONE, tvStatus.visibility)
        assertNotNull(ivPhoto.drawable)

        dialog.dismiss()
    }

    @Test
    fun setupPhoto_withMissingFile_showsDownloadingStatus() {
        val missingPath = File(tempFolder.root, "missing_photo.jpg").absolutePath
        val message =
            MessageModel(
                id = 2,
                chatId = 100,
                senderName = "Test Sender",
                text = "Missing Photo",
                time = "10:00 AM",
                isOutbound = false,
                mediaType = MediaType.PHOTO,
                mediaFilePath = missingPath,
                mediaFileId = 5678,
            )

        var downloadFileId = 0
        val dialog =
            MediaViewerDialog(
                context = context,
                message = message,
                onSaveMedia = {},
                onRequestDownload = { downloadFileId = it },
            )

        dialog.show()

        val ivPhoto = dialog.findViewById<ImageView>(R.id.iv_viewer_photo)
        val tvStatus = dialog.findViewById<TextView>(R.id.tv_viewer_status)

        assertEquals(View.GONE, ivPhoto.visibility)
        assertEquals(View.VISIBLE, tvStatus.visibility)
        assertEquals(5678, downloadFileId)

        dialog.dismiss()
    }

    @Test
    fun dialogStop_cancelsBackgroundDecodingJobsSafely() {
        val message =
            MessageModel(
                id = 3,
                chatId = 100,
                senderName = "Test Sender",
                text = "Photo Cancel Test",
                time = "10:00 AM",
                isOutbound = false,
                mediaType = MediaType.PHOTO,
                mediaFilePath = samplePhotoFile.absolutePath,
                mediaFileId = 999,
            )

        val dialog =
            MediaViewerDialog(
                context = context,
                message = message,
                onSaveMedia = {},
                onRequestDownload = {},
            )

        dialog.show()
        // Immediately dismiss dialog before background coroutines finish
        dialog.dismiss()

        shadowOf(android.os.Looper.getMainLooper()).idle()
        // Should close cleanly without crashing or updating dismissed views
    }

    @Test
    fun testProgressLayoutVisibility_videoAndAnimation_visibleWhenFileExists() {
        val sampleVideoFile = tempFolder.newFile("sample_video.mp4")
        FileOutputStream(sampleVideoFile).use { it.write("fake video content".toByteArray()) }

        val videoMessage = MessageModel(
            id = 10,
            chatId = 100,
            senderName = "Test",
            text = "Video test",
            time = "10:00 AM",
            isOutbound = false,
            mediaType = MediaType.VIDEO,
            mediaFilePath = sampleVideoFile.absolutePath,
        )

        val dialog = MediaViewerDialog(context, videoMessage, {}, {})
        dialog.show()

        val layoutProgress = dialog.findViewById<View>(R.id.layout_viewer_progress)
        assertNotNull(layoutProgress)
        assertEquals(View.VISIBLE, layoutProgress.visibility)

        dialog.dismiss()
    }

    @Test
    fun testProgressLayoutVisibility_photoAndSticker_gone() {
        val photoMessage = MessageModel(
            id = 11,
            chatId = 100,
            senderName = "Test",
            text = "Photo test",
            time = "10:00 AM",
            isOutbound = false,
            mediaType = MediaType.PHOTO,
            mediaFilePath = samplePhotoFile.absolutePath,
        )

        val dialog = MediaViewerDialog(context, photoMessage, {}, {})
        dialog.show()

        val layoutProgress = dialog.findViewById<View>(R.id.layout_viewer_progress)
        assertNotNull(layoutProgress)
        assertEquals(View.GONE, layoutProgress.visibility)

        dialog.dismiss()
    }

    @Test
    fun testFormatTimeMs_formatsMinutesAndSecondsAccurately() {
        val message = createTestMessage(MediaType.VIDEO)
        val dialog = MediaViewerDialog(context, message, {}, {})

        assertEquals("00:00", dialog.formatTimeMs(0))
        assertEquals("00:05", dialog.formatTimeMs(5000))
        assertEquals("01:05", dialog.formatTimeMs(65000))
        assertEquals("10:00", dialog.formatTimeMs(600000))
        assertEquals("00:00", dialog.formatTimeMs(-1000))
    }

    @Test
    fun testUpdateProgressUI_withValidDuration_updatesProgressBarAndText() {
        val message = createTestMessage(MediaType.VIDEO)
        val dialog = MediaViewerDialog(context, message, {}, {})
        dialog.show()

        val pb = dialog.findViewById<ProgressBar>(R.id.pb_viewer_progress)
        val tv = dialog.findViewById<TextView>(R.id.tv_viewer_time)

        dialog.updateProgressUI(currentMs = 15000, totalMs = 60000)

        assertEquals(60000, pb.max)
        assertEquals(15000, pb.progress)
        assertEquals("00:15 / 01:00", tv.text.toString())

        dialog.dismiss()
    }

    @Test
    fun testUpdateProgressUI_withZeroOrNegativeDuration_showsFallback() {
        val message = createTestMessage(MediaType.VIDEO)
        val dialog = MediaViewerDialog(context, message, {}, {})
        dialog.show()

        val pb = dialog.findViewById<ProgressBar>(R.id.pb_viewer_progress)
        val tv = dialog.findViewById<TextView>(R.id.tv_viewer_time)

        dialog.updateProgressUI(currentMs = 0, totalMs = 0)

        assertEquals(100, pb.max)
        assertEquals(0, pb.progress)
        assertEquals("0:00 / 0:00", tv.text.toString())

        dialog.updateProgressUI(currentMs = 5000, totalMs = -1)

        assertEquals(100, pb.max)
        assertEquals(0, pb.progress)
        assertEquals("0:00 / 0:00", tv.text.toString())

        dialog.dismiss()
    }
}
