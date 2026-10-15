package com.teleflip.app.media

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import java.io.File

class VoicePlayer(private val context: Context) {
    private val tag = "VoicePlayer"
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingPath: String? = null

    fun play(path: String, onCompletion: () -> Unit): Boolean {
        try {
            stop()
            val file = File(path)
            mediaPlayer = if (file.exists()) {
                MediaPlayer.create(context, Uri.fromFile(file))
            } else {
                MediaPlayer().apply {
                    setDataSource(path)
                    prepare()
                }
            }

            mediaPlayer?.setOnCompletionListener {
                currentPlayingPath = null
                onCompletion()
            }
            mediaPlayer?.start()
            currentPlayingPath = path
            return true
        } catch (e: Exception) {
            Log.e(tag, "Failed to play voice note: ${e.message}")
            stop()
            return false
        }
    }

    fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            // ignore
        } finally {
            mediaPlayer = null
            currentPlayingPath = null
        }
    }

    fun isPlayingPath(path: String): Boolean {
        return mediaPlayer?.isPlaying == true && currentPlayingPath == path
    }
}
