package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class TelegramManagerSpeechTest {
    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var mockClient: Client

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockPrefs = mock(SharedPreferences::class.java)
        mockEditor = mock(SharedPreferences.Editor::class.java)
        mockClient = mock(Client::class.java)

        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putInt(anyString(), anyInt())).thenReturn(mockEditor)
        `when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        `when`(mockEditor.putLong(anyString(), anyLong())).thenReturn(mockEditor)
    }

    @Test
    fun testUpdateMessageTranscript() {
        val manager = TelegramManager(mockContext)
        val chatId = 100L
        val msgId = 1L
        val msg = MessageModel(
            id = msgId,
            chatId = chatId,
            senderName = "Contact",
            text = "🎤 Voice Message (5s)",
            time = "12:00",
            isOutbound = false,
            mediaType = MediaType.VOICE,
            durationSeconds = 5,
        )

        manager.setMessagesForTesting(mapOf(chatId to listOf(msg)))
        assertEquals("", manager.messages.value[chatId]?.first()?.transcript)

        manager.updateMessageTranscript(chatId, msgId, "Hello world this is a test transcript")

        val updated = manager.messages.value[chatId]?.first()
        assertEquals("Hello world this is a test transcript", updated?.transcript)
    }

    @Test
    fun testRecognizeSpeechDispatchesTdApiQuery() {
        val manager = TelegramManager(mockContext)
        manager.setTdClientForTesting(mockClient)

        val chatId = 200L
        val messageId = 55L

        manager.recognizeSpeech(chatId, messageId)

        val functionCaptor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        val handlerCaptor = ArgumentCaptor.forClass(Client.ResultHandler::class.java)
        verify(mockClient, timeout(1000)).send(functionCaptor.capture(), handlerCaptor.capture())

        val query = functionCaptor.value
        assertTrue("Query should be instance of RecognizeSpeech", query is TdApi.RecognizeSpeech)
        val recognizeQuery = query as TdApi.RecognizeSpeech
        assertEquals(chatId, recognizeQuery.chatId)
        assertEquals(messageId, recognizeQuery.messageId)

        // Simulate TdApi.Ok response from TDLib
        handlerCaptor.value.onResult(TdApi.Ok())
    }

    @Test
    fun testRecognizeSpeechErrorHandling() {
        val manager = TelegramManager(mockContext)
        manager.setTdClientForTesting(mockClient)

        val chatId = 200L
        val messageId = 55L

        manager.recognizeSpeech(chatId, messageId)

        val functionCaptor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        val handlerCaptor = ArgumentCaptor.forClass(Client.ResultHandler::class.java)
        verify(mockClient, timeout(1000)).send(functionCaptor.capture(), handlerCaptor.capture())

        // Simulate TdApi.Error response
        handlerCaptor.value.onResult(TdApi.Error(400, "VOICE_RECOGNITION_DISABLED"))
    }
}
