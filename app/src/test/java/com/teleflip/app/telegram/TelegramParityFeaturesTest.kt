package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.ChatType
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock

class TelegramParityFeaturesTest {
    private lateinit var telegramManager: TelegramManager

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        Mockito.`when`(context.getSharedPreferences("teleflip_prefs", Context.MODE_PRIVATE)).thenReturn(prefs)
        telegramManager = TelegramManager(context)
    }

    @Test
    fun testSendMessageWithNativeReplyThreading() {
        val chatId = 10L
        val original =
            MessageModel(
                id = 501L,
                chatId = chatId,
                senderName = "Alice",
                text = "Are we still meeting at 6 PM?",
                time = "17:45",
                isOutbound = false,
                status = MessageStatus.READ,
            )
        telegramManager.setMessagesForTesting(mapOf(chatId to listOf(original)))

        telegramManager.sendMessage(
            chatId = chatId,
            text = "Yes, see you there!",
            replyToMessageId = 501L,
        )

        val msgs = telegramManager.messages.value[chatId] ?: emptyList()
        assertEquals(2, msgs.size)
        val replyMsg = msgs.last()
        assertEquals(501L, replyMsg.replyToMessageId)
        assertTrue(replyMsg.replyToPreview.contains("Alice"))
        assertTrue(replyMsg.replyToPreview.contains("Are we still meeting"))
    }

    @Test
    fun testEditMessageTextUpdatesMessageAndChatSnippet() {
        val chatId = 20L
        val chat = ChatModel(chatId, "Bob", "Initial typo", "12:00", 0, ChatType.DIRECT, "B")
        val msg = MessageModel(601L, chatId, "You", "Initial typo", "12:00", true, MessageStatus.SENT)
        telegramManager.setChatsForTesting(listOf(chat))
        telegramManager.setMessagesForTesting(mapOf(chatId to listOf(msg)))

        var editSuccess = false
        telegramManager.editMessageText(chatId, 601L, "Corrected message text") { ok ->
            editSuccess = ok
        }

        assertTrue(editSuccess)
        val updatedMsg = telegramManager.messages.value[chatId]?.first()
        assertNotNull(updatedMsg)
        assertEquals("Corrected message text", updatedMsg?.text)
        assertTrue(updatedMsg?.isEdited == true)
        assertEquals("Corrected message text", telegramManager.chats.value.first().lastMessage)
    }

    @Test
    fun testToggleMessageReactionAddsAndRemovesEmoji() {
        val chatId = 30L
        val msg = MessageModel(701L, chatId, "Alice", "Great news!", "14:00", false, MessageStatus.READ)
        telegramManager.setMessagesForTesting(mapOf(chatId to listOf(msg)))

        telegramManager.toggleMessageReaction(chatId, 701L, "👍")
        var currentReactions = telegramManager.messages.value[chatId]?.first()?.reactions ?: emptyList()
        assertEquals(listOf("👍"), currentReactions)

        telegramManager.toggleMessageReaction(chatId, 701L, "❤️")
        currentReactions = telegramManager.messages.value[chatId]?.first()?.reactions ?: emptyList()
        assertEquals(listOf("👍", "❤️"), currentReactions)

        telegramManager.toggleMessageReaction(chatId, 701L, "👍")
        currentReactions = telegramManager.messages.value[chatId]?.first()?.reactions ?: emptyList()
        assertEquals(listOf("❤️"), currentReactions)
    }

    @Test
    fun testToggleChatPinAndTogglePinMessage() {
        val chat1 = ChatModel(1L, "General", "Hi", "10:00", 0, ChatType.GROUP, "GE", isPinned = false)
        val chat2 = ChatModel(2L, "Work", "Report", "10:05", 0, ChatType.GROUP, "WO", isPinned = false)
        telegramManager.setChatsForTesting(listOf(chat1, chat2))

        telegramManager.toggleChatPin(2L, currentPinned = false)
        val sortedChats = telegramManager.chats.value
        assertEquals(2L, sortedChats.first().id)
        assertTrue(sortedChats.first().isPinned)

        val msg = MessageModel(801L, 2L, "Boss", "Quarterly roadmap", "10:05", false, MessageStatus.READ)
        telegramManager.setMessagesForTesting(mapOf(2L to listOf(msg)))

        telegramManager.togglePinMessage(2L, 801L, currentPinned = false)
        assertTrue(telegramManager.messages.value[2L]?.first()?.isPinned == true)
        assertEquals(801L, telegramManager.chats.value.find { it.id == 2L }?.pinnedMessageId)
    }

    @Test
    fun testSaveAndClearChatDraft() {
        val chat = ChatModel(5L, "Charlie", "Hello", "09:00", 2, ChatType.DIRECT, "CH")
        telegramManager.setChatsForTesting(listOf(chat))

        telegramManager.saveChatDraft(5L, "Working on reply...")
        assertEquals("Working on reply...", telegramManager.getChatDraft(5L))
        assertEquals("Working on reply...", telegramManager.chats.value.first().draftText)

        telegramManager.markChatAsRead(5L)
        assertEquals(0, telegramManager.chats.value.first().unreadCount)

        telegramManager.sendMessage(5L, "Working on reply...")
        assertEquals("", telegramManager.getChatDraft(5L))
    }

    @Test
    fun testSendPhotoVideoGifDocumentAndSticker() {
        val chatId = 40L
        telegramManager.setChatsForTesting(
            listOf(ChatModel(chatId, "Media Chat", "", "11:00", 0, ChatType.DIRECT, "MC")),
        )

        telegramManager.sendPhoto(chatId, "/sdcard/DCIM/photo1.jpg", "Mountain view")
        telegramManager.sendDocument(chatId, "/sdcard/Download/manual.pdf", "")
        telegramManager.sendStickerEmoji(chatId, "🚀")
        telegramManager.sendVideo(chatId, "/sdcard/Movies/clip.mp4", 12, "Trail video")
        telegramManager.sendAnimation(chatId, "/sdcard/Download/funny.gif", "Reaction GIF")

        val msgs = telegramManager.messages.value[chatId] ?: emptyList()
        assertEquals(5, msgs.size)
        assertEquals(MediaType.PHOTO, msgs[0].mediaType)
        assertEquals("Mountain view", msgs[0].mediaCaption)
        assertEquals(MediaType.DOCUMENT, msgs[1].mediaType)
        assertEquals("manual.pdf", msgs[1].mediaCaption)
        assertEquals(MediaType.STICKER, msgs[2].mediaType)
        assertEquals("🚀", msgs[2].stickerEmoji)
        assertEquals(MediaType.VIDEO, msgs[3].mediaType)
        assertEquals(12, msgs[3].durationSeconds)
        assertEquals(MediaType.ANIMATION, msgs[4].mediaType)
        assertEquals("Reaction GIF", msgs[4].mediaCaption)
    }

    @Test
    fun testSendPollVoteAndStopPoll() {
        val chatId = 50L
        telegramManager.setChatsForTesting(
            listOf(ChatModel(chatId, "Group Poll", "", "12:00", 0, ChatType.GROUP, "GP")),
        )

        telegramManager.sendPoll(
            chatId = chatId,
            question = "Best flip phone feature?",
            options = listOf("Physical T9 Keypad", "Multi-day Battery", "Compact Clamshell"),
            isAnonymous = true,
        )

        var pollMsg = telegramManager.messages.value[chatId]?.last()
        assertNotNull(pollMsg)
        assertEquals(MediaType.POLL, pollMsg?.mediaType)
        assertEquals(3, pollMsg?.pollOptions?.size)
        assertFalse(pollMsg?.pollIsClosed == true)

        val pollId = pollMsg!!.id
        telegramManager.votePoll(chatId, pollId, 0)
        pollMsg = telegramManager.messages.value[chatId]?.last()
        assertTrue(pollMsg?.pollOptions?.get(0)?.isChosen == true)
        assertEquals(1, pollMsg?.pollOptions?.get(0)?.voterCount)
        assertEquals(100, pollMsg?.pollOptions?.get(0)?.votePercentage)
        assertEquals(1, pollMsg?.pollTotalVoters)

        telegramManager.stopPoll(chatId, pollId)
        pollMsg = telegramManager.messages.value[chatId]?.last()
        assertTrue(pollMsg?.pollIsClosed == true)
    }

    @Test
    fun testGroupMembersAdminRoleAndRemoval() {
        val chatId = 60L
        val members = telegramManager.getGroupMembers(chatId)
        assertEquals(3, members.size)
        val bob = members.find { it.name == "Bob Jones" }
        assertNotNull(bob)
        assertFalse(bob!!.isAdmin)

        telegramManager.setChatMemberAdminRole(chatId, bob.userId, makeAdmin = true)
        val updatedBob = telegramManager.getGroupMembers(chatId).find { it.userId == bob.userId }
        assertTrue(updatedBob?.isAdmin == true)
        assertEquals("Admin", updatedBob?.role)

        telegramManager.removeChatMember(chatId, bob.userId)
        assertEquals(2, telegramManager.getGroupMembers(chatId).size)
    }

    @Test
    fun testForumTopicsCreationAndSelection() {
        val chatId = 70L
        telegramManager.setChatsForTesting(
            listOf(ChatModel(chatId, "Dev Forum", "", "13:00", 0, ChatType.GROUP, "DF")),
        )

        var loadedTopicsCount = 0
        telegramManager.loadForumTopics(chatId) { topics ->
            loadedTopicsCount = topics.size
        }
        assertEquals(3, loadedTopicsCount)

        var createdTopicId = 0L
        telegramManager.createForumTopic(chatId, "Hardware Mods", "🔧") { newTopic ->
            createdTopicId = newTopic.topicId
        }
        assertTrue(createdTopicId != 0L)

        val chatAfterSelect = telegramManager.chats.value.first()
        assertTrue(chatAfterSelect.isForum)
        assertEquals(createdTopicId, chatAfterSelect.activeTopicId)
        assertEquals("Hardware Mods", chatAfterSelect.activeTopicName)
    }

    @Test
    fun testArchiveChatAndCustomChatFolders() {
        val chat1 = ChatModel(1L, "Work Project", "Deadline", "09:00", 0, ChatType.GROUP, "WP")
        val chat2 = ChatModel(2L, "Old Chat", "See ya", "08:00", 0, ChatType.DIRECT, "OC")
        telegramManager.setChatsForTesting(listOf(chat1, chat2))

        telegramManager.toggleChatArchived(2L, currentArchived = false)
        assertTrue(telegramManager.chats.value.find { it.id == 2L }?.isArchived == true)

        telegramManager.assignChatToFolder(1L, "Work")
        assertEquals("Work", telegramManager.chats.value.find { it.id == 1L }?.folderName)
        assertEquals(1, telegramManager.chatFolders.value.size)
        assertEquals("Work", telegramManager.chatFolders.value.first().title)

        telegramManager.assignChatToFolder(1L, "")
        assertEquals("", telegramManager.chats.value.find { it.id == 1L }?.folderName)
        assertTrue(telegramManager.chatFolders.value.isEmpty())
    }

    @Test
    fun testSecretChatAndSelfDestructTimer() {
        var secretChat: ChatModel? = null
        telegramManager.startSecretChat("@AliceSecret", userId = 999L) { chat, _ ->
            secretChat = chat
        }
        assertNotNull(secretChat)
        assertTrue(secretChat?.isSecretChat == true)
        assertEquals(30, secretChat?.selfDestructTimerSec)

        val secChatId = secretChat!!.id
        telegramManager.setSelfDestructTimer(secChatId, 15)
        assertEquals(15, telegramManager.chats.value.find { it.id == secChatId }?.selfDestructTimerSec)

        telegramManager.sendMessage(secChatId, "Top secret message")
        val sentMsg = telegramManager.messages.value[secChatId]?.last()
        assertEquals(15, sentMsg?.selfDestructSeconds)
    }

    @Test
    fun testOpenSavedMessagesAndCreateChats() {
        var savedChat: ChatModel? = null
        telegramManager.openSavedMessages { chat ->
            savedChat = chat
        }
        assertNotNull(savedChat)
        assertEquals("Saved Messages", savedChat?.title)
        assertTrue(savedChat?.isPinned == true)

        var resolvedBot: ChatModel? = null
        telegramManager.resolveAndCreateChat("@TeleFlipHelperBot") { chat, _ ->
            resolvedBot = chat
        }
        assertNotNull(resolvedBot)
        assertEquals(ChatType.BOT, resolvedBot?.chatType)

        var createdGroup: ChatModel? = null
        telegramManager.createGroupOrChannel("Flip Phone Enthusiasts", isChannel = false) { chat, _ ->
            createdGroup = chat
        }
        assertNotNull(createdGroup)
        assertEquals(ChatType.GROUP, createdGroup?.chatType)
        assertEquals("Flip Phone Enthusiasts", createdGroup?.title)
    }
}
