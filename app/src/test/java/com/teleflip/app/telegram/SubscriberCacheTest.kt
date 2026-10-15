package com.teleflip.app.telegram

import android.content.Context
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.ChatType
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock

class SubscriberCacheTest {
    private lateinit var telegramManager: TelegramManager

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        val prefs = mock(android.content.SharedPreferences::class.java)
        Mockito.`when`(context.getSharedPreferences("teleflip_prefs", Context.MODE_PRIVATE)).thenReturn(prefs)
        telegramManager = TelegramManager(context)
    }

    @Test
    fun testChatModelAndMessageModelDefaultsAndProperties() {
        val chatDefault =
            ChatModel(
                id = 100L,
                title = "Test User",
                lastMessage = "Hello",
                time = "12:00",
                unreadCount = 0,
                chatType = ChatType.DIRECT,
                avatarLetters = "TU",
            )
        assertFalse("Default ChatModel isPremium should be false", chatDefault.isPremium)

        val chatPremium = chatDefault.copy(isPremium = true)
        assertTrue("ChatModel isPremium should be true when initialized", chatPremium.isPremium)

        val msgDefault =
            MessageModel(
                id = 1L,
                chatId = 100L,
                senderName = "Test User",
                text = "Hi",
                time = "12:01",
                isOutbound = false,
            )
        assertEquals("Default stickerEmoji should be empty", "", msgDefault.stickerEmoji)
        assertFalse("Default isPremiumSticker should be false", msgDefault.isPremiumSticker)

        val msgSticker =
            msgDefault.copy(
                mediaType = MediaType.STICKER,
                stickerEmoji = "🎉",
                isPremiumSticker = true,
            )
        assertEquals("Sticker emoji should match", "🎉", msgSticker.stickerEmoji)
        assertTrue("isPremiumSticker should be true", msgSticker.isPremiumSticker)
    }

    @Test
    fun testUserUpdateUpdatesCacheAndChatList() {
        val user =
            TdApi.User().apply {
                id = 200L
                firstName = "Premium"
                lastName = "Subscriber"
                isPremium = true
            }

        telegramManager.handleUserUpdate(user)

        assertTrue("User ID 200 should be cached as Premium", telegramManager.subscriberCache[200L] == true)
    }

    @Test
    fun testDemoModeIncludesPremiumContactsAndStickers() {
        telegramManager.enableDemoMode()

        val chats = telegramManager.chats.value
        val aliceChat = chats.find { it.id == 2L }
        assertTrue("Alice Smith in demo mode should be premium subscriber", aliceChat?.isPremium == true)

        val messages = telegramManager.messages.value[2L] ?: emptyList()
        val stickerMsg = messages.find { it.mediaType == MediaType.STICKER }
        assertTrue("Sticker message should exist in demo chat", stickerMsg != null)
        assertEquals("Sticker emoji should be '🎉'", "🎉", stickerMsg?.stickerEmoji)
    }
}
