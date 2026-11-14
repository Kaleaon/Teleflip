package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.model.ChatType
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class AutoDeleteTimerTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var telegramManager: TelegramManager

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        prefs = mock(SharedPreferences::class.java)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
        telegramManager = TelegramManager(context)
    }

    @Test
    fun testAddOrUpdateChatInitializesSelfDestructTimerSecFromMessageAutoDeleteTime() {
        val chat =
            TdApi.Chat().apply {
                id = 201L
                title = "Auto-Delete Direct Chat"
                unreadCount = 0
                type = TdApi.ChatTypePrivate()
                messageAutoDeleteTime = 86400 // 24 Hours
            }

        telegramManager.onResult(chat)
        Thread.sleep(300)

        val chatList = telegramManager.chats.value
        assertEquals(1, chatList.size)
        assertEquals(201L, chatList[0].id)
        assertEquals(86400, chatList[0].selfDestructTimerSec)
    }

    @Test
    fun testAddOrUpdateGroupChatInitializesSelfDestructTimerSec() {
        val chat =
            TdApi.Chat().apply {
                id = 202L
                title = "Auto-Delete Group Chat"
                unreadCount = 0
                type = TdApi.ChatTypeBasicGroup()
                messageAutoDeleteTime = 604800 // 7 Days
            }

        telegramManager.onResult(chat)
        Thread.sleep(300)

        val chatList = telegramManager.chats.value
        assertEquals(1, chatList.size)
        assertEquals(202L, chatList[0].id)
        assertEquals(ChatType.GROUP, chatList[0].chatType)
        assertEquals(604800, chatList[0].selfDestructTimerSec)
    }

    @Test
    fun testInboundUpdateChatMessageAutoDeleteTimeUpdatesStateFlow() {
        val chat =
            TdApi.Chat().apply {
                id = 301L
                title = "Channel Chat"
                unreadCount = 0
                type = TdApi.ChatTypeSupergroup().apply { isChannel = true }
                messageAutoDeleteTime = 0
            }

        telegramManager.onResult(chat)
        Thread.sleep(300)

        assertEquals(0, telegramManager.chats.value.find { it.id == 301L }?.selfDestructTimerSec)

        val update = TdApi.UpdateChatMessageAutoDeleteTime(301L, 2592000) // 30 Days
        telegramManager.onResult(update)

        val updatedChat = telegramManager.chats.value.find { it.id == 301L }
        assertNotNull(updatedChat)
        assertEquals(2592000, updatedChat?.selfDestructTimerSec)
    }

    @Test
    fun testDirectInvocationHandleChatMessageAutoDeleteTimeUpdated() {
        val chat =
            TdApi.Chat().apply {
                id = 401L
                title = "Direct Auto-Delete Test"
                unreadCount = 0
                type = TdApi.ChatTypePrivate()
                messageAutoDeleteTime = 0
            }

        telegramManager.onResult(chat)
        Thread.sleep(300)

        telegramManager.handleChatMessageAutoDeleteTimeUpdated(401L, 86400)

        val updatedChat = telegramManager.chats.value.find { it.id == 401L }
        assertNotNull(updatedChat)
        assertEquals(86400, updatedChat?.selfDestructTimerSec)
    }
}
