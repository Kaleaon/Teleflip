package com.teleflip.app.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import java.io.File

class VoicePlayer(private val context: Context) {
    private val tag = "VoicePlayer"
    internal var mediaPlayer: MediaPlayer? = null
    private var currentPlayingPath: String? = null

    var currentSpeed: Float = 1.0f
        private set

    fun cyclePlaybackSpeed(): Float {
        currentSpeed =
            when (currentSpeed) {
                1.0f -> 1.5f
                1.5f -> 2.0f
                else -> 1.0f
            }
        applyPlaybackSpeed()
        return currentSpeed
    }

    fun setPlaybackSpeed(speed: Float) {
        currentSpeed = speed
        applyPlaybackSpeed()
    }

    internal fun applyPlaybackSpeed() {
        val player = mediaPlayer ?: return
        try {
            val params = player.playbackParams
            player.playbackParams = params.setSpeed(currentSpeed)
        } catch (e: Exception) {
            Log.e(tag, "Failed to set playback params speed: ${e.message}")
            currentSpeed = 1.0f
        }
    }

    fun play(
        path: String,
        onError: (() -> Unit)? = null,
        onCompletion: () -> Unit,
    ): Boolean {
        try {
            stop()
            val file = File(path)
            val player =
                MediaPlayer().apply {
                    if (file.exists()) {
                        setDataSource(context, Uri.fromFile(file))
                    } else {
                        setDataSource(path)
                    }
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build(),
                    )
                    setOnPreparedListener { mp ->
                        try {
                            mp.start()
                            applyPlaybackSpeed()
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to start playback after prepare: ${e.message}")
                            stop()
                            onError?.invoke()
                        }
                    }
                    setOnCompletionListener {
                        currentPlayingPath = null
                        onCompletion()
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e(tag, "MediaPlayer error: what=$what, extra=$extra")
                        stop()
                        onError?.invoke()
                        true
                    }
                    prepareAsync()
                }

            mediaPlayer = player
            currentPlayingPath = path
            return true
        } catch (e: Exception) {
            Log.e(tag, "Failed to initiate voice note playback: ${e.message}")
            stop()
            onError?.invoke()
            return false
        }
    }

    fun stop() {
        val player = mediaPlayer
        mediaPlayer = null
        currentPlayingPath = null

        if (player != null) {
            try {
                player.setOnPreparedListener(null)
                player.setOnCompletionListener(null)
                player.setOnErrorListener(null)
                player.stop()
            } catch (e: Exception) {
                Log.e(tag, "Error stopping mediaPlayer: ${e.message}")
            } finally {
                try {
                    player.release()
                } catch (e: Exception) {
                    Log.e(tag, "Error releasing mediaPlayer: ${e.message}")
                }
            }
        }
    }

    fun isPlayingPath(path: String): Boolean {
        return currentPlayingPath == path && mediaPlayer != null
    }
}
