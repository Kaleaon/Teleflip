package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.ChatType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageSearchResult
import com.teleflip.app.model.MessageStatus
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class MessageSearchTest {
    private lateinit var mockContext: Context
    private lateinit var telegramManager: TelegramManager

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        val mockPrefs = mock(SharedPreferences::class.java)
        val mockEditor = mock(SharedPreferences.Editor::class.java)

        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putInt(anyString(), anyInt())).thenReturn(mockEditor)
        `when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        `when`(mockEditor.putLong(anyString(), anyLong())).thenReturn(mockEditor)

        telegramManager = TelegramManager(mockContext)

        val testChats =
            listOf(
                ChatModel(1L, "Alice Smith", "Hello", "12:00", 0, ChatType.DIRECT, "AS"),
                ChatModel(2L, "Team Channel", "Update", "12:05", 0, ChatType.CHANNEL, "TC"),
            )
        telegramManager.setChatsForTesting(testChats)
    }

    @Test
    fun testSearchMessagesOfflineTitleResolutionAndFallback() {
        val testMessages =
            mapOf(
                1L to listOf(MessageModel(101L, 1L, "Alice Smith", "hello from Alice", "12:00", false, MessageStatus.READ)),
                2L to listOf(MessageModel(102L, 2L, "Team Channel", "hello team", "12:05", false, MessageStatus.READ)),
                3L to listOf(MessageModel(103L, 3L, "Unknown User", "hello stranger", "12:10", false, MessageStatus.READ)),
            )
        telegramManager.setMessagesForTesting(testMessages)

        var searchResults: List<MessageSearchResult>? = null
        telegramManager.searchMessages("hello") { _, results, _ ->
            searchResults = results
        }

        assertNotNull(searchResults)
        assertEquals(3, searchResults!!.size)

        val resultMap = searchResults!!.associate { it.chatId to it.chatTitle }
        assertEquals("Alice Smith", resultMap[1L])
        assertEquals("Team Channel", resultMap[2L])
        assertEquals("Chat #3", resultMap[3L])
    }

    @Test
    fun testSearchChatMessagesOfflineTitleResolutionAndFallback() {
        val testMessages =
            mapOf(
                1L to listOf(MessageModel(101L, 1L, "Alice Smith", "meeting at 5", "12:00", false, MessageStatus.READ)),
                99L to listOf(MessageModel(102L, 99L, "Stranger", "meeting at 6", "12:05", false, MessageStatus.READ)),
            )
        telegramManager.setMessagesForTesting(testMessages)

        var knownResults: List<MessageSearchResult>? = null
        telegramManager.searchChatMessages(1L, "meeting") { results ->
            knownResults = results
        }
        assertNotNull(knownResults)
        assertEquals(1, knownResults!!.size)
        assertEquals("Alice Smith", knownResults!![0].chatTitle)

        var fallbackResults: List<MessageSearchResult>? = null
        telegramManager.searchChatMessages(99L, "meeting") { results ->
            fallbackResults = results
        }
        assertNotNull(fallbackResults)
        assertEquals(1, fallbackResults!!.size)
        assertEquals("Chat", fallbackResults!![0].chatTitle)
    }

    @Test
    fun testSearchMessagesOnlineTitleResolutionAndFallback() {
        val mockClient = mock(Client::class.java)
        telegramManager.setTdClientForTesting(mockClient)

        doAnswer { invocation ->
            val req = invocation.getArgument<TdApi.Function<*>>(0)
            val handler = invocation.getArgument<Client.ResultHandler>(1)
            if (req is TdApi.SearchMessages) {
                val foundMessages =
                    TdApi.FoundMessages().apply {
                        totalCount = 2
                        nextOffset = ""
                        messages =
                            arrayOf(
                                TdApi.Message().apply {
                                    id = 201L
                                    chatId = 1L
                                    date = 1600000000
                                    content =
                                        TdApi.MessageText().apply {
                                            text = TdApi.FormattedText("online search match 1", emptyArray())
                                        }
                                    isOutgoing = false
                                },
                                TdApi.Message().apply {
                                    id = 202L
                                    chatId = 888L
                                    date = 1600000000
                                    content =
                                        TdApi.MessageText().apply {
                                            text = TdApi.FormattedText("online search match 2", emptyArray())
                                        }
                                    isOutgoing = true
                                },
                            )
                    }
                handler.onResult(foundMessages)
            }
            null
        }.`when`(mockClient).send(any(), any())

        var searchResults: List<MessageSearchResult>? = null
        telegramManager.searchMessages("online") { _, results, _ ->
            searchResults = results
        }
        ShadowLooper.idleMainLooper()

        assertNotNull(searchResults)
        assertEquals(2, searchResults!!.size)

        val resultMap = searchResults!!.associate { it.chatId to it.chatTitle }
        assertEquals("Alice Smith", resultMap[1L])
        assertEquals("Chat #888", resultMap[888L])
    }

    @Test
    fun testSearchChatMessagesOnlineTitleResolutionAndFallback() {
        val mockClient = mock(Client::class.java)
        telegramManager.setTdClientForTesting(mockClient)

        doAnswer { invocation ->
            val req = invocation.getArgument<TdApi.Function<*>>(0)
            val handler = invocation.getArgument<Client.ResultHandler>(1)
            if (req is TdApi.SearchChatMessages) {
                val foundChatMessages =
                    TdApi.FoundChatMessages().apply {
                        totalCount = 1
                        messages =
                            arrayOf(
                                TdApi.Message().apply {
                                    id = 301L
                                    chatId = req.chatId
                                    date = 1600000000
                                    content =
                                        TdApi.MessageText().apply {
                                            text = TdApi.FormattedText("in chat search match", emptyArray())
                                        }
                                    isOutgoing = false
                                },
                            )
                    }
                handler.onResult(foundChatMessages)
            }
            null
        }.`when`(mockClient).send(any(), any())

        var knownResults: List<MessageSearchResult>? = null
        telegramManager.searchChatMessages(1L, "in chat") { results ->
            knownResults = results
        }
        ShadowLooper.idleMainLooper()

        assertNotNull(knownResults)
        assertEquals(1, knownResults!!.size)
        assertEquals("Alice Smith", knownResults!![0].chatTitle)

        var fallbackResults: List<MessageSearchResult>? = null
        telegramManager.searchChatMessages(777L, "in chat") { results ->
            fallbackResults = results
        }
        ShadowLooper.idleMainLooper()

        assertNotNull(fallbackResults)
        assertEquals(1, fallbackResults!!.size)
        assertEquals("Chat", fallbackResults!![0].chatTitle)
    }
}
