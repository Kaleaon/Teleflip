package com.teleflip.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import coil.load
import com.teleflip.app.R
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import java.io.File
import java.util.Locale

class MediaViewerDialog(
    context: Context,
    private val message: MessageModel,
    private val onSaveMedia: (MessageModel) -> Unit,
    private val onRequestDownload: (Int) -> Unit,
) : BaseDialog(context) {
    private var ivPhoto: ImageView? = null
    private var vvVideo: VideoView? = null
    private var tvZoomBadge: TextView? = null
    private var tvStatus: TextView? = null
    private var tvCaption: TextView? = null
    private var tvHint: TextView? = null

    var zoomLevel: Float = 1.0f
        private set
    var panX: Float = 0f
        private set
    var panY: Float = 0f
        private set

    private val zoomSteps = listOf(1.0f, 1.5f, 2.0f, 3.0f)
    private val panStepPx = 48f
    private var isVideoPlaying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_media_viewer)
        setupDismissKeys()

        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

        val tvTitle = findViewById<TextView>(R.id.tv_viewer_title)
        tvZoomBadge = findViewById(R.id.tv_viewer_zoom_badge)
        ivPhoto = findViewById(R.id.iv_viewer_photo)
        vvVideo = findViewById(R.id.vv_viewer_video)
        tvStatus = findViewById(R.id.tv_viewer_status)
        tvCaption = findViewById(R.id.tv_viewer_caption)
        tvHint = findViewById(R.id.tv_viewer_controls_hint)

        val captionStr = message.mediaCaption.ifBlank { message.text }
        if (captionStr.isNotBlank()) {
            tvCaption?.visibility = View.VISIBLE
            tvCaption?.text = captionStr
        } else {
            tvCaption?.visibility = View.GONE
        }

        val filePath = message.mediaFilePath
        val fileExists = !filePath.isNullOrBlank() && File(filePath).exists()

        when (message.mediaType) {
            MediaType.VIDEO, MediaType.ANIMATION -> {
                val isGif = message.mediaType == MediaType.ANIMATION
                tvTitle?.text = if (isGif) "🎞️ GIF Viewer" else "📹 Video Player"
                tvHint?.text = "[OK] Play/Pause • [◄/►] ±5s • [LSK] Restart • [1] Save • [RSK] Back"
                tvZoomBadge?.text = if (isGif) "LOOP" else "00:00"
                setupVideoOrGif(filePath, fileExists, isGif)
            }
            MediaType.STICKER -> {
                val emoji = message.stickerEmoji.ifBlank { "Sticker" }
                tvTitle?.text = "🎨 $emoji Viewer"
                tvHint?.text = "[LSK/2] Zoom • [D-Pad] Pan • [5] Reset • [1] Save • [RSK] Back"
                setupPhoto(filePath, fileExists)
            }
            else -> {
                tvTitle?.text = "📷 Photo Viewer"
                tvHint?.text = "[LSK/2] Zoom • [D-Pad] Pan • [5] Reset • [1] Save • [RSK] Back"
                setupPhoto(filePath, fileExists)
            }
        }
    }

    private fun setupPhoto(
        filePath: String?,
        fileExists: Boolean,
    ) {
        if (!fileExists || filePath == null) {
            ivPhoto?.visibility = View.GONE
            tvStatus?.visibility = View.VISIBLE
            tvStatus?.text = "Downloading photo from Telegram...\nPress [1] to prioritize download."
            if (message.mediaFileId != 0) {
                onRequestDownload(message.mediaFileId)
            }
            return
        }

        val bmp = BitmapFactory.decodeFile(filePath)
        if (bmp != null) {
            ivPhoto?.setImageBitmap(bmp)
            ivPhoto?.visibility = View.VISIBLE
            tvStatus?.visibility = View.GONE
            applyPhotoTransform()
        } else {
            ivPhoto?.visibility = View.GONE
            tvStatus?.visibility = View.VISIBLE
            tvStatus?.text = "Unable to decode photo file."
        }
    }

    private fun setupVideoOrGif(
        filePath: String?,
        fileExists: Boolean,
        isLoopingGif: Boolean,
    ) {
        if (!fileExists || filePath == null) {
            vvVideo?.visibility = View.GONE
            tvStatus?.visibility = View.VISIBLE
            tvStatus?.text = "Downloading video/GIF from Telegram...\nPress [1] to prioritize download."
            if (message.mediaFileId != 0) {
                onRequestDownload(message.mediaFileId)
            }
            return
        }

        vvVideo?.visibility = View.VISIBLE
        tvStatus?.visibility = View.GONE
        vvVideo?.setVideoURI(Uri.fromFile(File(filePath)))
        vvVideo?.setOnPreparedListener { mp ->
            mp.isLooping = isLoopingGif
            vvVideo?.start()
            isVideoPlaying = true
            tvZoomBadge?.text = if (isLoopingGif) "GIF ▶" else "Playing ▶"
        }
        vvVideo?.setOnCompletionListener {
            if (!isLoopingGif) {
                isVideoPlaying = false
                tvZoomBadge?.text = "Ended ■"
            }
        }
        vvVideo?.setOnErrorListener { _, _, _ ->
            if (isLoopingGif) {
                vvVideo?.visibility = View.GONE
                ivPhoto?.visibility = View.VISIBLE
                ivPhoto?.load(File(filePath))
                tvStatus?.visibility = View.GONE
                tvZoomBadge?.text = "GIF ▶"
            } else {
                vvVideo?.visibility = View.GONE
                tvStatus?.visibility = View.VISIBLE
                tvStatus?.text = "Unable to play video stream on this device."
            }
            true
        }
    }

    fun cycleZoomIn() {
        val currentIdx = zoomSteps.indexOfFirst { it == zoomLevel }
        val nextIdx = if (currentIdx in zoomSteps.indices) (currentIdx + 1) % zoomSteps.size else 0
        zoomLevel = zoomSteps[nextIdx]
        if (zoomLevel == 1.0f) {
            panX = 0f
            panY = 0f
        }
        applyPhotoTransform()
    }

    fun zoomOut() {
        val currentIdx = zoomSteps.indexOfFirst { it == zoomLevel }
        val prevIdx = if (currentIdx > 0) currentIdx - 1 else 0
        zoomLevel = zoomSteps[prevIdx]
        if (zoomLevel == 1.0f) {
            panX = 0f
            panY = 0f
        }
        applyPhotoTransform()
    }

    fun resetZoomAndPan() {
        zoomLevel = 1.0f
        panX = 0f
        panY = 0f
        applyPhotoTransform()
    }

    fun panBy(
        dx: Float,
        dy: Float,
    ): Boolean {
        if (zoomLevel <= 1.0f) return false
        val maxOffset = 240f * (zoomLevel - 1.0f)
        panX = (panX + dx).coerceIn(-maxOffset, maxOffset)
        panY = (panY + dy).coerceIn(-maxOffset, maxOffset)
        applyPhotoTransform()
        return true
    }

    private fun applyPhotoTransform() {
        ivPhoto?.scaleX = zoomLevel
        ivPhoto?.scaleY = zoomLevel
        ivPhoto?.translationX = panX
        ivPhoto?.translationY = panY
        tvZoomBadge?.text = String.format(Locale.US, "%.1fx", zoomLevel)
    }

    private fun toggleVideoPlayback() {
        val vv = vvVideo ?: return
        if (vv.isPlaying) {
            vv.pause()
            isVideoPlaying = false
            tvZoomBadge?.text = "Paused ⏸"
        } else {
            vv.start()
            isVideoPlaying = true
            tvZoomBadge?.text = "Playing ▶"
        }
    }

    private fun seekVideoBy(deltaMs: Int) {
        val vv = vvVideo ?: return
        val duration = vv.duration.coerceAtLeast(0)
        val target = (vv.currentPosition + deltaMs).coerceIn(0, if (duration > 0) duration else Int.MAX_VALUE)
        vv.seekTo(target)
    }

    override fun onStop() {
        try {
            vvVideo?.stopPlayback()
        } catch (_: Exception) {
        }
        super.onStop()
    }

    override fun onBackPressed() {
        dismiss()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (isBackOrRightSoftKey(keyCode)) {
            dismiss()
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_1) {
            onSaveMedia(message)
            return true
        }

        val isVideoMode = message.mediaType == MediaType.VIDEO || message.mediaType == MediaType.ANIMATION
        if (isVideoMode) {
            when {
                SoftkeyUtils.isCenterKey(keyCode) -> {
                    toggleVideoPlayback()
                    return true
                }
                SoftkeyUtils.isSoftLeftKey(keyCode) || keyCode == KeyEvent.KEYCODE_0 -> {
                    vvVideo?.seekTo(0)
                    vvVideo?.start()
                    isVideoPlaying = true
                    tvZoomBadge?.text = "Playing ▶"
                    return true
                }
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_4 -> {
                    seekVideoBy(-5000)
                    return true
                }
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_6 -> {
                    seekVideoBy(5000)
                    return true
                }
            }
        } else {
            when {
                SoftkeyUtils.isSoftLeftKey(keyCode) || SoftkeyUtils.isCenterKey(keyCode) || keyCode == KeyEvent.KEYCODE_2 -> {
                    cycleZoomIn()
                    return true
                }
                keyCode == KeyEvent.KEYCODE_8 -> {
                    zoomOut()
                    return true
                }
                keyCode == KeyEvent.KEYCODE_5 -> {
                    resetZoomAndPan()
                    return true
                }
                keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                    if (panBy(0f, panStepPx)) return true
                }
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (panBy(0f, -panStepPx)) return true
                }
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (panBy(panStepPx, 0f)) return true
                }
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (panBy(-panStepPx, 0f)) return true
                }
            }
        }

        return super.onKeyDown(keyCode, event)
    }
}
