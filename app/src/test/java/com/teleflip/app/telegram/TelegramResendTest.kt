package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class TelegramResendTest {
    private fun createMockContext(): Context {
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)

        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.putInt(anyString(), anyInt())).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)

        return context
    }

    @Test
    fun testUpdateMessageStatus() {
        val manager = TelegramManager(createMockContext())
        val msg = MessageModel(101L, 2L, "You", "Hi", "12:00", true, MessageStatus.SENT)
        manager.setMessagesForTesting(mapOf(2L to listOf(msg)))

        manager.updateMessageStatus(2L, 101L, MessageStatus.FAILED)

        val updatedMsgs = manager.messages.value[2L] ?: emptyList()
        val updatedMsg = updatedMsgs.find { it.id == 101L }

        assertNotNull(updatedMsg)
        assertEquals(MessageStatus.FAILED, updatedMsg?.status)
    }

    @Test
    fun retryWithoutClientPreservesFailedState() {
        val manager = TelegramManager(createMockContext())
        val message = MessageModel(101L, 2L, "You", "Retry", "", true, MessageStatus.FAILED)
        manager.setMessagesForTesting(mapOf(2L to listOf(message)))
        manager.resendMessage(2L, 101L)
        assertEquals(listOf(message), manager.messages.value[2L])
        assertNotNull(manager.operationError.value)
    }

    @Test
    fun retryOfflineStillDispatchesToTdlib() {
        val manager = TelegramManager(createMockContext())
        val client = mock(org.drinkless.tdlib.Client::class.java)
        manager.setTdClientForTesting(client)
        manager.resendMessage(2L, 101L)
        val captor = org.mockito.ArgumentCaptor.forClass(org.drinkless.tdlib.TdApi.Function::class.java)
        org.mockito.Mockito.verify(client).send(captor.capture(), org.mockito.Mockito.any())
        val request = captor.value as org.drinkless.tdlib.TdApi.ResendMessages
        assertEquals(2L, request.chatId)
        org.junit.Assert.assertArrayEquals(longArrayOf(101L), request.messageIds)
        org.junit.Assert.assertTrue(manager.messages.value.isEmpty())
    }
}
