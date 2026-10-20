package com.teleflip.app.storage

import android.content.Context
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportStage {
    STARTING,
    FETCHING_CLOUD_HISTORY,
    DOWNLOADING_MEDIA,
    GENERATING_FILES,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class ChatExportOptions(
    val includeText: Boolean = true,
    val includePhotos: Boolean = true,
    val includeGifs: Boolean = true,
    val includeVideos: Boolean = true,
    val includeVoice: Boolean = true,
    val includeDocuments: Boolean = true,
    val targetStorageType: StorageLocationType = StorageLocationType.INTERNAL,
)

data class ChatExportProgress(
    val stage: ExportStage,
    val messagesProcessed: Int = 0,
    val totalMessages: Int = 0,
    val mediaDownloaded: Int = 0,
    val totalMedia: Int = 0,
    val statusText: String = "",
    val exportDir: File? = null,
)

object ChatExportManager {
    private const val FOLDER_IMAGES = "images"
    private const val FOLDER_GIFS = "gifs"
    private const val FOLDER_VIDEOS = "videos"
    private const val FOLDER_AUDIO = "audio"
    private const val FOLDER_FILES = "files"

    fun sanitizeFilename(name: String): String {
        val sanitized = name.replace(Regex("[\\\\/:*?\"<>|]+"), "_").trim().trim('_')
        return if (sanitized.isBlank()) "Chat" else sanitized.take(50)
    }

    suspend fun exportChat(
        context: Context,
        chatId: Long,
        chatTitle: String,
        messages: List<MessageModel>,
        options: ChatExportOptions,
        fileDownloader: (suspend (fileId: Int) -> String?)? = null,
        onProgress: ((ChatExportProgress) -> Unit)? = null,
    ): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                onProgress?.invoke(
                    ChatExportProgress(
                        stage = ExportStage.STARTING,
                        totalMessages = messages.size,
                        statusText = "Initializing export for \"$chatTitle\"...",
                    ),
                )

                val exportBaseDir = StorageManager.getExportDirectory(context, options.targetStorageType)
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val cleanTitle = sanitizeFilename(chatTitle)
                val exportDir = File(exportBaseDir, "${cleanTitle}_$timeStamp")
                if (!exportDir.exists() && !exportDir.mkdirs()) {
                    return@withContext Result.failure(IllegalStateException("Could not create export directory: ${exportDir.absolutePath}"))
                }

                // Create selected media subfolders
                val dirImages = if (options.includePhotos) File(exportDir, FOLDER_IMAGES).apply { mkdirs() } else null
                val dirGifs = if (options.includeGifs) File(exportDir, FOLDER_GIFS).apply { mkdirs() } else null
                val dirVideos = if (options.includeVideos) File(exportDir, FOLDER_VIDEOS).apply { mkdirs() } else null
                val dirAudio = if (options.includeVoice) File(exportDir, FOLDER_AUDIO).apply { mkdirs() } else null
                val dirFiles = if (options.includeDocuments) File(exportDir, FOLDER_FILES).apply { mkdirs() } else null

                // Identify media messages matching options
                val mediaMessages =
                    messages.filter { msg ->
                        when (msg.mediaType) {
                            MediaType.PHOTO -> options.includePhotos
                            MediaType.ANIMATION -> options.includeGifs
                            MediaType.VIDEO -> options.includeVideos
                            MediaType.VOICE -> options.includeVoice
                            MediaType.DOCUMENT -> options.includeDocuments
                            else -> false
                        }
                    }

                var mediaDownloadedCount = 0
                val totalMediaCount = mediaMessages.size
                val exportedRelativePaths = mutableMapOf<Long, String>()

                onProgress?.invoke(
                    ChatExportProgress(
                        stage = ExportStage.DOWNLOADING_MEDIA,
                        messagesProcessed = messages.size,
                        totalMessages = messages.size,
                        mediaDownloaded = 0,
                        totalMedia = totalMediaCount,
                        statusText = "Exporting media files (0/$totalMediaCount)...",
                        exportDir = exportDir,
                    ),
                )

                for (msg in mediaMessages) {
                    if (!isActive) throw CancellationException("Export cancelled by user")

                    val targetDir =
                        when (msg.mediaType) {
                            MediaType.PHOTO -> dirImages
                            MediaType.ANIMATION -> dirGifs
                            MediaType.VIDEO -> dirVideos
                            MediaType.VOICE -> dirAudio
                            MediaType.DOCUMENT -> dirFiles
                            else -> null
                        } ?: continue

                    var localPath = msg.mediaFilePath
                    if ((localPath.isNullOrBlank() || !File(localPath).exists()) && msg.mediaFileId != 0 && fileDownloader != null) {
                        localPath = fileDownloader(msg.mediaFileId)
                    }

                    if (!localPath.isNullOrBlank()) {
                        val srcFile = File(localPath)
                        if (srcFile.exists() && srcFile.isFile) {
                            val ext =
                                srcFile.extension.ifBlank {
                                    when (msg.mediaType) {
                                        MediaType.PHOTO -> "jpg"
                                        MediaType.ANIMATION -> "gif"
                                        MediaType.VIDEO -> "mp4"
                                        MediaType.VOICE -> "ogg"
                                        else -> "dat"
                                    }
                                }
                            val destName = "msg_${msg.id}_${sanitizeFilename(srcFile.nameWithoutExtension)}.$ext"
                            val destFile = File(targetDir, destName)
                            try {
                                srcFile.copyTo(destFile, overwrite = true)
                                val relativePath = "${targetDir.name}/$destName"
                                exportedRelativePaths[msg.id] = relativePath
                            } catch (_: Exception) {
                            }
                        }
                    }

                    mediaDownloadedCount++
                    onProgress?.invoke(
                        ChatExportProgress(
                            stage = ExportStage.DOWNLOADING_MEDIA,
                            messagesProcessed = messages.size,
                            totalMessages = messages.size,
                            mediaDownloaded = mediaDownloadedCount,
                            totalMedia = totalMediaCount,
                            statusText = "Exporting media: $mediaDownloadedCount/$totalMediaCount",
                            exportDir = exportDir,
                        ),
                    )
                }

                if (!isActive) throw CancellationException("Export cancelled by user")

                onProgress?.invoke(
                    ChatExportProgress(
                        stage = ExportStage.GENERATING_FILES,
                        messagesProcessed = messages.size,
                        totalMessages = messages.size,
                        mediaDownloaded = mediaDownloadedCount,
                        totalMedia = totalMediaCount,
                        statusText = "Generating HTML, TXT, and JSON chat logs...",
                        exportDir = exportDir,
                    ),
                )

                if (options.includeText) {
                    generateHtmlFile(exportDir, chatTitle, messages, exportedRelativePaths)
                    generateTxtFile(exportDir, chatTitle, messages, exportedRelativePaths)
                    generateJsonFile(exportDir, chatId, chatTitle, messages, exportedRelativePaths)
                }

                onProgress?.invoke(
                    ChatExportProgress(
                        stage = ExportStage.COMPLETED,
                        messagesProcessed = messages.size,
                        totalMessages = messages.size,
                        mediaDownloaded = mediaDownloadedCount,
                        totalMedia = totalMediaCount,
                        statusText = "Export completed successfully to ${exportDir.name}!",
                        exportDir = exportDir,
                    ),
                )

                Result.success(exportDir)
            } catch (e: CancellationException) {
                onProgress?.invoke(
                    ChatExportProgress(
                        stage = ExportStage.CANCELLED,
                        statusText = "Export cancelled.",
                    ),
                )
                Result.failure(e)
            } catch (t: Throwable) {
                onProgress?.invoke(
                    ChatExportProgress(
                        stage = ExportStage.FAILED,
                        statusText = "Export failed: ${t.message}",
                    ),
                )
                Result.failure(t)
            }
        }

    private fun generateHtmlFile(
        exportDir: File,
        chatTitle: String,
        messages: List<MessageModel>,
        mediaPaths: Map<Long, String>,
    ) {
        val htmlFile = File(exportDir, "messages.html")
        val writer = OutputStreamWriter(FileOutputStream(htmlFile), StandardCharsets.UTF_8)
        val exportDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        writer.write(
            """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>TeleFlip Export - ${escapeHtml(chatTitle)}</title>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                        background: #0f141c;
                        color: #e0e4eb;
                        margin: 0;
                        padding: 16px;
                    }
                    .container {
                        max-width: 680px;
                        margin: 0 auto;
                    }
                    .header {
                        text-align: center;
                        background: #17212b;
                        padding: 16px;
                        border-radius: 12px;
                        margin-bottom: 20px;
                        box-shadow: 0 4px 12px rgba(0,0,0,0.3);
                    }
                    .header h1 {
                        margin: 0 0 6px 0;
                        font-size: 20px;
                        color: #ffffff;
                    }
                    .header p {
                        margin: 0;
                        font-size: 13px;
                        color: #82868e;
                    }
                    .message-feed {
                        display: flex;
                        flex-direction: column;
                        gap: 12px;
                    }
                    .message-row {
                        display: flex;
                        width: 100%;
                    }
                    .message-row.outbound {
                        justify-content: flex-end;
                    }
                    .message-row.inbound {
                        justify-content: flex-start;
                    }
                    .message-box {
                        max-width: 78%;
                        padding: 10px 14px;
                        border-radius: 14px;
                        font-size: 14px;
                        line-height: 1.45;
                        word-break: break-word;
                        box-shadow: 0 2px 4px rgba(0,0,0,0.2);
                    }
                    .message-row.outbound .message-box {
                        background: #2b5278;
                        color: #ffffff;
                        border-bottom-right-radius: 4px;
                    }
                    .message-row.inbound .message-box {
                        background: #182533;
                        color: #e4ecf2;
                        border-bottom-left-radius: 4px;
                    }
                    .sender {
                        font-size: 12px;
                        font-weight: bold;
                        color: #5288c1;
                        margin-bottom: 4px;
                    }
                    .time {
                        font-size: 10px;
                        color: #8da4be;
                        text-align: right;
                        margin-top: 6px;
                    }
                    .media-img {
                        max-width: 100%;
                        border-radius: 8px;
                        margin-top: 6px;
                        display: block;
                    }
                    .media-video {
                        max-width: 100%;
                        border-radius: 8px;
                        margin-top: 6px;
                        display: block;
                    }
                    .media-audio {
                        width: 100%;
                        margin-top: 6px;
                    }
                    .media-file {
                        display: inline-block;
                        padding: 6px 12px;
                        background: rgba(0, 0, 0, 0.25);
                        border-radius: 6px;
                        text-decoration: none;
                        color: #64b5f6;
                        margin-top: 6px;
                        font-weight: 500;
                    }
                </style>
            </head>
            <body>
            <div class="container">
                <div class="header">
                    <h1>${escapeHtml(chatTitle)}</h1>
                    <p>Exported from TeleFlip • $exportDate • ${messages.size} Messages</p>
                </div>
                <div class="message-feed">
            """.trimIndent() + "\n",
        )

        for (msg in messages) {
            val isOutbound = msg.isOutbound
            val rowClass = if (isOutbound) "outbound" else "inbound"
            val relativeMedia = mediaPaths[msg.id]

            writer.write("""        <div class="message-row $rowClass">""" + "\n")
            writer.write("""            <div class="message-box">""" + "\n")
            if (!isOutbound) {
                writer.write("""                <div class="sender">${escapeHtml(msg.senderName)}</div>""" + "\n")
            }

            if (msg.text.isNotBlank()) {
                writer.write("""                <div class="text">${escapeHtml(msg.text)}</div>""" + "\n")
            }

            if (!relativeMedia.isNullOrBlank()) {
                when (msg.mediaType) {
                    MediaType.PHOTO -> {
                        writer.write("""                <a href="$relativeMedia" target="_blank"><img class="media-img" src="$relativeMedia" alt="Photo" /></a>""" + "\n")
                    }
                    MediaType.ANIMATION -> {
                        writer.write("""                <img class="media-img" src="$relativeMedia" alt="GIF" />""" + "\n")
                    }
                    MediaType.VIDEO -> {
                        writer.write("""                <video class="media-video" controls src="$relativeMedia"></video>""" + "\n")
                    }
                    MediaType.VOICE -> {
                        writer.write("""                <audio class="media-audio" controls src="$relativeMedia"></audio>""" + "\n")
                    }
                    MediaType.DOCUMENT -> {
                        val fileName = relativeMedia.substringAfterLast('/')
                        writer.write("""                <a class="media-file" href="$relativeMedia" download>📄 ${escapeHtml(fileName)}</a>""" + "\n")
                    }
                    else -> {}
                }
            }

            writer.write("""                <div class="time">${escapeHtml(msg.time)}</div>""" + "\n")
            writer.write("""            </div>""" + "\n")
            writer.write("""        </div>""" + "\n")
        }

        writer.write(
            """
                </div>
            </div>
            </body>
            </html>
            """.trimIndent(),
        )
        writer.flush()
        writer.close()
    }

    private fun generateTxtFile(
        exportDir: File,
        chatTitle: String,
        messages: List<MessageModel>,
        mediaPaths: Map<Long, String>,
    ) {
        val txtFile = File(exportDir, "messages.txt")
        val writer = OutputStreamWriter(FileOutputStream(txtFile), StandardCharsets.UTF_8)
        val exportDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        writer.write("======================================================================\n")
        writer.write("TeleFlip Chat Export: $chatTitle\n")
        writer.write("Export Date: $exportDate\n")
        writer.write("Total Messages: ${messages.size}\n")
        writer.write("======================================================================\n\n")

        for (msg in messages) {
            val sender = if (msg.isOutbound) "You" else msg.senderName
            writer.write("[${msg.time}] $sender:\n")
            if (msg.text.isNotBlank()) {
                writer.write("${msg.text}\n")
            }
            val mediaPath = mediaPaths[msg.id]
            if (!mediaPath.isNullOrBlank()) {
                writer.write("[Attached ${msg.mediaType}: $mediaPath]\n")
            }
            writer.write("\n")
        }

        writer.flush()
        writer.close()
    }

    private fun generateJsonFile(
        exportDir: File,
        chatId: Long,
        chatTitle: String,
        messages: List<MessageModel>,
        mediaPaths: Map<Long, String>,
    ) {
        val jsonFile = File(exportDir, "messages.json")
        val exportDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
        val writer = OutputStreamWriter(FileOutputStream(jsonFile), StandardCharsets.UTF_8)

        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"chatId\": ").append(chatId).append(",\n")
        sb.append("  \"title\": \"").append(escapeJson(chatTitle)).append("\",\n")
        sb.append("  \"exportDate\": \"").append(exportDate).append("\",\n")
        sb.append("  \"messageCount\": ").append(messages.size).append(",\n")
        sb.append("  \"messages\": [\n")

        messages.forEachIndexed { index, msg ->
            sb.append("    {\n")
            sb.append("      \"id\": ").append(msg.id).append(",\n")
            sb.append("      \"chatId\": ").append(msg.chatId).append(",\n")
            sb.append("      \"senderName\": \"").append(escapeJson(msg.senderName)).append("\",\n")
            sb.append("      \"isOutbound\": ").append(msg.isOutbound).append(",\n")
            sb.append("      \"time\": \"").append(escapeJson(msg.time)).append("\",\n")
            sb.append("      \"text\": \"").append(escapeJson(msg.text)).append("\",\n")
            sb.append("      \"mediaType\": \"").append(msg.mediaType.name).append("\"")
            val media = mediaPaths[msg.id]
            if (media != null) {
                sb.append(",\n      \"localRelativePath\": \"").append(escapeJson(media)).append("\"\n")
            } else {
                sb.append("\n")
            }
            sb.append("    }")
            if (index < messages.size - 1) {
                sb.append(",")
            }
            sb.append("\n")
        }

        sb.append("  ]\n")
        sb.append("}\n")

        writer.write(sb.toString())
        writer.flush()
        writer.close()
    }

    private fun escapeJson(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
