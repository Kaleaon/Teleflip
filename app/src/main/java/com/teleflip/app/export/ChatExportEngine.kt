package com.teleflip.app.export

import android.content.Context
import android.os.Environment
import com.teleflip.app.telegram.TelegramManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException

object ChatExportEngine {
    fun exportChatHistory(
        context: Context,
        scope: CoroutineScope,
        telegramManager: TelegramManager,
        chatId: Long,
        chatTitle: String,
        format: ExportFormat,
        mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
        onProgress: (completedCount: Int) -> Unit,
        onComplete: (Result<List<File>>) -> Unit,
    ): Job {
        return scope.launch(Dispatchers.IO) {
            val allMessages = mutableListOf<ExportMessage>()
            var fromMessageId = 0L
            val limit = 100
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

            try {
                while (coroutineContext.isActive) {
                    val batch = telegramManager.getChatHistoryBatch(chatId, fromMessageId, limit)
                    if (batch.isEmpty()) {
                        break
                    }

                    for (msg in batch) {
                        if (!coroutineContext.isActive) break
                        val senderName = telegramManager.resolveSenderName(msg.senderId)
                        val parsed = parseMessage(msg, senderName, dateFormat)
                        allMessages.add(parsed)
                    }

                    fromMessageId = batch.last().id

                    val currentSize = allMessages.size
                    withContext(mainDispatcher) {
                        onProgress(currentSize)
                    }

                    if (batch.size < limit) {
                        break
                    }
                }

                if (!coroutineContext.isActive) {
                    withContext(NonCancellable + mainDispatcher) {
                        onComplete(Result.failure(CancellationException("Export cancelled")))
                    }
                    return@launch
                }

                // Sort messages chronologically (oldest first)
                val sortedMessages = allMessages.sortedBy { it.timestamp }

                val exportedAt = dateFormat.format(Date())
                val targetDir = getExportDirectory(context)
                val cleanTitle = chatTitle.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(30).ifEmpty { "chat" }
                val timeStampStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

                val savedFiles = mutableListOf<File>()

                if (format == ExportFormat.JSON || format == ExportFormat.BOTH) {
                    val jsonContent = ChatExportFormatter.formatToJson(chatId, chatTitle, sortedMessages, exportedAt)
                    val jsonFile = File(targetDir, "chat_export_${cleanTitle}_$timeStampStr.json")
                    jsonFile.writeText(jsonContent, Charsets.UTF_8)
                    savedFiles.add(jsonFile)
                }

                if (format == ExportFormat.HTML || format == ExportFormat.BOTH) {
                    val htmlContent = ChatExportFormatter.formatToHtml(chatId, chatTitle, sortedMessages, exportedAt)
                    val htmlFile = File(targetDir, "chat_export_${cleanTitle}_$timeStampStr.html")
                    htmlFile.writeText(htmlContent, Charsets.UTF_8)
                    savedFiles.add(htmlFile)
                }

                if (!coroutineContext.isActive) {
                    savedFiles.forEach { if (it.exists()) it.delete() }
                    withContext(NonCancellable + mainDispatcher) {
                        onComplete(Result.failure(CancellationException("Export cancelled")))
                    }
                    return@launch
                }

                withContext(NonCancellable + mainDispatcher) {
                    onComplete(Result.success(savedFiles))
                }
            } catch (e: Throwable) {
                withContext(NonCancellable + mainDispatcher) {
                    onComplete(Result.failure(if (e is CancellationException) e else Exception(e)))
                }
            }
        }
    }

    fun parseMessage(
        msg: TdApi.Message,
        senderName: String,
        dateFormat: SimpleDateFormat,
    ): ExportMessage {
        val timestamp = msg.date.toLong()
        val dateFormatted = dateFormat.format(Date(timestamp * 1000L))
        val senderId =
            when (val sender = msg.senderId) {
                is TdApi.MessageSenderUser -> sender.userId
                is TdApi.MessageSenderChat -> sender.chatId
                else -> 0L
            }

        var text = ""
        var attachmentType: String? = null
        var attachmentDetails: String? = null

        when (val content = msg.content) {
            is TdApi.MessageText -> {
                text = content.text.text
            }
            is TdApi.MessagePhoto -> {
                text = content.caption?.text ?: ""
                attachmentType = "Photo"
                val size = content.photo?.sizes?.lastOrNull()
                attachmentDetails = if (size != null) "${size.width}x${size.height}" else "Photo"
            }
            is TdApi.MessageVideo -> {
                text = content.caption?.text ?: ""
                attachmentType = "Video"
                val fileName = content.video?.fileName?.ifBlank { "video.mp4" } ?: "video.mp4"
                attachmentDetails = "$fileName (${content.video?.duration ?: 0}s)"
            }
            is TdApi.MessageDocument -> {
                text = content.caption?.text ?: ""
                attachmentType = "Document"
                attachmentDetails = content.document?.fileName?.ifBlank { "file" } ?: "file"
            }
            is TdApi.MessageVoiceNote -> {
                text = content.caption?.text ?: ""
                attachmentType = "Voice Note"
                attachmentDetails = "${content.voiceNote?.duration ?: 0}s"
            }
            is TdApi.MessageAudio -> {
                text = content.caption?.text ?: ""
                attachmentType = "Audio"
                val name = content.audio?.title?.ifBlank { content.audio?.fileName } ?: "audio"
                attachmentDetails = "$name (${content.audio?.duration ?: 0}s)"
            }
            is TdApi.MessageSticker -> {
                attachmentType = "Sticker"
                attachmentDetails = content.sticker?.emoji ?: "😊"
            }
            is TdApi.MessageAnimation -> {
                text = content.caption?.text ?: ""
                attachmentType = "Animation/GIF"
                attachmentDetails = content.animation?.fileName ?: "animation.gif"
            }
            is TdApi.MessagePoll -> {
                text = content.poll?.question?.text ?: "Poll"
                attachmentType = "Poll"
                attachmentDetails = "${content.poll?.options?.size ?: 0} options"
            }
            is TdApi.MessageLocation -> {
                attachmentType = "Location"
                attachmentDetails = "Lat: ${content.location?.latitude ?: 0.0}, Lon: ${content.location?.longitude ?: 0.0}"
            }
            is TdApi.MessageCall -> {
                attachmentType = "Call"
                attachmentDetails = "${content.duration}s"
            }
            is TdApi.MessageContact -> {
                val contactName = "${content.contact?.firstName ?: ""} ${content.contact?.lastName ?: ""}".trim()
                text = contactName
                attachmentType = "Contact"
                attachmentDetails = content.contact?.phoneNumber ?: ""
            }
            else -> {
                text = "[Media Content]"
                attachmentType = "Media"
            }
        }

        return ExportMessage(
            messageId = msg.id,
            timestamp = timestamp,
            dateFormatted = dateFormatted,
            senderId = senderId,
            senderName = senderName,
            text = text,
            attachmentType = attachmentType,
            attachmentDetails = attachmentDetails,
            isOutbound = msg.isOutgoing,
        )
    }

    fun getExportDirectory(context: Context): File {
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val exportDir = File(documentsDir, "TeleFlip_Exports")
        if (!exportDir.exists()) {
            val created = exportDir.mkdirs()
            if (!created) {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val fallbackDir = File(downloadsDir, "TeleFlip_Exports")
                if (!fallbackDir.exists()) {
                    fallbackDir.mkdirs()
                }
                return fallbackDir
            }
        }
        return exportDir
    }
}
