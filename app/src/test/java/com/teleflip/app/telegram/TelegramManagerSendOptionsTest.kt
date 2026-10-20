package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class TelegramManagerSendOptionsTest {
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
    fun testSendMessageDefaultOptions() {
        val manager = TelegramManager(mockContext)
        manager.setTdClientForTesting(mockClient)

        manager.sendMessage(chatId = 123L, text = "Hello World")

        val captor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        verify(mockClient, atLeastOnce()).send(captor.capture(), any())

        val sendReq = captor.allValues.filterIsInstance<TdApi.SendMessage>().first()
        assertNotNull("options should not be null", sendReq.options)
        assertEquals(false, sendReq.options.disableNotification)
        assertNull("schedulingState should be null for immediate delivery", sendReq.options.schedulingState)
    }

    @Test
    fun testSendMessageSendSilently() {
        val manager = TelegramManager(mockContext)
        manager.setTdClientForTesting(mockClient)

        manager.sendMessage(
            chatId = 123L,
            text = "Silent Message",
            disableNotification = true,
        )

        val captor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        verify(mockClient, atLeastOnce()).send(captor.capture(), any())

        val sendReq = captor.allValues.filterIsInstance<TdApi.SendMessage>().first()
        assertNotNull("options should not be null", sendReq.options)
        assertEquals(true, sendReq.options.disableNotification)
        assertNull(sendReq.options.schedulingState)
    }

    @Test
    fun testSendMessageScheduledFutureDate() {
        val manager = TelegramManager(mockContext)
        manager.setTdClientForTesting(mockClient)

        val futureSec = (System.currentTimeMillis() / 1000).toInt() + 3600 // 1 hour in future
        val schedulingState = TdApi.MessageSchedulingStateSendAtDate(futureSec, 0)

        manager.sendMessage(
            chatId = 123L,
            text = "Future Scheduled Message",
            schedulingState = schedulingState,
        )

        val captor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        verify(mockClient, atLeastOnce()).send(captor.capture(), any())

        val sendReq = captor.allValues.filterIsInstance<TdApi.SendMessage>().first()
        assertNotNull("options should not be null", sendReq.options)
        val optionsSched = sendReq.options.schedulingState
        assertNotNull("schedulingState should be present for future schedule", optionsSched)
        assertTrue(optionsSched is TdApi.MessageSchedulingStateSendAtDate)
        val sendAtDate = optionsSched as TdApi.MessageSchedulingStateSendAtDate
        assertEquals(futureSec, sendAtDate.sendDate)
    }

    @Test
    fun testSendMessageScheduledPastDateFallbackToImmediate() {
        val manager = TelegramManager(mockContext)
        manager.setTdClientForTesting(mockClient)

        val pastSec = (System.currentTimeMillis() / 1000).toInt() - 600 // 10 minutes in past
        val schedulingState = TdApi.MessageSchedulingStateSendAtDate(pastSec, 0)

        manager.sendMessage(
            chatId = 123L,
            text = "Past Scheduled Message",
            schedulingState = schedulingState,
        )

        val captor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        verify(mockClient, atLeastOnce()).send(captor.capture(), any())

        val sendReq = captor.allValues.filterIsInstance<TdApi.SendMessage>().first()
        assertNotNull("options should not be null", sendReq.options)
        assertNull(
            "Past scheduled date must fall back to immediate delivery (null schedulingState)",
            sendReq.options.schedulingState,
        )
    }

    @Test
    fun testSendPhotoAndDocumentWithOptions() {
        val manager = TelegramManager(mockContext)
        manager.setTdClientForTesting(mockClient)

        val futureSec = (System.currentTimeMillis() / 1000).toInt() + 1800
        val schedulingState = TdApi.MessageSchedulingStateSendAtDate(futureSec, 0)

        manager.sendPhoto(
            chatId = 456L,
            filePath = "/tmp/test.jpg",
            caption = "Test Photo",
            disableNotification = true,
            schedulingState = schedulingState,
        )

        val captor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        verify(mockClient, atLeastOnce()).send(captor.capture(), any())

        val sendReq = captor.allValues.filterIsInstance<TdApi.SendMessage>().first()
        assertNotNull(sendReq.options)
        assertEquals(true, sendReq.options.disableNotification)
        assertNotNull(sendReq.options.schedulingState)
        val photoSched = sendReq.options.schedulingState as TdApi.MessageSchedulingStateSendAtDate
        assertEquals(futureSec, photoSched.sendDate)
    }
}
