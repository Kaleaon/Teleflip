package com.teleflip.app.calls

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

interface CallTonePlayer {
    fun startDialTone()

    fun stop()
}

class AndroidCallTonePlayer : CallTonePlayer {
    private var toneGenerator: ToneGenerator? = null

    @Volatile
    private var isPlaying = false

    @Synchronized
    override fun startDialTone() {
        if (isPlaying) return
        try {
            val generator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, TONE_VOLUME)
            toneGenerator = generator
            generator.startTone(ToneGenerator.TONE_SUP_RINGTONE)
            isPlaying = true
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to start dialing tone: ${error.message}")
            stop()
        }
    }

    @Synchronized
    override fun stop() {
        isPlaying = false
        try {
            toneGenerator?.stopTone()
        } catch (error: Throwable) {
            Log.w(TAG, "Error stopping dialing tone: ${error.message}")
        } finally {
            try {
                toneGenerator?.release()
            } catch (error: Throwable) {
                Log.w(TAG, "Error releasing ToneGenerator: ${error.message}")
            }
            toneGenerator = null
        }
    }

    companion object {
        private const val TAG = "CallTonePlayer"
        private const val TONE_VOLUME = 80
    }
}
