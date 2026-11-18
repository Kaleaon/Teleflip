package com.teleflip.app.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

interface CallAudioSession {
    fun start(onFocusLost: () -> Unit)

    fun speaker(enabled: Boolean)

    fun stop()
}

class AndroidCallAudioSession(private val context: Context) : CallAudioSession {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousMute = false
    private var previousSpeaker = false

    override fun start(onFocusLost: () -> Unit) {
        if (focus != null) return
        val request =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onFocusLost()
                }.build()
        check(audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio is in use by another call" }
        focus = request
        previousMode = audio.mode
        previousMute = audio.isMicrophoneMute
        @Suppress("DEPRECATION")
        previousSpeaker = audio.isSpeakerphoneOn
        try {
            CallAudioService.start(context)
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            audio.isMicrophoneMute = false
        } catch (error: Exception) {
            stop()
            throw error
        }
    }

    override fun speaker(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            if (enabled) {
                val device =
                    audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?: error("Speaker is unavailable")
                check(audio.setCommunicationDevice(device)) { "Unable to select speaker" }
            } else {
                audio.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = enabled
        }
    }

    override fun stop() {
        val request = focus ?: return
        focus = null
        try {
            if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = previousSpeaker
            audio.isMicrophoneMute = previousMute
            audio.mode = previousMode
        } finally {
            audio.abandonAudioFocusRequest(request)
            CallAudioService.stop(context)
        }
    }
}
