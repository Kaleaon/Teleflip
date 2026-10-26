package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageStatus
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TelegramManagerHistoryTest {
    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockClient: Client
    private lateinit var telegramManager: TelegramManager

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockPrefs = mock(SharedPreferences::class.java)
        mockClient = mock(Client::class.java)

        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        telegramManager = TelegramManager(mockContext)
        telegramManager.setTdClientForTesting(mockClient)
    }

    @Test
    fun testImportChatHistoryFromCloudDeduplicatesAndSortsByMessageId() = runBlocking {
        val chatId = 555L

        // Initial messages in state flow: IDs 10 and 30 (unordered or existing)
        val initialMessages = listOf(
            MessageModel(
                id = 30L,
                chatId = chatId,
                senderName = "Alice",
                text = "Message 30",
                time = "10:00",
                isOutbound = false,
                status = MessageStatus.READ,
                mediaType = MediaType.NONE,
            ),
            MessageModel(
                id = 10L,
                chatId = chatId,
                senderName = "Alice",
                text = "Message 10",
                time = "09:00",
                isOutbound = false,
                status = MessageStatus.READ,
                mediaType = MediaType.NONE,
            ),
        )
        telegramManager.setMessagesForTesting(mapOf(chatId to initialMessages))

        // Create TDLib messages: ID 10 (duplicate), ID 20 (new), ID 40 (new)
        val tdMsg10 = TdApi.Message().apply {
            id = 10L
            this.chatId = chatId
            date = 1000
            isOutgoing = false
            content = TdApi.MessageText().apply {
                text = TdApi.FormattedText("Message 10 cloud", emptyArray())
            }
        }
        val tdMsg20 = TdApi.Message().apply {
            id = 20L
            this.chatId = chatId
            date = 2000
            isOutgoing = false
            content = TdApi.MessageText().apply {
                text = TdApi.FormattedText("Message 20 cloud", emptyArray())
            }
        }
        val tdMsg40 = TdApi.Message().apply {
            id = 40L
            this.chatId = chatId
            date = 4000
            isOutgoing = false
            content = TdApi.MessageText().apply {
                text = TdApi.FormattedText("Message 40 cloud", emptyArray())
            }
        }

        // Mock TDLib send call for GetChatHistory
        doAnswer { invocation ->
            val query = invocation.getArgument<TdApi.Function<*>>(0)
            val handler = invocation.getArgument<Client.ResultHandler>(1)
            if (query is TdApi.GetChatHistory) {
                handler.onResult(TdApi.Messages(3, arrayOf(tdMsg40, tdMsg20, tdMsg10)))
            }
            null
        }.`when`(mockClient).send(any(), any())

        var reportedLoaded = 0
        val result = telegramManager.importChatHistoryFromCloud(
            chatId = chatId,
            autoDownloadMedia = false,
            onProgress = { loaded, _ ->
                reportedLoaded = loaded
            },
        )

        assertEquals(3, result.first)
        assertEquals(3, reportedLoaded)

        val updatedList = telegramManager.messages.value[chatId]
        assertNotNull(updatedList)
        assertEquals("List should contain 4 unique messages after deduplication", 4, updatedList!!.size)

        // Verify ID sorting
        val expectedIds = listOf(10L, 20L, 30L, 40L)
        val actualIds = updatedList.map { it.id }
        assertEquals("Messages must be strictly sorted by ID", expectedIds, actualIds)
    }

    @Test
    fun testChatExportProgressDialogDispatchesToMainThread() {
        val appContext = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val dialog = com.teleflip.app.ui.ChatExportProgressDialog(appContext) {}
        val progress = com.teleflip.app.storage.ChatExportProgress(
            stage = com.teleflip.app.storage.ExportStage.FETCHING_CLOUD_HISTORY,
            messagesProcessed = 100,
            totalMessages = 100,
            statusText = "Loading...",
        )

        // Invoke updateProgress from a background thread to verify safety
        var threadException: Exception? = null
        val thread = Thread {
            try {
                dialog.updateProgress(progress)
            } catch (e: Exception) {
                threadException = e
            }
        }
        thread.start()
        thread.join()

        org.junit.Assert.assertNull("updateProgress should not throw when called from background thread", threadException)
    }
}
