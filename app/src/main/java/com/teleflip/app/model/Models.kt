package com.teleflip.app.model

enum class ChatType {
    DIRECT,
    GROUP,
    CHANNEL,
    BOT,
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
    val avatarFileId: Int = 0,
    val isPremium: Boolean = false,
)

enum class MessageStatus {
    SENDING,
    SENT,
    READ,
    FAILED,
}

enum class MediaType {
    NONE,
    PHOTO,
    VOICE,
    LOCATION,
    DOCUMENT,
    STICKER,
    VIDEO,
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
    val longitude: Double = 0.0,
    val stickerEmoji: String = "",
    val isPremiumSticker: Boolean = false,
)

data class MessageSearchResult(
    val messageId: Long,
    val chatId: Long,
    val chatTitle: String,
    val senderName: String,
    val text: String,
    val time: String,
    val dateMillis: Long = 0L,
)

enum class CallState {
    PENDING,
    EXCHANGING_KEYS,
    READY,
    HANGING_UP,
    DISCARDED,
    ERROR,
}

data class ActiveCall(
    val callId: Int,
    val userId: Long,
    val userName: String,
    val isOutgoing: Boolean,
    val state: CallState,
    val durationSec: Int = 0,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
)

data class ChatProfile(
    val chatId: Long,
    val title: String,
    val chatType: ChatType,
    val avatarPath: String? = null,
    val avatarLetters: String = "TG",
    val statusText: String = "",
    val username: String = "",
    val phoneNumber: String = "",
    val bio: String = "",
    val memberCount: Int = 0,
    val isMuted: Boolean = false,
    val isPremium: Boolean = false,
)
