package com.teleflip.app

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class MainActivityAudioTest {
    private lateinit var activity: MainActivity

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(MainActivity::class.java).get()
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    @Suppress("DEPRECATION")
    fun testLegacySpeakerphoneEnableAndDisable() {
        val mockAudioManager = mock(AudioManager::class.java)

        activity.setSpeakerphoneEnabled(mockAudioManager, true)
        verify(mockAudioManager).isSpeakerphoneOn = true

        activity.setSpeakerphoneEnabled(mockAudioManager, false)
        verify(mockAudioManager).isSpeakerphoneOn = false
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    @Suppress("DEPRECATION")
    fun testLegacyIsSpeakerphoneEnabled() {
        val mockAudioManager = mock(AudioManager::class.java)

        `when`(mockAudioManager.isSpeakerphoneOn).thenReturn(true)
        assertTrue(activity.isSpeakerphoneEnabled(mockAudioManager))

        `when`(mockAudioManager.isSpeakerphoneOn).thenReturn(false)
        assertFalse(activity.isSpeakerphoneEnabled(mockAudioManager))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    fun testAndroid12SpeakerphoneEnableWithSpeakerDevice() {
        val mockAudioManager = mock(AudioManager::class.java)
        val speakerDevice = mock(AudioDeviceInfo::class.java)
        `when`(speakerDevice.type).thenReturn(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)

        val earpieceDevice = mock(AudioDeviceInfo::class.java)
        `when`(earpieceDevice.type).thenReturn(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)

        `when`(mockAudioManager.availableCommunicationDevices).thenReturn(listOf(earpieceDevice, speakerDevice))

        activity.setSpeakerphoneEnabled(mockAudioManager, true)
        verify(mockAudioManager).setCommunicationDevice(speakerDevice)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    fun testAndroid12SpeakerphoneEnableWhenNoSpeakerDeviceAvailable() {
        val mockAudioManager = mock(AudioManager::class.java)
        val earpieceDevice = mock(AudioDeviceInfo::class.java)
        `when`(earpieceDevice.type).thenReturn(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)

        `when`(mockAudioManager.availableCommunicationDevices).thenReturn(listOf(earpieceDevice))

        activity.setSpeakerphoneEnabled(mockAudioManager, true)
        verify(mockAudioManager, never()).setCommunicationDevice(org.mockito.ArgumentMatchers.any())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    fun testAndroid12SpeakerphoneDisable() {
        val mockAudioManager = mock(AudioManager::class.java)

        activity.setSpeakerphoneEnabled(mockAudioManager, false)
        verify(mockAudioManager).clearCommunicationDevice()
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    fun testAndroid12IsSpeakerphoneEnabled() {
        val mockAudioManager = mock(AudioManager::class.java)
        val speakerDevice = mock(AudioDeviceInfo::class.java)
        `when`(speakerDevice.type).thenReturn(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)

        val earpieceDevice = mock(AudioDeviceInfo::class.java)
        `when`(earpieceDevice.type).thenReturn(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)

        `when`(mockAudioManager.communicationDevice).thenReturn(speakerDevice)
        assertTrue(activity.isSpeakerphoneEnabled(mockAudioManager))

        `when`(mockAudioManager.communicationDevice).thenReturn(earpieceDevice)
        assertFalse(activity.isSpeakerphoneEnabled(mockAudioManager))

        `when`(mockAudioManager.communicationDevice).thenReturn(null)
        assertFalse(activity.isSpeakerphoneEnabled(mockAudioManager))
    }
}
