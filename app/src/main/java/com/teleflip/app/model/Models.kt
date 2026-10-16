package com.teleflip.app.model

enum class ChatType {
    DIRECT,
    GROUP,
    CHANNEL,
    BOT,
}

data class PollOption(
    val text: String,
    val voterCount: Int = 0,
    val votePercentage: Int = 0,
    val isChosen: Boolean = false,
)

data class GroupMember(
    val userId: Long,
    val name: String,
    val role: String = "Member",
    val status: String = "offline",
    val isAdmin: Boolean = false,
    val isCreator: Boolean = false,
)

data class ForumTopicModel(
    val topicId: Long,
    val name: String,
    val unreadCount: Int = 0,
    val iconEmoji: String = "💬",
    val isClosed: Boolean = false,
)

data class ChatFolderModel(
    val id: Int,
    val title: String,
    val includedChatIds: Set<Long> = emptySet(),
)

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
    val isMuted: Boolean = false,
    val draftText: String = "",
    val pinnedMessageId: Long = 0L,
    val isArchived: Boolean = false,
    val folderName: String = "",
    val isSecretChat: Boolean = false,
    val secretChatId: Int = 0,
    val selfDestructTimerSec: Int = 0,
    val isForum: Boolean = false,
    val activeTopicId: Long = 0L,
    val activeTopicName: String = "",
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
    ANIMATION,
    POLL,
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
    val replyToMessageId: Long = 0L,
    val replyToPreview: String = "",
    val isEdited: Boolean = false,
    val isPinned: Boolean = false,
    val reactions: List<String> = emptyList(),
    val forwardedFrom: String = "",
    val pollQuestion: String = "",
    val pollOptions: List<PollOption> = emptyList(),
    val pollIsAnonymous: Boolean = true,
    val pollIsClosed: Boolean = false,
    val pollTotalVoters: Int = 0,
    val messageThreadId: Long = 0L,
    val selfDestructSeconds: Int = 0,
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
    val isForum: Boolean = false,
    val isSecretChat: Boolean = false,
    val selfDestructTimerSec: Int = 0,
    val members: List<GroupMember> = emptyList(),
)
