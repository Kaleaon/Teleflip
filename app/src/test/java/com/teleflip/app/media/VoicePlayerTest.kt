package com.teleflip.app.media

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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
        player.sdkVersion = Build.VERSION_CODES.M
        val mockMediaPlayer = mock(MediaPlayer::class.java)

        // Simulate a failure when accessing/setting playbackParams
        `when`(mockMediaPlayer.playbackParams).thenThrow(IllegalStateException("Audio driver playbackParams unsupported"))

        player.mediaPlayer = mockMediaPlayer
        player.setPlaybackSpeed(1.5f)

        // Verify fallback to 1.0f without throwing exception
        assertEquals(1.0f, player.currentSpeed, 0.001f)
    }

    @Test
    fun testVoicePlayerRequestsAudioFocusApi26Plus() {
        val player = VoicePlayer(dummyContext())
        player.sdkVersion = Build.VERSION_CODES.O
        val mockAudioManager = mock(AudioManager::class.java)
        player.audioManager = mockAudioManager
        `when`(mockAudioManager.requestAudioFocus(any(AudioFocusRequest::class.java)))
            .thenReturn(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        val granted = player.requestAudioFocus()
        assertTrue(granted)
        verify(mockAudioManager).requestAudioFocus(any(AudioFocusRequest::class.java))
    }

    @Test
    fun testVoicePlayerRequestsAudioFocusLegacyApi() {
        val player = VoicePlayer(dummyContext())
        player.sdkVersion = Build.VERSION_CODES.M
        val mockAudioManager = mock(AudioManager::class.java)
        player.audioManager = mockAudioManager
        `when`(
            mockAudioManager.requestAudioFocus(
                eq(player.audioFocusChangeListener),
                eq(AudioManager.STREAM_MUSIC),
                eq(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT),
            ),
        ).thenReturn(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        val granted = player.requestAudioFocus()
        assertTrue(granted)
        verify(mockAudioManager).requestAudioFocus(
            eq(player.audioFocusChangeListener),
            eq(AudioManager.STREAM_MUSIC),
            eq(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT),
        )
    }

    @Test
    fun testVoicePlayerHandlesAudioFocusDenied() {
        val player = VoicePlayer(dummyContext())
        player.sdkVersion = Build.VERSION_CODES.O
        val mockAudioManager = mock(AudioManager::class.java)
        player.audioManager = mockAudioManager
        `when`(mockAudioManager.requestAudioFocus(any(AudioFocusRequest::class.java)))
            .thenReturn(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        val granted = player.requestAudioFocus()
        assertFalse(granted)
    }

    @Test
    fun testVoicePlayerAbandonsAudioFocusApi26Plus() {
        val player = VoicePlayer(dummyContext())
        player.sdkVersion = Build.VERSION_CODES.O
        val mockAudioManager = mock(AudioManager::class.java)
        player.audioManager = mockAudioManager
        `when`(mockAudioManager.requestAudioFocus(any(AudioFocusRequest::class.java)))
            .thenReturn(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        player.requestAudioFocus()
        val focusReq = player.audioFocusRequest
        player.abandonAudioFocus()

        verify(mockAudioManager).abandonAudioFocusRequest(focusReq!!)
        assertNull(player.audioFocusRequest)
    }

    @Test
    fun testVoicePlayerAbandonsAudioFocusLegacyApi() {
        val player = VoicePlayer(dummyContext())
        player.sdkVersion = Build.VERSION_CODES.M
        val mockAudioManager = mock(AudioManager::class.java)
        player.audioManager = mockAudioManager

        player.abandonAudioFocus()

        verify(mockAudioManager).abandonAudioFocus(player.audioFocusChangeListener)
    }

    @Test
    fun testVoicePlayerHandlesFocusLossCallbacks() {
        val player = VoicePlayer(dummyContext())
        val mockMediaPlayer = mock(MediaPlayer::class.java)
        `when`(mockMediaPlayer.isPlaying).thenReturn(true)
        player.mediaPlayer = mockMediaPlayer

        // 1. Transient focus loss -> PAUSED
        player.audioFocusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        verify(mockMediaPlayer).pause()
        assertEquals(PlayerState.PAUSED, player.currentState)

        // 2. Permanent focus loss -> IDLE
        player.mediaPlayer = mockMediaPlayer
        player.audioFocusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        verify(mockMediaPlayer).stop()
        assertEquals(PlayerState.IDLE, player.currentState)
    }

    private fun dummyContext(): Context {
        return mock(Context::class.java)
    }
}
