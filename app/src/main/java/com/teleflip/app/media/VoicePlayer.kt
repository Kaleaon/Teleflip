package com.teleflip.app.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

enum class PlayerState { IDLE, PLAYING, PAUSED, COMPLETED, ERROR }

class VoicePlayer(private val context: Context) {
    private val tag = "VoicePlayer"
    internal var mediaPlayer: MediaPlayer? = null
    private var currentPlayingPath: String? = null

    var currentState: PlayerState = PlayerState.IDLE
        private set

    private var progressRunnable: Runnable? = null
    private var currentOnStateChanged: ((PlayerState) -> Unit)? = null

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

    internal var sdkVersion: Int = Build.VERSION.SDK_INT

    internal fun applyPlaybackSpeed() {
        val player = mediaPlayer ?: return
        if (sdkVersion >= Build.VERSION_CODES.M) {
            try {
                val params = player.playbackParams
                player.playbackParams = params.setSpeed(currentSpeed)
            } catch (e: Exception) {
                Log.e(tag, "Failed to set playback params speed: ${e.message}")
                currentSpeed = 1.0f
            }
        }
    }

    private fun getHandler(): Handler? {
        return try {
            val looper = Looper.getMainLooper()
            if (looper != null) Handler(looper) else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun startProgressTimer(onProgress: ((currentMs: Int, totalMs: Int) -> Unit)?) {
        stopProgressTimer()
        if (onProgress == null) return
        val h = getHandler() ?: return
        val runnable =
            object : Runnable {
                override fun run() {
                    val player = mediaPlayer
                    if (player != null && player.isPlaying) {
                        try {
                            val current = player.currentPosition
                            val total = player.duration
                            onProgress.invoke(current, total)
                        } catch (e: Exception) {
                            Log.e(tag, "Error reading progress: ${e.message}")
                        }
                        getHandler()?.postDelayed(this, 500L)
                    }
                }
            }
        progressRunnable = runnable
        h.post(runnable)
    }

    private fun stopProgressTimer() {
        val runnable = progressRunnable
        if (runnable != null) {
            getHandler()?.removeCallbacks(runnable)
            progressRunnable = null
        }
    }

    fun play(
        path: String,
        onProgress: ((currentMs: Int, totalMs: Int) -> Unit)? = null,
        onStateChanged: ((PlayerState) -> Unit)? = null,
        onError: (() -> Unit)? = null,
        onCompletion: () -> Unit,
    ): Boolean {
        try {
            stop()
            currentOnStateChanged = onStateChanged
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
                            currentState = PlayerState.PLAYING
                            onStateChanged?.invoke(PlayerState.PLAYING)
                            startProgressTimer(onProgress)
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to start playback after prepare: ${e.message}")
                            currentState = PlayerState.ERROR
                            onStateChanged?.invoke(PlayerState.ERROR)
                            stop()
                            onError?.invoke()
                        }
                    }
                    setOnCompletionListener {
                        stopProgressTimer()
                        currentPlayingPath = null
                        currentState = PlayerState.COMPLETED
                        onStateChanged?.invoke(PlayerState.COMPLETED)
                        onCompletion()
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e(tag, "MediaPlayer error: what=$what, extra=$extra")
                        stopProgressTimer()
                        currentState = PlayerState.ERROR
                        onStateChanged?.invoke(PlayerState.ERROR)
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
            stopProgressTimer()
            currentState = PlayerState.ERROR
            onStateChanged?.invoke(PlayerState.ERROR)
            stop()
            onError?.invoke()
            return false
        }
    }

    fun pause() {
        val player = mediaPlayer
        if (player != null && player.isPlaying) {
            try {
                player.pause()
                stopProgressTimer()
                currentState = PlayerState.PAUSED
                currentOnStateChanged?.invoke(PlayerState.PAUSED)
            } catch (e: Exception) {
                Log.e(tag, "Error pausing mediaPlayer: ${e.message}")
            }
        }
    }

    fun stop() {
        stopProgressTimer()
        val player = mediaPlayer
        mediaPlayer = null
        currentPlayingPath = null

        if (currentState != PlayerState.IDLE) {
            currentState = PlayerState.IDLE
            currentOnStateChanged?.invoke(PlayerState.IDLE)
        }
        currentOnStateChanged = null

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
