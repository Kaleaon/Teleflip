package com.teleflip.app.media

import android.content.Context
import android.media.MediaPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
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

    private fun dummyContext(): Context {
        return mock(Context::class.java)
    }
}
