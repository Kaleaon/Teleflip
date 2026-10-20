package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.report.AdbReporter
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TelegramManagerBatchTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var telegramManager: TelegramManager

    @Before
    fun setUp() {
        AdbReporter.resetForTest()
        val tempLogFile = java.io.File.createTempFile("test_teleflip_batch_", ".log")
        tempLogFile.deleteOnExit()
        AdbReporter.setLogFile(tempLogFile)
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

    @Test
    fun testContinuousDispatchWithSemaphoreConcurrencyLimit() {
        val mockClient = mock(Client::class.java)
        val activeCount = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val completedLatch = CountDownLatch(50)

        doAnswer { invocation ->
            val query = invocation.getArgument<TdApi.Function<*>>(0)
            val handler = invocation.getArgument<Client.ResultHandler>(1)

            if (query is TdApi.GetChat) {
                val current = activeCount.incrementAndGet()
                maxConcurrent.accumulateAndGet(current) { prev, curr -> maxOf(prev, curr) }

                Thread {
                    try {
                        Thread.sleep((10..25).random().toLong())
                        val chat =
                            TdApi.Chat().apply {
                                id = query.chatId
                                title = "Chat ${query.chatId}"
                                type = TdApi.ChatTypePrivate()
                            }
                        activeCount.decrementAndGet()
                        handler.onResult(chat)
                    } finally {
                        completedLatch.countDown()
                    }
                }.start()
            } else if (query is TdApi.GetUser) {
                handler.onResult(TdApi.User().apply { id = query.userId })
            } else {
                handler.onResult(TdApi.Ok())
            }
            null
        }.`when`(mockClient).send(any(), any())

        telegramManager.setTdClientForTesting(mockClient)

        val chatIds = LongArray(50) { (it + 1).toLong() }
        telegramManager.dispatchChatBatches(chatIds, batchSize = 10)

        assertTrue("All 50 chat requests should complete within timeout", completedLatch.await(5, TimeUnit.SECONDS))
        assertTrue("Max active concurrent GetChat TDLib calls (${maxConcurrent.get()}) must not exceed batchSize (10)", maxConcurrent.get() <= 10)

        // Wait briefly for StateFlow update
        Thread.sleep(400)
        assertEquals(50, telegramManager.chats.value.size)
    }

    @Test
    fun testHeadOfLineBlockingEliminatedForStalledQuery() {
        val mockClient = mock(Client::class.java)
        val completionTimes = ConcurrentHashMap<Long, Long>()
        val completedLatch = CountDownLatch(20)

        doAnswer { invocation ->
            val query = invocation.getArgument<TdApi.Function<*>>(0)
            val handler = invocation.getArgument<Client.ResultHandler>(1)

            if (query is TdApi.GetChat) {
                Thread {
                    try {
                        val id = query.chatId
                        if (id == 1L) {
                            // Chat 1 stalls for 400ms
                            Thread.sleep(400)
                        } else {
                            // Other chats complete quickly in 10ms
                            Thread.sleep(10)
                        }
                        completionTimes[id] = System.currentTimeMillis()
                        val chat =
                            TdApi.Chat().apply {
                                this.id = id
                                title = "Chat $id"
                                type = TdApi.ChatTypePrivate()
                            }
                        handler.onResult(chat)
                    } finally {
                        completedLatch.countDown()
                    }
                }.start()
            } else if (query is TdApi.GetUser) {
                handler.onResult(TdApi.User().apply { id = query.userId })
            } else {
                handler.onResult(TdApi.Ok())
            }
            null
        }.`when`(mockClient).send(any(), any())

        telegramManager.setTdClientForTesting(mockClient)

        val chatIds = LongArray(20) { (it + 1).toLong() }
        telegramManager.dispatchChatBatches(chatIds, batchSize = 10)

        assertTrue("All 20 chat requests should complete within timeout", completedLatch.await(5, TimeUnit.SECONDS))

        val chat1Time = completionTimes[1L] ?: Long.MAX_VALUE
        val chat15Time = completionTimes[15L] ?: Long.MAX_VALUE

        // In continuous Semaphore pipeline, chat 15 finishes long before stalled chat 1 finishes!
        assertTrue("Chat 15 should complete before stalled Chat 1 completes (chat15: $chat15Time, chat1: $chat1Time)", chat15Time < chat1Time)
    }

    @Test
    fun testTimeoutAndExceptionHandlingContinuesPipeline() {
        val mockClient = mock(Client::class.java)
        val completedLatch = CountDownLatch(5)

        doAnswer { invocation ->
            val query = invocation.getArgument<TdApi.Function<*>>(0)
            val handler = invocation.getArgument<Client.ResultHandler>(1)

            if (query is TdApi.GetChat) {
                Thread {
                    try {
                        when (query.chatId) {
                            1L -> {
                                // Timeout simulation: pass null
                                handler.onResult(null)
                            }
                            2L -> {
                                // TDLib Error response
                                handler.onResult(TdApi.Error(400, "Chat not found"))
                            }
                            else -> {
                                handler.onResult(
                                    TdApi.Chat().apply {
                                        id = query.chatId
                                        title = "Chat ${query.chatId}"
                                        type = TdApi.ChatTypePrivate()
                                    },
                                )
                            }
                        }
                    } finally {
                        completedLatch.countDown()
                    }
                }.start()
            } else if (query is TdApi.GetUser) {
                handler.onResult(TdApi.User().apply { id = query.userId })
            } else {
                handler.onResult(TdApi.Ok())
            }
            null
        }.`when`(mockClient).send(any(), any())

        telegramManager.setTdClientForTesting(mockClient)

        val chatIds = LongArray(5) { (it + 1).toLong() }
        telegramManager.dispatchChatBatches(chatIds, batchSize = 2)

        assertTrue("All 5 requests should complete despite errors/timeouts", completedLatch.await(5, TimeUnit.SECONDS))

        // Give state flow batching loop time to process valid responses
        Thread.sleep(300)
        val validChats = telegramManager.chats.value
        assertEquals("3 valid chats (IDs 3, 4, 5) should be added", 3, validChats.size)
        assertTrue(validChats.none { it.id == 1L || it.id == 2L })

        // Ensure logs are flushed to disk before querying AdbReporter
        AdbReporter.flush()
        val recentLogs = AdbReporter.getRecentLogs(50)
        assertTrue(
            "Recent logs should record timeout or error: $recentLogs",
            recentLogs.any { it.contains("Timeout fetching chat 1 in batch chunk") || it.contains("Failed to fetch chat 2") },
        )
    }
}
