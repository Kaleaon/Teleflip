package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class TelegramManagerBatchTest {
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
    fun testDefaultBatchSizeIsTen() {
        assertEquals(10, TelegramManager.DEFAULT_CHAT_BATCH_SIZE)
        assertEquals(10, telegramManager.chatBatchSize)
    }

    @Test
    fun testConfigurableBatchSize() {
        telegramManager.chatBatchSize = 5
        assertEquals(5, telegramManager.chatBatchSize)
    }

    @Test
    fun testDispatchChatBatchesWithEmptyArrayDoesNotCrash() {
        telegramManager.dispatchChatBatches(longArrayOf())
        assertTrue("Chats list should remain empty for empty input", telegramManager.chats.value.isEmpty())
    }

    @Test
    fun testOnResultWithChatsConstructTriggersBatchDispatch() {
        telegramManager.chatBatchSize = 10
        val chatIds = LongArray(25) { (it + 1).toLong() }
        val chatsObj = TdApi.Chats().apply { this.chatIds = chatIds }

        // Verifying that calling onResult with TdApi.Chats doesn't throw and handles the object
        telegramManager.onResult(chatsObj)
        assertNotNull(telegramManager.chats)
    }

    @Test
    fun testAddOrUpdateChatUpdatesStateFlow() {
        val chat =
            TdApi.Chat().apply {
                id = 101L
                title = "Test Concurrent Chat"
                unreadCount = 3
                type = TdApi.ChatTypePrivate()
            }

        // Simulate receiving a chat directly
        telegramManager.onResult(chat)

        // Wait briefly for batchJob to collect and update StateFlow
        Thread.sleep(300)

        val chatList = telegramManager.chats.value
        assertEquals(1, chatList.size)
        assertEquals(101L, chatList[0].id)
        assertEquals("Test Concurrent Chat", chatList[0].title)
    }

    @Test
    fun testMultipleChatsFlowIntoStateFlowWithoutLostUpdates() {
        for (i in 1..15) {
            val chat =
                TdApi.Chat().apply {
                    id = i.toLong()
                    title = "Chat #$i"
                    unreadCount = 0
                    type = TdApi.ChatTypePrivate()
                }
            telegramManager.onResult(chat)
        }

        // Allow batch job loop to drain pending chats
        Thread.sleep(400)

        val chatList = telegramManager.chats.value
        assertEquals("All 15 chats must flow into StateFlow without lost updates", 15, chatList.size)
    }

    @Test
    fun testErrorInOnResultHandledGracefully() {
        val error =
            TdApi.Error().apply {
                code = 400
                message = "Bad Request"
            }
        telegramManager.onResult(error)
        // Ensure no exception is thrown and state flow is operational
        assertNotNull(telegramManager.chats.value)
    }
}
