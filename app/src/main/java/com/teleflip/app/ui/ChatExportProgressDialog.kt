package com.teleflip.app.ui

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import com.teleflip.app.R
import com.teleflip.app.export.ChatExportEngine
import com.teleflip.app.export.ExportFormat
import com.teleflip.app.telegram.TelegramManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.io.File

class ChatExportProgressDialog(
    context: Context,
    private val scope: CoroutineScope,
    private val telegramManager: TelegramManager,
    private val chatId: Long,
    private val chatTitle: String,
    private val format: ExportFormat,
    private val onCompleteCallback: (Result<List<File>>) -> Unit,
) : BaseDialog(context) {

    private var exportJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_export_progress)
        setupDismissKeys()

        val tvChatName = findViewById<TextView>(R.id.tv_export_chat_name)
        val tvStatus = findViewById<TextView>(R.id.tv_export_status)
        val btnCancel = findViewById<Button>(R.id.btn_cancel_export)

        tvChatName?.text = "Chat: $chatTitle (${format.label})"
        tvStatus?.text = "Exported 0 messages..."

        btnCancel?.setOnClickListener {
            cancelExportAndDismiss()
        }
        btnCancel?.requestFocus()

        exportJob = ChatExportEngine.exportChatHistory(
            context = context,
            scope = scope,
            telegramManager = telegramManager,
            chatId = chatId,
            chatTitle = chatTitle,
            format = format,
            onProgress = { count ->
                tvStatus?.text = "Exported $count messages..."
            },
            onComplete = { result ->
                if (isShowing) {
                    dismiss()
                }
                onCompleteCallback(result)
            },
        )
    }

    private fun cancelExportAndDismiss() {
        exportJob?.cancel()
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
}
