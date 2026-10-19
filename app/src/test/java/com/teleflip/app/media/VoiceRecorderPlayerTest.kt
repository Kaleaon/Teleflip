package com.teleflip.app.media

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class VoiceRecorderPlayerTest {
    @Test
    fun testVoicePlayerInitialStateAndSafeStop() {
        val player = VoicePlayer(dummyContext())
        assertFalse("VoicePlayer should not be playing initially", player.isPlayingPath("/fake/path.m4a"))

        // Ensure safe stop on non-playing instance does not throw exceptions
        player.stop()
        assertFalse("VoicePlayer should remain idle after stop", player.isPlayingPath("/fake/path.m4a"))
    }

    @Test
    fun testVoicePlayerStopHaltsActiveSessionAndResetsState() {
        val player = VoicePlayer(dummyContext())
        val mockMediaPlayer = mock(MediaPlayer::class.java)
        player.mediaPlayer = mockMediaPlayer

        player.stop()

        assertNull("VoicePlayer mediaPlayer should be null after stop", player.mediaPlayer)
        assertFalse("VoicePlayer should not be playing any path after stop", player.isPlayingPath("/fake/path.m4a"))
        verify(mockMediaPlayer).stop()
        verify(mockMediaPlayer).release()
    }

    @Test
    fun testVoicePlayerSpeedCyclingSequence() {
        val player = VoicePlayer(dummyContext())
        assertEquals(1.0f, player.currentSpeed, 0.001f)

        // 1.0x -> 1.5x
        val speed1 = player.cyclePlaybackSpeed()
        assertEquals(1.5f, speed1, 0.001f)
        assertEquals(1.5f, player.currentSpeed, 0.001f)

        // 1.5x -> 2.0x
        val speed2 = player.cyclePlaybackSpeed()
        assertEquals(2.0f, speed2, 0.001f)
        assertEquals(2.0f, player.currentSpeed, 0.001f)

        // 2.0x -> 1.0x
        val speed3 = player.cyclePlaybackSpeed()
        assertEquals(1.0f, speed3, 0.001f)
        assertEquals(1.0f, player.currentSpeed, 0.001f)
    }

    @Test
    fun testVoicePlayerPlaybackParamsFallbackOnFailure() {
        val player = VoicePlayer(dummyContext())
        player.sdkVersion = android.os.Build.VERSION_CODES.M
        val mockMediaPlayer = mock(MediaPlayer::class.java)

        `when`(mockMediaPlayer.playbackParams).thenThrow(IllegalStateException("Audio driver error"))

        player.mediaPlayer = mockMediaPlayer
        player.setPlaybackSpeed(1.5f)

        assertEquals(1.0f, player.currentSpeed, 0.001f)
    }

    @Test
    fun testVoiceRecorderInitialStateAndSafeStop() {
        val recorder = VoiceRecorder(dummyContext())
        assertFalse("VoiceRecorder should not be recording initially", recorder.isRecording())

        // Ensure stopRecording returns null when not active
        val recording = recorder.stopRecording()
        assertNull("stopRecording should return null when not recording", recording)

        // Ensure cancelRecording on idle recorder executes without errors
        recorder.cancelRecording()
        assertFalse("VoiceRecorder should remain idle after cancel", recorder.isRecording())
    }

    @Test
    fun testVoiceRecorderCancelRecordingHaltsActiveSessionAndResetsState() {
        val recorder = VoiceRecorder(dummyContext())
        val mockMediaRecorder = mock(MediaRecorder::class.java)
        val tempFile = File.createTempFile("test_voice_recording", ".m4a")

        recorder.recorder = mockMediaRecorder
        recorder.currentFile = tempFile
        recorder.isRecording = true

        assertTrue("VoiceRecorder should be recording initially", recorder.isRecording())
        assertTrue("Temp recording file should exist initially", tempFile.exists())

        recorder.cancelRecording()

        assertFalse("VoiceRecorder should not be recording after cancelRecording", recorder.isRecording())
        assertNull("VoiceRecorder recorder should be null after cancelRecording", recorder.recorder)
        assertNull("VoiceRecorder currentFile should be null after cancelRecording", recorder.currentFile)
        assertFalse("Temp recording file should be deleted on cancelRecording", tempFile.exists())

        verify(mockMediaRecorder).stop()
        verify(mockMediaRecorder).release()
    }

    private fun dummyContext(): Context {
        return mock(Context::class.java)
    }
}
