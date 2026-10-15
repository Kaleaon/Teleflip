package com.teleflip.app

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.ChatType
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageStatus
import com.teleflip.app.telegram.TelegramManager
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito

class SubscriberCacheTest {
    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences

    @Before
    fun setUp() {
        mockContext = Mockito.mock(Context::class.java)
        mockPrefs = Mockito.mock(SharedPreferences::class.java)
        val mockEditor = Mockito.mock(SharedPreferences.Editor::class.java)

        Mockito.`when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        Mockito.`when`(mockPrefs.edit()).thenReturn(mockEditor)
        Mockito.`when`(mockPrefs.getInt(anyString(), anyInt())).thenReturn(0)
        Mockito.`when`(mockPrefs.getString(anyString(), Mockito.nullable(String::class.java))).thenReturn(null)
    }

    @Test
    fun testChatModelAndMessageModelDefaults() {
        val chat =
            ChatModel(
                id = 100L,
                title = "Test User",
                lastMessage = "Hello",
                time = "12:00",
                unreadCount = 0,
                chatType = ChatType.DIRECT,
                avatarLetters = "TU",
            )
        assertFalse("Default isPremium should be false", chat.isPremium)

        val premiumChat = chat.copy(isPremium = true)
        assertTrue("isPremium should be true when set", premiumChat.isPremium)

        val msg =
            MessageModel(
                id = 1L,
                chatId = 100L,
                senderName = "Test User",
                text = "",
                time = "12:00",
                isOutbound = false,
                mediaType = MediaType.STICKER,
                stickerEmoji = "🎉",
                isPremiumSticker = true,
            )
        assertEquals("🎉", msg.stickerEmoji)
        assertTrue(msg.isPremiumSticker)
    }

    @Test
    fun testSubscriberCacheAndUserUpdates() {
        val manager = TelegramManager(mockContext)

        val chat = ChatModel(2L, "Alice Smith", "Hi", "12:00", 0, ChatType.DIRECT, "AS", isPremium = true)
        manager.setChatsForTesting(listOf(chat))
        val aliceChat = manager.chats.value.find { it.id == 2L }
        assertTrue("Alice Smith should be premium", aliceChat?.isPremium == true)

        // Test updating user subscriber cache via updateUserCache
        val testUser =
            TdApi.User().apply {
                id = 555L
                isPremium = true
            }
        manager.updateUserCache(testUser)
        assertTrue("userSubscriberCache should store true for user 555", manager.userSubscriberCache[555L] == true)

        // Test UpdateUser processing
        val nonPremiumUser =
            TdApi.User().apply {
                id = 666L
                isPremium = false
            }
        val updateUser = TdApi.UpdateUser(nonPremiumUser)
        manager.onResult(updateUser)
        assertEquals("userSubscriberCache should store false for user 666", false, manager.userSubscriberCache[666L])
    }

    @Test
    fun testStickerMessageModel() {
        val manager = TelegramManager(mockContext)
        val stickerMsg =
            MessageModel(
                id = 203L,
                chatId = 2L,
                senderName = "Alice Smith",
                text = "Sticker",
                time = "12:37",
                isOutbound = false,
                status = MessageStatus.READ,
                mediaType = MediaType.STICKER,
                stickerEmoji = "🎉",
                isPremiumSticker = true,
            )
        manager.setMessagesForTesting(mapOf(2L to listOf(stickerMsg)))

        val messages = manager.messages.value[2L]
        assertTrue("Messages should be present", !messages.isNullOrEmpty())
        val found = messages?.find { it.mediaType == MediaType.STICKER }
        assertTrue("Sticker message should be present", found != null)
        assertEquals("🎉", found?.stickerEmoji)
        assertTrue("Sticker message should be marked premium", found?.isPremiumSticker == true)
    }
}
