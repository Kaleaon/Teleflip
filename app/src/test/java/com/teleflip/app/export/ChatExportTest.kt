package com.teleflip.app.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.teleflip.app.telegram.TelegramManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChatExportTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testEscapeHtml() {
        val raw = "<script>alert('xss & \"hello\"')</script>"
        val escaped = ChatExportFormatter.escapeHtml(raw)
        assertEquals("&lt;script&gt;alert(&#39;xss &amp; &quot;hello&quot;&#39;)&lt;/script&gt;", escaped)
    }

    @Test
    fun testFormatToJson_validStructureAndContents() {
        val messages =
            listOf(
                ExportMessage(
                    messageId = 101L,
                    timestamp = 1700000000L,
                    dateFormatted = "2026-10-19 08:00:00",
                    senderId = 1L,
                    senderName = "Alice",
                    text = "Hello world!",
                    attachmentType = null,
                    attachmentDetails = null,
                    isOutbound = false,
                ),
                ExportMessage(
                    messageId = 102L,
                    timestamp = 1700000060L,
                    dateFormatted = "2026-10-19 08:01:00",
                    senderId = 2L,
                    senderName = "Me",
                    text = "Hi Alice, look at this photo",
                    attachmentType = "Photo",
                    attachmentDetails = "1080x1920",
                    isOutbound = true,
                ),
            )

        val jsonStr = ChatExportFormatter.formatToJson(12345L, "Test Chat", messages, "2026-10-19 08:30:00")
        assertNotNull(jsonStr)

        val jsonObj = JSONObject(jsonStr)
        assertEquals(12345L, jsonObj.getLong("chat_id"))
        assertEquals("Test Chat", jsonObj.getString("chat_title"))
        assertEquals("2026-10-19 08:30:00", jsonObj.getString("exported_at"))
        assertEquals(2, jsonObj.getInt("message_count"))

        val array = jsonObj.getJSONArray("messages")
        assertEquals(2, array.length())

        val msg1 = array.getJSONObject(0)
        assertEquals(101L, msg1.getLong("id"))
        assertEquals("Alice", msg1.getString("sender_name"))
        assertEquals("Hello world!", msg1.getString("text"))
        assertFalse(msg1.getBoolean("is_outbound"))
        assertTrue(msg1.isNull("attachment_type"))

        val msg2 = array.getJSONObject(1)
        assertEquals(102L, msg2.getLong("id"))
        assertEquals("Me", msg2.getString("sender_name"))
        assertTrue(msg2.getBoolean("is_outbound"))
        assertEquals("Photo", msg2.getString("attachment_type"))
        assertEquals("1080x1920", msg2.getString("attachment_details"))
    }

    @Test
    fun testFormatToHtml_validStructureAndEscaping() {
        val messages =
            listOf(
                ExportMessage(
                    messageId = 201L,
                    timestamp = 1700000000L,
                    dateFormatted = "2026-10-19 08:00:00",
                    senderId = 1L,
                    senderName = "Bob & Alice <Tag>",
                    text = "Line 1\nLine 2 & <More>",
                    attachmentType = "Document",
                    attachmentDetails = "report.pdf",
                    isOutbound = false,
                ),
            )

        val htmlStr = ChatExportFormatter.formatToHtml(999L, "Group <Test>", messages, "2026-10-19 08:30:00")
        assertNotNull(htmlStr)

        assertTrue(htmlStr.contains("<!DOCTYPE html>"))
        assertTrue(htmlStr.contains("<title>Chat Export - Group &lt;Test&gt;</title>"))
        assertTrue(htmlStr.contains("Chat Export: Group &lt;Test&gt;"))
        assertTrue(htmlStr.contains("Bob &amp; Alice &lt;Tag&gt;"))
        assertTrue(htmlStr.contains("Line 1<br>Line 2 &amp; &lt;More&gt;"))
        assertTrue(htmlStr.contains("📎 [Document]: report.pdf"))
        assertTrue(htmlStr.contains("2026-10-19 08:00:00"))
    }

    @Test
    fun testParseMessage_variousTypes() {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        // Text Message
        val textMsg = TdApi.Message()
        textMsg.id = 1L
        textMsg.date = 1700000000
        textMsg.senderId = TdApi.MessageSenderUser(100L)
        textMsg.isOutgoing = false
        textMsg.content = TdApi.MessageText(TdApi.FormattedText("Sample Text", emptyArray()), null, null)

        val parsedText = ChatExportEngine.parseMessage(textMsg, "Alice", dateFormat)
        assertEquals(1L, parsedText.messageId)
        assertEquals(100L, parsedText.senderId)
        assertEquals("Alice", parsedText.senderName)
        assertEquals("Sample Text", parsedText.text)
        assertNull(parsedText.attachmentType)

        // Photo Message
        val photoMsg = TdApi.Message()
        photoMsg.id = 2L
        photoMsg.date = 1700000010
        photoMsg.senderId = TdApi.MessageSenderUser(101L)
        photoMsg.isOutgoing = true
        val photoSize = TdApi.PhotoSize()
        photoSize.width = 1280
        photoSize.height = 720
        val photo = TdApi.Photo()
        photo.sizes = arrayOf(photoSize)
        val photoContent = TdApi.MessagePhoto()
        photoContent.photo = photo
        photoContent.caption = TdApi.FormattedText("Caption Text", emptyArray())
        photoMsg.content = photoContent

        val parsedPhoto = ChatExportEngine.parseMessage(photoMsg, "Bob", dateFormat)
        assertEquals("Caption Text", parsedPhoto.text)
        assertEquals("Photo", parsedPhoto.attachmentType)
        assertEquals("1280x720", parsedPhoto.attachmentDetails)
        assertTrue(parsedPhoto.isOutbound)

        // Poll Message
        val pollMsg = TdApi.Message()
        pollMsg.id = 3L
        pollMsg.date = 1700000020
        pollMsg.senderId = TdApi.MessageSenderChat(555L)
        val poll = TdApi.Poll()
        poll.question = TdApi.FormattedText("Which option?", emptyArray())
        poll.options = arrayOf(TdApi.PollOption(), TdApi.PollOption())
        val pollContent = TdApi.MessagePoll()
        pollContent.poll = poll
        pollMsg.content = pollContent

        val parsedPoll = ChatExportEngine.parseMessage(pollMsg, "Channel", dateFormat)
        assertEquals("Which option?", parsedPoll.text)
        assertEquals("Poll", parsedPoll.attachmentType)
        assertEquals("2 options", parsedPoll.attachmentDetails)
    }

    @Test
    fun testExportChatHistory_incrementalAndBothFormats(): Unit =
        runBlocking {
            val mockManager = mock(TelegramManager::class.java)

            val msg1 = TdApi.Message()
            msg1.id = 1000L
            msg1.date = 1700000000
            msg1.senderId = TdApi.MessageSenderUser(10L)
            msg1.content = TdApi.MessageText(TdApi.FormattedText("First Message", emptyArray()), null, null)

            val msg2 = TdApi.Message()
            msg2.id = 900L
            msg2.date = 1700000060
            msg2.senderId = TdApi.MessageSenderUser(20L)
            msg2.content = TdApi.MessageText(TdApi.FormattedText("Second Message", emptyArray()), null, null)

            `when`(mockManager.getChatHistoryBatch(999L, 0L, 100)).thenReturn(listOf(msg1, msg2))
            `when`(mockManager.getChatHistoryBatch(999L, 900L, 100)).thenReturn(emptyList())
            `when`(mockManager.resolveSenderName(org.mockito.ArgumentMatchers.any())).thenReturn("Alice")

            val completionDeferred = CompletableDeferred<Result<List<File>>>()
            var progressCount = 0

            val job =
                ChatExportEngine.exportChatHistory(
                    context = context,
                    scope = this,
                    telegramManager = mockManager,
                    chatId = 999L,
                    chatTitle = "Unit Test Chat",
                    format = ExportFormat.BOTH,
                    mainDispatcher = Dispatchers.Unconfined,
                    onProgress = { count ->
                        progressCount = count
                    },
                    onComplete = { res ->
                        completionDeferred.complete(res)
                    },
                )

            job.join()
            val result = completionDeferred.await()
            assertTrue(result.isSuccess)

            val files = result.getOrNull()!!
            assertEquals(2, files.size)

            val jsonFile = files.find { it.name.endsWith(".json") }
            val htmlFile = files.find { it.name.endsWith(".html") }

            assertNotNull(jsonFile)
            assertNotNull(htmlFile)

            assertTrue(jsonFile!!.exists())
            assertTrue(htmlFile!!.exists())

            val jsonText = jsonFile.readText()
            assertTrue(jsonText.contains("First Message"))
            assertTrue(jsonText.contains("Second Message"))

            val htmlText = htmlFile.readText()
            assertTrue(htmlText.contains("First Message"))
            assertTrue(htmlText.contains("Second Message"))

            // Cleanup test files
            jsonFile.delete()
            htmlFile.delete()
        }

    @Test
    fun testExportChatHistory_highVolumeMessages(): Unit =
        runBlocking {
            val mockManager = mock(TelegramManager::class.java)

            val batchCount = 10 // 10 batches of 100 = 1,000 messages
            val fakeBatches = mutableMapOf<Long, List<TdApi.Message>>()

            var currentMsgId = 10000L
            var fromId = 0L

            for (b in 0 until batchCount) {
                val batch = mutableListOf<TdApi.Message>()
                for (i in 0 until 100) {
                    currentMsgId--
                    val msg = TdApi.Message()
                    msg.id = currentMsgId
                    msg.date = (1700000000 + (b * 100) + i)
                    msg.senderId = TdApi.MessageSenderUser(1L)
                    msg.content = TdApi.MessageText(TdApi.FormattedText("Msg #$currentMsgId in batch $b", emptyArray()), null, null)
                    batch.add(msg)
                }
                fakeBatches[fromId] = batch
                fromId = batch.last().id
            }
            fakeBatches[fromId] = emptyList()

            for ((fId, list) in fakeBatches) {
                `when`(mockManager.getChatHistoryBatch(888L, fId, 100)).thenReturn(list)
            }
            `when`(mockManager.resolveSenderName(org.mockito.ArgumentMatchers.any())).thenReturn("User1")

            val completionDeferred = CompletableDeferred<Result<List<File>>>()
            var maxProgress = 0

            val testScope = CoroutineScope(Dispatchers.IO)
            val job =
                ChatExportEngine.exportChatHistory(
                    context = context,
                    scope = testScope,
                    telegramManager = mockManager,
                    chatId = 888L,
                    chatTitle = "High Volume Chat",
                    format = ExportFormat.JSON,
                    mainDispatcher = Dispatchers.Unconfined,
                    onProgress = { count ->
                        maxProgress = count
                    },
                    onComplete = { res ->
                        completionDeferred.complete(res)
                    },
                )

            job.join()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val result = completionDeferred.await()
            if (result.isFailure) {
                println("HighVolume test failure: " + result.exceptionOrNull()?.message)
                result.exceptionOrNull()?.printStackTrace()
            }
            assertTrue(result.isSuccess)

            val files = result.getOrNull()!!
            assertEquals(1, files.size)
            assertEquals(1000, maxProgress)

            val exportedJson = files[0].readText()
            val jsonObj = JSONObject(exportedJson)
            assertEquals(1000, jsonObj.getInt("message_count"))

            // Cleanup
            files[0].delete()
        }

    @Test
    fun testExportChatHistory_cancellationPreventsCorruptFiles(): Unit =
        runBlocking {
            val mockManager = mock(TelegramManager::class.java)

            val msg1 = TdApi.Message()
            msg1.id = 500L
            msg1.date = 1700000000
            msg1.senderId = TdApi.MessageSenderUser(1L)
            msg1.content = TdApi.MessageText(TdApi.FormattedText("Msg 1", emptyArray()), null, null)

            val batchStarted = CountDownLatch(1)
            val releaseBatch = CountDownLatch(1)
            `when`(mockManager.getChatHistoryBatch(777L, 0L, 100)).thenAnswer {
                batchStarted.countDown()
                check(releaseBatch.await(5, TimeUnit.SECONDS)) { "Test did not release the history read" }
                listOf(msg1)
            }
            `when`(mockManager.resolveSenderName(msg1.senderId)).thenReturn("User1")

            val completionDeferred = CompletableDeferred<Result<List<File>>>()

            val testScope = CoroutineScope(Dispatchers.IO)
            val exportJob =
                ChatExportEngine.exportChatHistory(
                    context = context,
                    scope = testScope,
                    telegramManager = mockManager,
                    chatId = 777L,
                    chatTitle = "Cancelled Export",
                    format = ExportFormat.BOTH,
                    mainDispatcher = Dispatchers.Unconfined,
                    onProgress = {},
                    onComplete = { res ->
                        completionDeferred.complete(res)
                    },
                )

            // Cancel while a history read is in progress, before it can finish exporting.
            assertTrue("History read must start", batchStarted.await(5, TimeUnit.SECONDS))
            exportJob.cancel()
            releaseBatch.countDown()
            exportJob.join()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

            val result = kotlinx.coroutines.withTimeout(5000) { completionDeferred.await() }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is CancellationException)
        }
}
