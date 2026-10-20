package com.teleflip.app

import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.ChatType
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    @Test
    fun testChatModelDefaultsAndProperties() {
        val chat =
            ChatModel(
                id = 1001L,
                title = "Test Group",
                lastMessage = "Hello World",
                time = "12:00 PM",
                unreadCount = 3,
                chatType = ChatType.GROUP,
                avatarLetters = "TG",
            )

        assertEquals(1001L, chat.id)
        assertEquals("Test Group", chat.title)
        assertEquals("Hello World", chat.lastMessage)
        assertEquals("12:00 PM", chat.time)
        assertEquals(3, chat.unreadCount)
        assertEquals(ChatType.GROUP, chat.chatType)
        assertEquals("TG", chat.avatarLetters)
        assertFalse(chat.isPinned)
        assertFalse(chat.isMuted)
        assertFalse(chat.isArchived)
        assertFalse(chat.isSecretChat)
        assertFalse(chat.isForum)
        assertEquals("", chat.draftText)
        assertEquals("", chat.folderName)
        assertEquals(0L, chat.pinnedMessageId)
        assertEquals(0, chat.selfDestructTimerSec)
        assertNull(chat.avatarPath)
        assertEquals(0, chat.avatarFileId)
    }

    @Test
    fun testChatModelCopy() {
        val chat =
            ChatModel(
                id = 1L,
                title = "John",
                lastMessage = "Hey",
                time = "10:00 AM",
                unreadCount = 0,
                chatType = ChatType.DIRECT,
                avatarLetters = "J",
            )

        val updated =
            chat.copy(
                unreadCount = 5,
                isPinned = true,
                isMuted = true,
                draftText = "Hi",
                isArchived = true,
                folderName = "Work",
                isSecretChat = true,
                selfDestructTimerSec = 30,
            )
        assertEquals(1L, updated.id)
        assertEquals(5, updated.unreadCount)
        assertTrue(updated.isPinned)
        assertTrue(updated.isMuted)
        assertTrue(updated.isArchived)
        assertTrue(updated.isSecretChat)
        assertEquals("Hi", updated.draftText)
        assertEquals("Work", updated.folderName)
        assertEquals(30, updated.selfDestructTimerSec)
    }

    @Test
    fun testMessageModelDefaultsAndProperties() {
        val msg =
            MessageModel(
                id = 500L,
                chatId = 1001L,
                senderName = "Alice",
                text = "Test message",
                time = "12:01 PM",
                isOutbound = true,
            )

        assertEquals(500L, msg.id)
        assertEquals(1001L, msg.chatId)
        assertEquals("Alice", msg.senderName)
        assertEquals("Test message", msg.text)
        assertEquals("12:01 PM", msg.time)
        assertTrue(msg.isOutbound)
        assertEquals(MessageStatus.SENT, msg.status)
        assertEquals(MediaType.NONE, msg.mediaType)
        assertEquals("", msg.mediaCaption)
        assertNull(msg.mediaFilePath)
        assertEquals(0, msg.mediaFileId)
        assertEquals(0, msg.durationSeconds)
        assertEquals(0.0, msg.latitude, 0.0001)
        assertEquals(0.0, msg.longitude, 0.0001)
        assertEquals(0L, msg.replyToMessageId)
        assertEquals("", msg.replyToPreview)
        assertFalse(msg.isEdited)
        assertFalse(msg.isPinned)
        assertTrue(msg.reactions.isEmpty())
        assertEquals("", msg.forwardedFrom)
        assertEquals("", msg.pollQuestion)
        assertTrue(msg.pollOptions.isEmpty())
        assertTrue(msg.pollIsAnonymous)
        assertFalse(msg.pollIsClosed)
        assertEquals(0, msg.pollTotalVoters)
        assertEquals(0L, msg.messageThreadId)
        assertEquals(0, msg.selfDestructSeconds)
    }

    @Test
    fun testEnumValues() {
        assertEquals(4, ChatType.values().size)
        assertEquals(4, MessageStatus.values().size)
        assertEquals(9, MediaType.values().size)
    }

    @Test
    fun testMediaPickerModels() {
        val sticker =
            com.teleflip.app.model.StickerItem(
                fileId = 123,
                emoji = "🎉",
                filePath = "/tmp/sticker.webp",
                setName = "Party Pack",
            )
        assertEquals(123, sticker.fileId)
        assertEquals("🎉", sticker.emoji)
        assertEquals("/tmp/sticker.webp", sticker.filePath)
        assertEquals("Party Pack", sticker.setName)

        val set =
            com.teleflip.app.model.StickerSetModel(
                id = 456L,
                title = "Party Pack",
                stickers = listOf(sticker),
            )
        assertEquals(456L, set.id)
        assertEquals("Party Pack", set.title)
        assertEquals(1, set.stickers.size)

        val gif =
            com.teleflip.app.model.SavedGifItem(
                fileId = 789,
                filePath = "/tmp/anim.mp4",
                durationSeconds = 5,
                caption = "Applause",
                queryId = 9999L,
                resultId = "res_123",
            )
        assertEquals(789, gif.fileId)
        assertEquals("/tmp/anim.mp4", gif.filePath)
        assertEquals(5, gif.durationSeconds)
        assertEquals("Applause", gif.caption)
        assertEquals(9999L, gif.queryId)
        assertEquals("res_123", gif.resultId)
    }
}
