package com.teleflip.app.search

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teleflip.app.R
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.ChatType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageSearchResult
import com.teleflip.app.model.MessageStatus
import com.teleflip.app.telegram.TelegramManager
import com.teleflip.app.ui.SearchDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Shadows
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class SearchDialogTest {
    private fun <T> anyKotlin(): T {
        org.mockito.Mockito.any<T>()
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

    private fun <T> eqKotlin(obj: T): T {
        org.mockito.Mockito.eq(obj)
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

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

    @Test
    fun testDpadLeftRightDoNotSwitchTabsWhenSearchInputHasFocus() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tm = createTelegramManager()
        val dialog = SearchDialog(context, tm, {}, { _, _ -> })
        dialog.show()

        val etSearchInput = dialog.findViewById<EditText>(R.id.et_search_input)
        assertTrue("Search input should have focus initially", etSearchInput.hasFocus())
        assertEquals(SearchDialog.SearchTab.CHATS, dialog.currentTab)

        val dpadRightDown = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, dpadRightDown)
        assertEquals("Tab should not change when search input has focus", SearchDialog.SearchTab.CHATS, dialog.currentTab)

        val dpadLeftDown = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0)
        dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, dpadLeftDown)
        assertEquals("Tab should not change when search input has focus", SearchDialog.SearchTab.CHATS, dialog.currentTab)

        dialog.dismiss()
    }

    @Test
    fun testDpadLeftRightSwitchTabsWhenSearchInputDoesNotHaveFocus() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tm = createTelegramManager()
        val dialog = SearchDialog(context, tm, {}, { _, _ -> })
        dialog.show()

        val etSearchInput = dialog.findViewById<EditText>(R.id.et_search_input)
        val tabChats = dialog.findViewById<android.view.View>(R.id.tab_search_chats)

        tabChats.isFocusableInTouchMode = true
        tabChats.requestFocus()
        etSearchInput.clearFocus()
        assertFalse("Search input should not have focus", etSearchInput.hasFocus())
        assertEquals(SearchDialog.SearchTab.CHATS, dialog.currentTab)

        val dpadRightDown = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, dpadRightDown)
        assertEquals("DPAD_RIGHT should switch tab to CONTACTS when input lacks focus", SearchDialog.SearchTab.CONTACTS, dialog.currentTab)

        dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, dpadRightDown)
        assertEquals("DPAD_RIGHT should switch tab to MESSAGES when input lacks focus", SearchDialog.SearchTab.MESSAGES, dialog.currentTab)

        val dpadLeftDown = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0)
        dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, dpadLeftDown)
        assertEquals("DPAD_LEFT should switch tab back to CONTACTS when input lacks focus", SearchDialog.SearchTab.CONTACTS, dialog.currentTab)

        dialog.dismiss()
    }

    @Test
    fun testDpadRightCommitsMultiTapWhenSearchInputHasFocus() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tm = createTelegramManager()
        val dialog = SearchDialog(context, tm, {}, { _, _ -> })
        dialog.show()

        val etSearchInput = dialog.findViewById<EditText>(R.id.et_search_input)
        assertTrue("Search input should have focus", etSearchInput.hasFocus())

        // Key '2' -> 'A' (CAP_FIRST mode)
        val key2Down = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2, 0)
        dialog.onKeyDown(KeyEvent.KEYCODE_2, key2Down)
        assertEquals("A", etSearchInput.text.toString())

        // KEYCODE_DPAD_RIGHT commits 'A' manually
        val dpadRightDown = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0)
        dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, dpadRightDown)

        // Key '2' again -> inserts new char 'a' instead of cycling 'A' to 'b'
        dialog.onKeyDown(KeyEvent.KEYCODE_2, key2Down)
        assertEquals("Aa", etSearchInput.text.toString())
        assertEquals(SearchDialog.SearchTab.CHATS, dialog.currentTab)

        dialog.dismiss()
    }

    @Test
    fun testSearchInputDebouncesBy300ms() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.setTheme(R.style.Theme_TeleFlip)
        var searchCallCount = 0
        var lastSearchQuery = ""

        val tm = mock(TelegramManager::class.java)
        doAnswer { invocation ->
            searchCallCount++
            lastSearchQuery = invocation.getArgument(0) as String
            @Suppress("UNCHECKED_CAST")
            val callback = invocation.getArgument(1) as (List<ChatModel>) -> Unit
            callback(emptyList())
            null
        }.`when`(tm).searchChats(anyString(), anyKotlin())

        val dialog = SearchDialog(context, tm, {}, { _, _ -> })
        dialog.show()

        val input = dialog.findViewById<EditText>(R.id.et_search_input)
        input.setText("News")

        // Before 300ms delay: no search should be dispatched
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals("Search should not be dispatched before 300ms", 0, searchCallCount)

        // After 300ms delay total: search should be dispatched
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        assertEquals("Search should be dispatched after 300ms", 1, searchCallCount)
        assertEquals("News", lastSearchQuery)

        dialog.dismiss()
    }

    @Test
    fun testRapidTypingCancelsPendingDebouncedJob() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.setTheme(R.style.Theme_TeleFlip)
        val searchedQueries = mutableListOf<String>()

        val tm = mock(TelegramManager::class.java)
        doAnswer { invocation ->
            val q = invocation.getArgument(0) as String
            searchedQueries.add(q)
            @Suppress("UNCHECKED_CAST")
            val callback = invocation.getArgument(1) as (List<ChatModel>) -> Unit
            callback(emptyList())
            null
        }.`when`(tm).searchChats(anyString(), anyKotlin())

        val dialog = SearchDialog(context, tm, {}, { _, _ -> })
        dialog.show()

        val input = dialog.findViewById<EditText>(R.id.et_search_input)

        // Rapid keypresses
        input.setText("N")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

        input.setText("Ne")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

        input.setText("News")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

        // No queries dispatched yet
        assertTrue("Intermediate queries should be debounced", searchedQueries.isEmpty())

        // Wait until 300ms after last keypress
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))

        assertEquals("Only final query should be dispatched", 1, searchedQueries.size)
        assertEquals("News", searchedQueries.first())

        dialog.dismiss()
    }

    @Test
    fun testStaleSearchResponsesAreDropped() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.setTheme(R.style.Theme_TeleFlip)

        var pendingCallback: ((List<ChatModel>) -> Unit)? = null
        var secondCallback: ((List<ChatModel>) -> Unit)? = null

        val tm = mock(TelegramManager::class.java)
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            pendingCallback = invocation.getArgument(1) as (List<ChatModel>) -> Unit
            null
        }.`when`(tm).searchChats(eqKotlin("Query1"), anyKotlin())

        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            secondCallback = invocation.getArgument(1) as (List<ChatModel>) -> Unit
            null
        }.`when`(tm).searchChats(eqKotlin("Query2"), anyKotlin())

        val dialog = SearchDialog(context, tm, {}, { _, _ -> })
        dialog.show()

        val input = dialog.findViewById<EditText>(R.id.et_search_input)
        val progressBar = dialog.findViewById<ProgressBar>(R.id.progress_search)

        // First query
        input.setText("Query1")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        assertNotNull("Callback for Query1 should be captured", pendingCallback)

        // Second query before first callback returns
        input.setText("Query2")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        assertNotNull("Callback for Query2 should be captured", secondCallback)

        // Now invoke first (stale) callback
        val staleResult = listOf(ChatModel(99L, "Stale Chat", "Old msg", "10:00", 0, ChatType.DIRECT, "SC"))
        pendingCallback?.invoke(staleResult)

        // Progress bar should still be visible because Query2 is active and hasn't finished
        assertEquals(View.VISIBLE, progressBar.visibility)

        // Now invoke second callback
        val validResult = listOf(ChatModel(100L, "Valid Chat", "New msg", "10:05", 0, ChatType.DIRECT, "VC"))
        secondCallback?.invoke(validResult)

        // Progress bar should now be GONE
        assertEquals(View.GONE, progressBar.visibility)

        dialog.dismiss()
    }

    @Test
    fun testClearingInputResetsResultsAndHidesSpinnerImmediately() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.setTheme(R.style.Theme_TeleFlip)

        val tm = createTelegramManager()
        val dialog = SearchDialog(context, tm, {}, { _, _ -> })
        dialog.show()

        val input = dialog.findViewById<EditText>(R.id.et_search_input)
        val progressBar = dialog.findViewById<ProgressBar>(R.id.progress_search)
        val emptyLayout = dialog.findViewById<View>(R.id.layout_search_empty)
        val emptyTitle = dialog.findViewById<TextView>(R.id.tv_search_empty_title)

        // Type search term and wait for search to complete
        input.setText("News")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))

        assertEquals(View.GONE, progressBar.visibility)
        assertEquals(View.GONE, emptyLayout.visibility)

        // Clear search input
        input.setText("")

        // Immediately (0ms delay) UI should reset
        assertEquals(View.GONE, progressBar.visibility)
        assertEquals(View.VISIBLE, emptyLayout.visibility)
        assertEquals("Type a keyword to search", emptyTitle.text.toString())

        dialog.dismiss()
    }

    @Test
    fun testDismissCommitsCharacterInSearchDialog() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.setTheme(R.style.Theme_TeleFlip)

        val tm = createTelegramManager()
        val dialog = SearchDialog(context, tm, {}, { _, _ -> })
        dialog.show()

        val input = dialog.findViewById<EditText>(R.id.et_search_input)
        assertTrue("Search input should have focus", input.hasFocus())

        val key2Down = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2, 0)
        dialog.onKeyDown(KeyEvent.KEYCODE_2, key2Down)
        assertEquals("A", input.text.toString())

        val backEvent = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
        dialog.onKeyDown(KeyEvent.KEYCODE_BACK, backEvent)

        assertEquals("A", input.text.toString())
    }
}
