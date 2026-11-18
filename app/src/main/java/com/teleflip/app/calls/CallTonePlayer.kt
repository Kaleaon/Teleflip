package com.teleflip.app.calls

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.content.ContextCompat

interface CallTonePlayer {
    fun startDialTone()

    fun startRingtone()

    fun playBusyTone()

    fun stop()
}

class AndroidCallTonePlayer(
    private val context: Context? = null,
) : CallTonePlayer {
    private var toneGenerator: ToneGenerator? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

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
    override fun startRingtone() {
        if (isPlaying) return
        try {
            context?.let { ctx ->
                val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val rt = RingtoneManager.getRingtone(ctx, ringtoneUri)
                if (rt != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        rt.isLooping = true
                    }
                    rt.play()
                    ringtone = rt
                }
                val vib = ContextCompat.getSystemService(ctx, Vibrator::class.java)
                if (vib != null && vib.hasVibrator()) {
                    vibrator = vib
                    val pattern = longArrayOf(0, 1000, 1000)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
                    } else {
                        @Suppress("DEPRECATION")
                        vib.vibrate(pattern, 0)
                    }
                }
            }
            isPlaying = true
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to start ringtone: ${error.message}")
            stop()
        }
    }

    @Synchronized
    override fun playBusyTone() {
        stop()
        try {
            val generator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, TONE_VOLUME)
            toneGenerator = generator
            generator.startTone(ToneGenerator.TONE_SUP_BUSY, BUSY_DURATION_MS)
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to play busy tone: ${error.message}")
            stop()
        }
    }

    @Synchronized
    override fun stop() {
        isPlaying = false
        try {
            ringtone?.stop()
        } catch (error: Throwable) {
            Log.w(TAG, "Error stopping ringtone: ${error.message}")
        }
        ringtone = null

        try {
            vibrator?.cancel()
        } catch (error: Throwable) {
            Log.w(TAG, "Error stopping vibrator: ${error.message}")
        }
        vibrator = null

        try {
            toneGenerator?.stopTone()
        } catch (error: Throwable) {
            Log.w(TAG, "Error stopping tone: ${error.message}")
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
        private const val BUSY_DURATION_MS = 1500
    }
}
