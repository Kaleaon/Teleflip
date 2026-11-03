package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.model.MessageStatus
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class TelegramMessageLifecycleTest {
    private lateinit var manager: TelegramManager

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
        manager = TelegramManager(context)
    }

    private fun message(
        id: Long,
        chat: Long = 10L,
        state: TdApi.MessageSendingState? = null,
    ) = TdApi.Message().apply {
        this.id = id
        chatId = chat
        isOutgoing = true
        sendingState = state
        content = TdApi.MessageText().apply { text = TdApi.FormattedText("hello", emptyArray()) }
    }

    @Test
    fun repeatedUpdatesUpsertButIdenticalTextWithDifferentIdsStaysDistinct() {
        val first = message(101L, state = TdApi.MessageSendingStatePending())
        manager.onResult(TdApi.UpdateNewMessage(first))
        manager.onResult(TdApi.UpdateNewMessage(first))
        manager.onResult(TdApi.UpdateNewMessage(message(102L, state = TdApi.MessageSendingStatePending())))
        assertEquals(listOf(101L, 102L), manager.messages.value[10L]?.map { it.id })
        assertTrue(manager.messages.value[10L]!!.all { it.status == MessageStatus.SENDING })
    }

    @Test
    fun successfulSendReplacesPendingIdAndReadReceiptUsesFinalId() {
        manager.onResult(TdApi.UpdateNewMessage(message(101L, state = TdApi.MessageSendingStatePending())))
        manager.onResult(TdApi.UpdateMessageSendSucceeded(message(201L), 101L))
        manager.onResult(TdApi.UpdateMessageSendSucceeded(message(201L), 101L))
        manager.onResult(TdApi.UpdateNewMessage(message(201L)))
        assertEquals(listOf(201L), manager.messages.value[10L]?.map { it.id })
        assertEquals(MessageStatus.SENT, manager.messages.value[10L]?.single()?.status)
        manager.onResult(TdApi.UpdateChatReadOutbox(10L, 201L))
        assertEquals(MessageStatus.READ, manager.messages.value[10L]?.single()?.status)
        manager.handleMessagesListForTesting(arrayOf(message(201L)))
        assertEquals(MessageStatus.READ, manager.messages.value[10L]?.single()?.status)
    }

    @Test
    fun failureReplacesOldIdAndKeepsAuthoritativeError() {
        manager.onResult(TdApi.UpdateNewMessage(message(101L, state = TdApi.MessageSendingStatePending())))
        val error = TdApi.Error(400, "CHAT_WRITE_FORBIDDEN")
        val failed = message(102L, state = TdApi.MessageSendingStateFailed().apply { this.error = error })
        manager.onResult(TdApi.UpdateMessageSendFailed(failed, 101L, error))
        assertEquals(102L, manager.messages.value[10L]?.single()?.id)
        assertEquals(MessageStatus.FAILED, manager.messages.value[10L]?.single()?.status)
        assertTrue(manager.operationError.value!!.contains(error.message))
    }

    @Test
    fun staleSendCallbackCannotRestorePendingBubbleAfterSuccess() {
        val client = mock(Client::class.java)
        manager.setTdClientForTesting(client)
        manager.sendMessage(10L, "hello")
        val request = ArgumentCaptor.forClass(TdApi.Function::class.java)
        val handler = ArgumentCaptor.forClass(Client.ResultHandler::class.java)
        verify(client, atLeastOnce()).send(request.capture(), handler.capture())
        val sendHandler = handler.allValues[request.allValues.indexOfFirst { it is TdApi.SendMessage }]
        manager.onResult(TdApi.UpdateNewMessage(message(101L, state = TdApi.MessageSendingStatePending())))
        manager.onResult(TdApi.UpdateMessageSendSucceeded(message(201L), 101L))
        sendHandler.onResult(message(101L, state = TdApi.MessageSendingStatePending()))
        assertEquals(listOf(201L), manager.messages.value[10L]?.map { it.id })
    }

    @Test
    fun oldPendingHistoryCannotRestoreReplacedMessage() {
        val pending = message(101L, state = TdApi.MessageSendingStatePending())
        manager.onResult(TdApi.UpdateNewMessage(pending))
        manager.onResult(TdApi.UpdateMessageSendSucceeded(message(201L), 101L))
        manager.handleMessagesListForTesting(arrayOf(pending))
        manager.onResult(TdApi.UpdateNewMessage(pending))
        assertEquals(listOf(201L), manager.messages.value[10L]?.map { it.id })
    }

    @Test
    fun reconciliationIsScopedToChat() {
        manager.onResult(TdApi.UpdateNewMessage(message(101L, 10L)))
        manager.onResult(TdApi.UpdateNewMessage(message(101L, 20L)))
        manager.onResult(TdApi.UpdateMessageSendSucceeded(message(201L, 10L), 101L))
        assertEquals(201L, manager.messages.value[10L]?.single()?.id)
        assertEquals(101L, manager.messages.value[20L]?.single()?.id)
    }
}
