package com.teleflip.app.export

enum class ExportFormat(val label: String, val extension: String) {
    JSON("JSON Format (.json)", ".json"),
    HTML("HTML Format (.html)", ".html"),
    BOTH("Both Formats (JSON & HTML)", ".json & .html"),
}

data class ExportMessage(
    val messageId: Long,
    val timestamp: Long,
    val dateFormatted: String,
    val senderId: Long,
    val senderName: String,
    val text: String,
    val attachmentType: String? = null,
    val attachmentDetails: String? = null,
    val isOutbound: Boolean = false,
)
