package com.teleflip.app.ui

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.teleflip.app.R
import com.teleflip.app.storage.ChatExportProgress
import com.teleflip.app.storage.ExportStage

class ChatExportProgressDialog(
    context: Context,
    private val onCancelRequested: () -> Unit,
) : BaseDialog(context) {
    private var tvTitle: TextView? = null
    private var tvStatus: TextView? = null
    private var tvDetail: TextView? = null
    private var progressBar: ProgressBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_chat_export_progress)
        setCancelable(false)
        setupDismissKeys()

        tvTitle = findViewById(R.id.tv_progress_title)
        tvStatus = findViewById(R.id.tv_progress_status)
        tvDetail = findViewById(R.id.tv_progress_detail)
        progressBar = findViewById(R.id.pb_export_progress)

        findViewById<Button>(R.id.btn_cancel_progress)?.setOnClickListener {
            onCancelRequested()
            dismiss()
        }
    }

    fun updateProgress(progress: ChatExportProgress) {
        val updateAction = Runnable {
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

        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateAction.run()
        } else {
            Handler(Looper.getMainLooper()).post(updateAction)
        }
    }
}
