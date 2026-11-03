package com.teleflip.app.ui

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.teleflip.app.R
import com.teleflip.app.export.ChatExportEngine
import com.teleflip.app.export.ExportFormat
import com.teleflip.app.storage.ChatExportProgress
import com.teleflip.app.storage.ExportStage
import com.teleflip.app.telegram.TelegramManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.io.File

class ChatExportProgressDialog : BaseDialog {
    private var onCancelRequested: (() -> Unit)? = null

    // Engine export parameters
    private var scope: CoroutineScope? = null
    private var telegramManager: TelegramManager? = null
    private var chatId: Long = 0L
    private var chatTitle: String = ""
    private var format: ExportFormat? = null
    private var onCompleteCallback: ((Result<List<File>>) -> Unit)? = null

    private var exportJob: Job? = null

    // Views for R.layout.dialog_chat_export_progress
    private var tvTitle: TextView? = null
    private var tvStatus: TextView? = null
    private var tvDetail: TextView? = null
    private var progressBar: ProgressBar? = null

    constructor(
        context: Context,
        onCancelRequested: () -> Unit = {},
    ) : super(context) {
        this.onCancelRequested = onCancelRequested
    }

    constructor(
        context: Context,
        scope: CoroutineScope,
        telegramManager: TelegramManager,
        chatId: Long,
        chatTitle: String,
        format: ExportFormat,
        onCompleteCallback: (Result<List<File>>) -> Unit,
    ) : super(context) {
        this.scope = scope
        this.telegramManager = telegramManager
        this.chatId = chatId
        this.chatTitle = chatTitle
        this.format = format
        this.onCompleteCallback = onCompleteCallback
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val currentFormat = format
        val currentScope = scope
        val currentTelegramManager = telegramManager
        val currentOnComplete = onCompleteCallback

        if (currentFormat != null && currentScope != null && currentTelegramManager != null && currentOnComplete != null) {
            setContentView(R.layout.dialog_export_progress)
            setupDismissKeys { cancelExportAndDismiss() }

            val tvChatName = findViewById<TextView>(R.id.tv_export_chat_name)
            val tvEngineStatus = findViewById<TextView>(R.id.tv_export_status)
            val btnCancel = findViewById<Button>(R.id.btn_cancel_export)

            tvChatName?.text = "Chat: $chatTitle (${currentFormat.label})"
            tvEngineStatus?.text = "Exported 0 messages..."

            btnCancel?.setOnClickListener {
                cancelExportAndDismiss()
            }
            btnCancel?.requestFocus()

            exportJob =
                ChatExportEngine.exportChatHistory(
                    context = context,
                    scope = currentScope,
                    telegramManager = currentTelegramManager,
                    chatId = chatId,
                    chatTitle = chatTitle,
                    format = currentFormat,
                    onProgress = { count ->
                        tvEngineStatus?.text = "Exported $count messages..."
                    },
                    onComplete = { result ->
                        if (isShowing) {
                            dismiss()
                        }
                        currentOnComplete(result)
                    },
                )
        } else {
            setContentView(R.layout.dialog_chat_export_progress)
            setCancelable(false)
            setupDismissKeys { cancelExportAndDismiss() }

            tvTitle = findViewById(R.id.tv_progress_title)
            tvStatus = findViewById(R.id.tv_progress_status)
            tvDetail = findViewById(R.id.tv_progress_detail)
            progressBar = findViewById(R.id.pb_export_progress)

            findViewById<Button>(R.id.btn_cancel_progress)?.setOnClickListener {
                cancelExportAndDismiss()
            }
        }
    }

    private fun cancelExportAndDismiss() {
        exportJob?.cancel()
        onCancelRequested?.invoke()
        if (isShowing) {
            dismiss()
        }
    }

    override fun onBackPressed() {
        cancelExportAndDismiss()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (isBackOrRightSoftKey(keyCode)) {
            cancelExportAndDismiss()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    fun updateProgress(progress: ChatExportProgress) {
        val titleText =
            when (progress.stage) {
                ExportStage.STARTING -> "⏳ Initializing Download..."
                ExportStage.FETCHING_CLOUD_HISTORY -> "☁️ Fetching Cloud History..."
                ExportStage.DOWNLOADING_MEDIA -> "📥 Downloading Media Files..."
                ExportStage.GENERATING_FILES -> "📄 Writing Export Logs..."
                ExportStage.COMPLETED -> "✅ Export Complete!"
                ExportStage.FAILED -> "❌ Export Failed"
                ExportStage.CANCELLED -> "🚫 Export Cancelled"
            }
        tvTitle?.text = titleText
        tvStatus?.text = progress.statusText

        if (progress.totalMedia > 0 && progress.stage == ExportStage.DOWNLOADING_MEDIA) {
            progressBar?.isIndeterminate = false
            val pct = ((progress.mediaDownloaded.toDouble() / progress.totalMedia.toDouble()) * 100).toInt().coerceIn(0, 100)
            progressBar?.progress = pct
            tvDetail?.text = "Media: ${progress.mediaDownloaded}/${progress.totalMedia} ($pct%) • Messages: ${progress.totalMessages}"
        } else if (progress.stage == ExportStage.FETCHING_CLOUD_HISTORY) {
            progressBar?.isIndeterminate = true
            tvDetail?.text = "Retrieved ${progress.messagesProcessed} messages from cloud..."
        } else if (progress.stage == ExportStage.GENERATING_FILES) {
            progressBar?.isIndeterminate = true
            tvDetail?.text = "Writing HTML, TXT, and JSON files..."
        } else if (progress.stage == ExportStage.COMPLETED) {
            progressBar?.isIndeterminate = false
            progressBar?.progress = 100
            val destName = progress.exportDir?.name ?: ""
            tvDetail?.text = "Saved to $destName (${progress.totalMessages} msgs, ${progress.mediaDownloaded} media)"
        }
    }
}
