package com.teleflip.app.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

data class VoiceRecording(
    val file: File,
    val durationSeconds: Int
)

class VoiceRecorder(private val context: Context) {
    private val tag = "VoiceRecorder"
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startTimeMillis: Long = 0
    private var isRecording = false

    private val handler = Handler(Looper.getMainLooper())
    private var onTickListener: ((Int) -> Unit)? = null

    private val tickerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsed = ((System.currentTimeMillis() - startTimeMillis) / 1000).toInt()
                onTickListener?.invoke(elapsed)
                handler.postDelayed(this, 500)
            }
        }
    }

    fun startRecording(onTick: (Int) -> Unit): Boolean {
        if (isRecording) return false

        try {
            val audioDir = File(context.cacheDir, "voice_recordings")
            if (!audioDir.exists()) audioDir.mkdirs()

            val file = File(audioDir, "voice_${System.currentTimeMillis()}.m4a")
            currentFile = file

            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(32000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            startTimeMillis = System.currentTimeMillis()
            onTickListener = onTick
            handler.post(tickerRunnable)
            return true
        } catch (e: Exception) {
            Log.e(tag, "Failed to start recording: ${e.message}")
            cancelRecording()
            return false
        }
    }

    fun stopRecording(): VoiceRecording? {
        if (!isRecording) return null

        val durationSec = Math.max(1, ((System.currentTimeMillis() - startTimeMillis) / 1000).toInt())
        isRecording = false
        handler.removeCallbacks(tickerRunnable)

        try {
            recorder?.stop()
            recorder?.release()
        } catch (e: Exception) {
            Log.e(tag, "Error stopping recorder: ${e.message}")
        } finally {
            recorder = null
        }

        val file = currentFile
        return if (file != null && file.exists() && file.length() > 0) {
            VoiceRecording(file, durationSec)
        } else {
            null
        }
    }

    fun cancelRecording() {
        isRecording = false
        handler.removeCallbacks(tickerRunnable)
        try {
            recorder?.stop()
            recorder?.release()
        } catch (e: Exception) {
            // ignore on cancel
        } finally {
            recorder = null
        }
        currentFile?.delete()
        currentFile = null
    }

    fun isRecording(): Boolean = isRecording
}
