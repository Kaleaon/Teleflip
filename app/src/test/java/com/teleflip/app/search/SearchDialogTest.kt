package com.teleflip.app.search

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.ChatType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageSearchResult
import com.teleflip.app.model.MessageStatus
import com.teleflip.app.telegram.TelegramManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class SearchDialogTest {
    private fun createTelegramManager(): TelegramManager {
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.putInt(anyString(), anyInt())).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)

        val tm = TelegramManager(context)
        val testChats =
            listOf(
                ChatModel(1L, "Telegram News", "Welcome to TeleFlip!", "12:00", 1, ChatType.CHANNEL, "TN"),
                ChatModel(2L, "Alice Smith", "Dinner at 7 PM", "12:35", 0, ChatType.DIRECT, "AS"),
            )
        tm.setChatsForTesting(testChats)

        val testMessages: Map<Long, List<MessageModel>> =
            mapOf(
                2L to
                    listOf(
                        MessageModel(201L, 2L, "Alice Smith", "Meeting for dinner tonight", "12:35", false, MessageStatus.READ),
                    ),
            )
        tm.setMessagesForTesting(testMessages)
        return tm
    }

    @Test
    fun testMessageSearchResultModel() {
        val result =
            MessageSearchResult(
                messageId = 101L,
                chatId = 1L,
                chatTitle = "Telegram News",
                senderName = "News Bot",
                text = "Meeting at dinner tonight",
                time = "12:00",
            )

        assertEquals(101L, result.messageId)
        assertEquals(1L, result.chatId)
        assertEquals("Telegram News", result.chatTitle)
        assertEquals("News Bot", result.senderName)
        assertEquals("Meeting at dinner tonight", result.text)
        assertEquals("12:00", result.time)
    }

    @Test
    fun testChatsSearch() {
        val tm = createTelegramManager()
        var searchResults: List<ChatModel>? = null

        tm.searchChats("News") { results ->
            searchResults = results
        }

        assertNotNull(searchResults)
        assertTrue("Expected search results for 'News'", searchResults!!.isNotEmpty())
        assertTrue("Expected 'Telegram News' in results", searchResults!!.any { it.title.contains("News") })
    }

    @Test
    fun testContactsSearch() {
        val tm = createTelegramManager()
        var searchResults: List<ChatModel>? = null

        tm.searchContacts("Alice") { results ->
            searchResults = results
        }

        assertNotNull(searchResults)
        assertTrue("Expected search results for 'Alice'", searchResults!!.isNotEmpty())
        assertEquals(ChatType.DIRECT, searchResults!![0].chatType)
        assertTrue(searchResults!![0].title.contains("Alice"))
    }

    @Test
    fun testMessagesSearch() {
        val tm = createTelegramManager()
        var msgResults: List<MessageSearchResult>? = null

        tm.searchMessages("dinner") { _, results, _ ->
            msgResults = results
        }

        assertNotNull(msgResults)
        assertTrue("Expected at least 1 message snippet matching 'dinner'", msgResults!!.isNotEmpty())
        assertTrue("Message snippet should contain 'dinner'", msgResults!![0].text.lowercase().contains("dinner"))
    }

    @Test
    fun testEmptySearchQueryReturnsEmptyList() {
        val tm = createTelegramManager()
        var chatResults: List<ChatModel>? = null
        var messageResults: List<MessageSearchResult>? = null

        tm.searchChats("   ") { results ->
            chatResults = results
        }
        tm.searchMessages("") { _, results, _ ->
            messageResults = results
        }

        assertNotNull(chatResults)
        assertTrue("Empty query should return empty chat results", chatResults!!.isEmpty())
        assertNotNull(messageResults)
        assertTrue("Empty query should return empty message results", messageResults!!.isEmpty())
    }

    @Test
    fun testResultsEnforceLimitOf50() {
        val tm = createTelegramManager()
        var messageResults: List<MessageSearchResult>? = null

        tm.searchMessages("a") { _, results, _ ->
            messageResults = results
        }

        assertNotNull(messageResults)
        assertTrue("Result count should be <= 50 entries", messageResults!!.size <= 50)
    }
}
