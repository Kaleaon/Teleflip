package com.teleflip.app.model

enum class ChatType {
    DIRECT, GROUP, CHANNEL, BOT
}

data class ChatModel(
    val id: Long,
    val title: String,
    val lastMessage: String,
    val time: String,
    val unreadCount: Int,
    val chatType: ChatType,
    val avatarLetters: String,
    val isPinned: Boolean = false,
    val avatarPath: String? = null,
    val avatarFileId: Int = 0
)

enum class MessageStatus {
    SENDING, SENT, READ, FAILED
}

enum class MediaType {
    NONE, PHOTO, VOICE, LOCATION, DOCUMENT, STICKER, VIDEO
}

data class MessageModel(
    val id: Long,
    val chatId: Long,
    val senderName: String,
    val text: String,
    val time: String,
    val isOutbound: Boolean,
    val status: MessageStatus = MessageStatus.SENT,
    val mediaType: MediaType = MediaType.NONE,
    val mediaCaption: String = "",
    val mediaFilePath: String? = null,
    val mediaFileId: Int = 0,
    val durationSeconds: Int = 0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

data class MessageSearchResult(
    val messageId: Long,
    val chatId: Long,
    val chatTitle: String,
    val senderName: String,
    val text: String,
    val time: String,
    val dateMillis: Long = 0L
)
