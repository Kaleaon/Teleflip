package com.teleflip.app.ui

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.core.view.ViewCompat
import coil.dispose
import coil.load
import com.teleflip.app.R
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class MediaViewerDialog(
    context: Context,
    private var message: MessageModel,
    private val onSaveMedia: (MessageModel) -> Unit,
    private val onRequestDownload: (Int) -> Unit,
    private val messageUpdates: StateFlow<Map<Long, List<MessageModel>>>? = null,
) : BaseDialog(context) {
    private val dialogJob = SupervisorJob()
    private val dialogScope = CoroutineScope(Dispatchers.Main + dialogJob)

    private var ivPhoto: ImageView? = null
    private var vvVideo: VideoView? = null
    private var tvZoomBadge: TextView? = null
    private var tvStatus: TextView? = null
    private var tvCaption: TextView? = null
    private var tvHint: TextView? = null
    private var layoutProgress: View? = null
    private var pbProgress: ProgressBar? = null
    private var tvTime: TextView? = null
    private var progressJob: Job? = null

    var zoomLevel: Float = 1.0f
        private set
    var panX: Float = 0f
        private set
    var panY: Float = 0f
        private set

    var playbackSpeed: Float = 1.0f
        private set

    private val zoomSteps = listOf(1.0f, 1.5f, 2.0f, 3.0f)
    private val playbackSpeedSteps = listOf(1.0f, 1.25f, 1.5f, 2.0f)
    private val panStepPx = 48f
    private var isVideoPlaying = false
    private val isVideoMedia: Boolean
        get() =
            message.mediaType == MediaType.VIDEO || message.mediaType == MediaType.ANIMATION ||
                (message.mediaType == MediaType.STICKER && message.stickerFormat == "webm") ||
                (message.mediaType == MediaType.PAID_MEDIA && !message.isPaidMediaLocked && message.mediaMimeType.startsWith("video/"))

    internal var mediaPlayer: android.media.MediaPlayer? = null
    internal var sdkVersion: Int = android.os.Build.VERSION_CODES.M

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_media_viewer)
        setupDismissKeys()

        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

        tvZoomBadge = findViewById(R.id.tv_viewer_zoom_badge)
        ivPhoto = findViewById(R.id.iv_viewer_photo)
        vvVideo = findViewById(R.id.vv_viewer_video)
        tvStatus = findViewById(R.id.tv_viewer_status)
        tvCaption = findViewById(R.id.tv_viewer_caption)
        tvHint = findViewById(R.id.tv_viewer_controls_hint)
        layoutProgress = findViewById(R.id.layout_viewer_progress)
        pbProgress = findViewById(R.id.pb_viewer_progress)
        tvTime = findViewById(R.id.tv_viewer_time)

        renderMedia()
        dialogScope.launch {
            messageUpdates?.collect { chats ->
                val messages = chats[message.chatId].orEmpty()
                val updated =
                    messages.find { it.id == message.id }
                        ?: messages.find { message.mediaFileId != 0 && it.mediaFileId == message.mediaFileId }
                if (updated != null) updateMessage(updated)
            }
        }
    }

    internal fun updateMessage(updated: MessageModel) {
        val needsRender = updated.mediaFilePath != message.mediaFilePath || updated.isPaidMediaLocked != message.isPaidMediaLocked || updated.mediaFileId != message.mediaFileId || updated.mediaMimeType != message.mediaMimeType || updated.mediaType != message.mediaType
        message = updated
        if (needsRender) renderMedia()
    }

    private fun renderMedia() {
        val tvTitle = findViewById<TextView>(R.id.tv_viewer_title)
        vvVideo?.stopPlayback()
        vvVideo?.visibility = View.GONE
        ivPhoto?.dispose()
        ivPhoto?.visibility = View.GONE
        val captionStr = message.mediaCaption.ifBlank { message.text }
        if (captionStr.isNotBlank()) {
            tvCaption?.visibility = View.VISIBLE
            val captionMessage = if (message.mediaCaption.isNotBlank()) message.copy(text = message.mediaCaption, mediaCaption = "", mediaType = MediaType.NONE) else message
            tvCaption?.text = MessageTextFormatter.format(captionMessage)
        } else {
            tvCaption?.visibility = View.GONE
        }

        val filePath = message.mediaFilePath
        val fileExists = !filePath.isNullOrBlank() && File(filePath).exists()

        when (message.mediaType) {
            MediaType.VIDEO, MediaType.ANIMATION -> {
                val isGif = message.mediaType == MediaType.ANIMATION
                tvTitle?.text = if (isGif) "🎞️ GIF Viewer" else "📹 Video Player"
                tvHint?.text = "[OK] Play/Pause • [◄/►] ±5s • [3] Speed • [LSK] Restart • [1] Save • [RSK] Back"
                tvZoomBadge?.text = if (isGif) "LOOP" else formatSpeedLabel(playbackSpeed)
                setupVideoOrGif(filePath, fileExists, isGif)
            }
            MediaType.STICKER -> {
                val emoji = message.stickerEmoji.ifBlank { "Sticker" }
                tvTitle?.text = "🎨 $emoji Viewer"
                tvHint?.text = "[LSK/2] Zoom • [D-Pad] Pan • [5] Reset • [1] Save • [RSK] Back"
                if (message.stickerFormat == "webm") {
                    tvHint?.text = "[OK] Play/Pause • [LSK] Restart • [1] Save • [RSK] Back"
                    setupVideoOrGif(filePath, fileExists, true)
                } else {
                    setupPhoto(filePath, fileExists)
                }
            }
            MediaType.PAID_MEDIA -> {
                if (message.isPaidMediaLocked) {
                    tvTitle?.text = "🔒 Locked Media (⭐ ${message.starCount} Stars)"
                    tvHint?.text = "Unlock this media from the conversation • [RSK] Back"
                } else {
                    tvTitle?.text = "🔓 Unlocked Paid Media"
                    tvHint?.text = if (isVideoMedia) "[OK] Play/Pause • [◄/►] ±5s • [3] Speed • [LSK] Restart • [1] Save • [RSK] Back" else "[LSK/2] Zoom • [D-Pad] Pan • [5] Reset • [1] Save • [RSK] Back"
                }
                if (isVideoMedia) setupVideoOrGif(filePath, fileExists, false) else setupPhoto(filePath, fileExists)
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
        layoutProgress?.visibility = View.GONE
        if (!fileExists || filePath == null) {
            ivPhoto?.visibility = View.GONE
            tvStatus?.visibility = View.VISIBLE
            tvStatus?.text = "Downloading photo from Telegram...\nPress [1] to prioritize download."
            if (message.mediaFileId != 0) {
                onRequestDownload(message.mediaFileId)
            }
            return
        }

        ivPhoto?.visibility = View.GONE
        tvStatus?.visibility = View.VISIBLE
        tvStatus?.text = "Loading photo..."

        ivPhoto?.load(File(filePath)) {
            size(context.resources.displayMetrics.widthPixels.coerceAtLeast(1), context.resources.displayMetrics.heightPixels.coerceAtLeast(1))
            listener(
                onSuccess = { _, _ ->
                    ivPhoto?.visibility = View.VISIBLE
                    tvStatus?.visibility = View.GONE
                    applyPhotoTransform()
                },
                onError = { _, _ ->
                    ivPhoto?.visibility = View.GONE
                    tvStatus?.visibility = View.VISIBLE
                    tvStatus?.text = "Unable to display this media file."
                },
            )
        }
    }

    private fun setupVideoOrGif(
        filePath: String?,
        fileExists: Boolean,
        isLoopingGif: Boolean,
    ) {
        if (!fileExists || filePath == null) {
            layoutProgress?.visibility = View.GONE
            vvVideo?.visibility = View.GONE
            tvStatus?.visibility = View.VISIBLE
            tvStatus?.text = "Downloading video/GIF from Telegram...\nPress [1] to prioritize download."
            if (message.mediaFileId != 0) {
                onRequestDownload(message.mediaFileId)
            }
            return
        }

        if (isLoopingGif && File(filePath).extension.equals("gif", ignoreCase = true)) {
            setupPhoto(filePath, true)
            return
        }

        layoutProgress?.visibility = View.VISIBLE
        vvVideo?.visibility = View.VISIBLE
        tvStatus?.visibility = View.GONE
        vvVideo?.setVideoURI(Uri.fromFile(File(filePath)))
        vvVideo?.setOnPreparedListener { mp ->
            mediaPlayer = mp
            mp.isLooping = isLoopingGif
            vvVideo?.start()
            isVideoPlaying = true
            applyPlaybackSpeed()
            updateSpeedBadge()
            updateProgressUI()
            startProgressTracking()
            val playingDesc = context.getString(R.string.cd_video_state_playing)
            vvVideo?.let { ViewCompat.setStateDescription(it, playingDesc) }
        }
        vvVideo?.setOnCompletionListener {
            stopProgressTracking()
            val totalMs = vvVideo?.duration ?: 0
            updateProgressUI(currentMs = totalMs, totalMs = totalMs)
            if (!isLoopingGif) {
                isVideoPlaying = false
                tvZoomBadge?.text = "Ended ■"
                val endedDesc = context.getString(R.string.cd_video_state_ended)
                vvVideo?.let { ViewCompat.setStateDescription(it, endedDesc) }
            }
        }
        vvVideo?.setOnErrorListener { _, _, _ ->
            stopProgressTracking()
            layoutProgress?.visibility = View.GONE
            if (isLoopingGif) {
                vvVideo?.visibility = View.GONE
                ivPhoto?.visibility = View.VISIBLE
                ivPhoto?.load(File(filePath)) {
                    listener(
                        onSuccess = { _, _ ->
                            tvStatus?.visibility = View.VISIBLE
                            tvStatus?.text = "Showing preview; animation codec unavailable on this device."
                        },
                        onError = { _, _ ->
                            ivPhoto?.visibility = View.GONE
                            tvStatus?.visibility = View.VISIBLE
                            tvStatus?.text = "Unable to decode this animation on this device."
                        },
                    )
                }
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
        val zoomDesc = ContentDescriptionFormatter.getZoomDescription(context, zoomLevel)
        ivPhoto?.let { ViewCompat.setStateDescription(it, zoomDesc) }
        tvZoomBadge?.let { ViewCompat.setStateDescription(it, zoomDesc) }
    }

    fun cyclePlaybackSpeed(): Float {
        val currentIdx = playbackSpeedSteps.indexOfFirst { it == playbackSpeed }
        val nextIdx = if (currentIdx in playbackSpeedSteps.indices) (currentIdx + 1) % playbackSpeedSteps.size else 0
        playbackSpeed = playbackSpeedSteps[nextIdx]
        applyPlaybackSpeed()
        updateSpeedBadge()
        return playbackSpeed
    }

    internal fun applyPlaybackSpeed() {
        val mp = mediaPlayer ?: return
        if (sdkVersion >= android.os.Build.VERSION_CODES.M) {
            try {
                val params = mp.playbackParams
                mp.playbackParams = params.setSpeed(playbackSpeed)
            } catch (_: Exception) {
            }
        }
    }

    internal fun updateSpeedBadge() {
        tvZoomBadge?.text = formatSpeedLabel(playbackSpeed)
    }

    internal fun formatSpeedLabel(speed: Float): String {
        return if (speed == 1.25f) {
            String.format(Locale.US, "%.2fx", speed)
        } else {
            String.format(Locale.US, "%.1fx", speed)
        }
    }

    internal fun startProgressTracking() {
        stopProgressTracking()
        progressJob = dialogScope.launch {
            while (isActive) {
                updateProgressUI()
                delay(250L)
            }
        }
    }

    internal fun stopProgressTracking() {
        progressJob?.cancel()
        progressJob = null
    }

    internal fun updateProgressUI(
        currentMs: Int = vvVideo?.currentPosition ?: 0,
        totalMs: Int = vvVideo?.duration ?: 0,
    ) {
        val pb = pbProgress ?: return
        val tv = tvTime ?: return

        if (totalMs <= 0) {
            pb.max = 100
            pb.progress = 0
            tv.text = "0:00 / 0:00"
        } else {
            pb.max = totalMs
            val safeCurrent = currentMs.coerceIn(0, totalMs)
            pb.progress = safeCurrent
            tv.text = "${formatTimeMs(safeCurrent)} / ${formatTimeMs(totalMs)}"
        }
    }

    internal fun formatTimeMs(ms: Int): String {
        val safeMs = ms.coerceAtLeast(0)
        val totalSec = safeMs / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", min, sec)
    }

    private fun toggleVideoPlayback() {
        val vv = vvVideo ?: return
        if (vv.isPlaying) {
            vv.pause()
            isVideoPlaying = false
            stopProgressTracking()
            updateProgressUI()
            tvZoomBadge?.text = "Paused ⏸"
            val pausedDesc = context.getString(R.string.cd_video_state_paused)
            ViewCompat.setStateDescription(vv, pausedDesc)
        } else {
            vv.start()
            isVideoPlaying = true
            updateProgressUI()
            startProgressTracking()
            tvZoomBadge?.text = "Playing ▶"
            val playingDesc = context.getString(R.string.cd_video_state_playing)
            ViewCompat.setStateDescription(vv, playingDesc)
        }
    }

    private fun seekVideoBy(deltaMs: Int) {
        val vv = vvVideo ?: return
        val duration = vv.duration.coerceAtLeast(0)
        val target = (vv.currentPosition + deltaMs).coerceIn(0, if (duration > 0) duration else Int.MAX_VALUE)
        vv.seekTo(target)
        updateProgressUI(currentMs = target, totalMs = duration)
    }

    override fun onStop() {
        stopProgressTracking()
        try {
            vvVideo?.stopPlayback()
        } catch (_: Exception) {
        }
        ivPhoto?.dispose()
        dialogJob.cancelChildren()
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

        if (isVideoMedia) {
            when {
                SoftkeyUtils.isCenterKey(keyCode) -> {
                    toggleVideoPlayback()
                    return true
                }
                keyCode == KeyEvent.KEYCODE_3 -> {
                    cyclePlaybackSpeed()
                    return true
                }
                SoftkeyUtils.isSoftLeftKey(keyCode) || keyCode == KeyEvent.KEYCODE_0 -> {
                    vvVideo?.seekTo(0)
                    vvVideo?.start()
                    isVideoPlaying = true
                    updateSpeedBadge()
                    updateProgressUI(currentMs = 0, totalMs = vvVideo?.duration ?: 0)
                    startProgressTracking()
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
