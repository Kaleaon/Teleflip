package com.teleflip.app.media

import android.content.Context
import android.media.MediaPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class VoicePlayerTest {
    @Test
    fun testVoicePlayerInitialStateAndSafeStop() {
        val player = VoicePlayer(dummyContext())
        assertEquals(PlayerState.IDLE, player.currentState)
        assertFalse("VoicePlayer should not be playing initially", player.isPlayingPath("/fake/path.m4a"))

        player.stop()
        assertEquals(PlayerState.IDLE, player.currentState)
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
    fun testVoicePlayerSetPlaybackSpeed() {
        val player = VoicePlayer(dummyContext())
        player.setPlaybackSpeed(1.5f)
        assertEquals(1.5f, player.currentSpeed, 0.001f)

        player.setPlaybackSpeed(2.0f)
        assertEquals(2.0f, player.currentSpeed, 0.001f)

        player.setPlaybackSpeed(1.0f)
        assertEquals(1.0f, player.currentSpeed, 0.001f)
    }

    @Test
    fun testVoicePlayerPlaybackParamsFallbackOnFailure() {
        val player = VoicePlayer(dummyContext())
        val mockMediaPlayer = mock(MediaPlayer::class.java)

        // Simulate a failure when accessing/setting playbackParams
        `when`(mockMediaPlayer.playbackParams).thenThrow(IllegalStateException("Audio driver playbackParams unsupported"))

        player.mediaPlayer = mockMediaPlayer
        player.setPlaybackSpeed(1.5f)

        // Verify fallback to 1.0f without throwing exception
        assertEquals(1.0f, player.currentSpeed, 0.001f)
    }

    private fun dummyContext(): Context {
        return mock(Context::class.java)
    }
}
