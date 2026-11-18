package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.ChatType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class MultiPinBannerTest {
    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var telegramManager: TelegramManager

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockPrefs = mock(SharedPreferences::class.java)
        mockEditor = mock(SharedPreferences.Editor::class.java)

        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putInt(anyString(), anyInt())).thenReturn(mockEditor)
        `when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        `when`(mockEditor.putLong(anyString(), anyLong())).thenReturn(mockEditor)

        telegramManager = TelegramManager(mockContext)
    }

    @Test
    fun testChatModelEffectivePinnedMessageIds() {
        val chatLegacy = ChatModel(1L, "Test", "Hi", "12:00", 0, ChatType.GROUP, "T", pinnedMessageId = 101L)
        assertEquals(listOf(101L), chatLegacy.getEffectivePinnedMessageIds())

        val chatMulti = ChatModel(2L, "Test2", "Hi", "12:00", 0, ChatType.GROUP, "T", pinnedMessageIds = listOf(201L, 202L, 203L))
        assertEquals(listOf(201L, 202L, 203L), chatMulti.getEffectivePinnedMessageIds())

        val chatEmpty = ChatModel(3L, "Test3", "Hi", "12:00", 0, ChatType.GROUP, "T")
        assertTrue(chatEmpty.getEffectivePinnedMessageIds().isEmpty())
    }

    @Test
    fun testMultiplePinMessagesKeepsPreviousPins() {
        val chatId = 100L
        val chat = ChatModel(chatId, "Dev Group", "Welcome", "10:00", 0, ChatType.GROUP, "DG")
        val msg1 = MessageModel(801L, chatId, "User1", "First Pin", "10:01", false, MessageStatus.READ)
        val msg2 = MessageModel(802L, chatId, "User2", "Second Pin", "10:02", false, MessageStatus.READ)
        val msg3 = MessageModel(803L, chatId, "User3", "Third Pin", "10:03", false, MessageStatus.READ)

        telegramManager.setChatsForTesting(listOf(chat))
        telegramManager.setMessagesForTesting(mapOf(chatId to listOf(msg1, msg2, msg3)))

        // Pin first message
        telegramManager.togglePinMessage(chatId, 801L, currentPinned = false)
        var updatedChat = telegramManager.chats.value.first { it.id == chatId }
        assertEquals(listOf(801L), updatedChat.getEffectivePinnedMessageIds())
        assertTrue(telegramManager.messages.value[chatId]?.first { it.id == 801L }?.isPinned == true)

        // Pin second message - first pin must remain!
        telegramManager.togglePinMessage(chatId, 802L, currentPinned = false)
        updatedChat = telegramManager.chats.value.first { it.id == chatId }
        assertEquals(listOf(801L, 802L), updatedChat.getEffectivePinnedMessageIds())
        assertTrue(telegramManager.messages.value[chatId]?.first { it.id == 801L }?.isPinned == true)
        assertTrue(telegramManager.messages.value[chatId]?.first { it.id == 802L }?.isPinned == true)

        // Pin third message - previous pins remain!
        telegramManager.togglePinMessage(chatId, 803L, currentPinned = false)
        updatedChat = telegramManager.chats.value.first { it.id == chatId }
        assertEquals(listOf(801L, 802L, 803L), updatedChat.getEffectivePinnedMessageIds())
        assertTrue(telegramManager.messages.value[chatId]?.first { it.id == 801L }?.isPinned == true)
        assertTrue(telegramManager.messages.value[chatId]?.first { it.id == 802L }?.isPinned == true)
        assertTrue(telegramManager.messages.value[chatId]?.first { it.id == 803L }?.isPinned == true)
    }

    @Test
    fun testUnpinSpecificMessagePreservesOthers() {
        val chatId = 200L
        val chat = ChatModel(chatId, "Team", "Msg", "10:00", 0, ChatType.GROUP, "TM")
        val msg1 = MessageModel(901L, chatId, "Alice", "Announcement 1", "10:01", false, MessageStatus.READ)
        val msg2 = MessageModel(902L, chatId, "Bob", "Announcement 2", "10:02", false, MessageStatus.READ)

        telegramManager.setChatsForTesting(listOf(chat))
        telegramManager.setMessagesForTesting(mapOf(chatId to listOf(msg1, msg2)))

        telegramManager.togglePinMessage(chatId, 901L, currentPinned = false)
        telegramManager.togglePinMessage(chatId, 902L, currentPinned = false)

        var updatedChat = telegramManager.chats.value.first { it.id == chatId }
        assertEquals(listOf(901L, 902L), updatedChat.getEffectivePinnedMessageIds())

        // Unpin msg1 (901L)
        telegramManager.togglePinMessage(chatId, 901L, currentPinned = true)
        updatedChat = telegramManager.chats.value.first { it.id == chatId }
        assertEquals(listOf(902L), updatedChat.getEffectivePinnedMessageIds())
        assertFalse(telegramManager.messages.value[chatId]?.first { it.id == 901L }?.isPinned == true)
        assertTrue(telegramManager.messages.value[chatId]?.first { it.id == 902L }?.isPinned == true)
    }
}
