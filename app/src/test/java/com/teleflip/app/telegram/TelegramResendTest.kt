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
    fun testResendMessageTransitionsToSending() {
        val manager = TelegramManager(createMockContext())
        manager.setConnectionStateForTesting("Ready")

        val chatId = 2L
        val msg = MessageModel(101L, chatId, "You", "Test resend outbound", "12:00", true, MessageStatus.FAILED)
        manager.setMessagesForTesting(mapOf(chatId to listOf(msg)))

        manager.resendMessage(chatId, 101L)

        val chatMsgs = manager.messages.value[chatId] ?: emptyList()
        val resendMsg = chatMsgs.find { it.id == 101L }
        assertNotNull(resendMsg)
        assertEquals(MessageStatus.SENDING, resendMsg?.status)
    }

    @Test
    fun testResendMessageOffline() {
        val manager = TelegramManager(createMockContext())
        // Default connectionState is "Offline"

        val chatId = 2L
        manager.sendMessage(chatId, "Offline message")

        val outboundMsg = manager.messages.value[chatId]?.last()
        assertNotNull(outboundMsg)
        val msgId = outboundMsg!!.id

        manager.updateMessageStatus(chatId, msgId, MessageStatus.FAILED)

        // Trigger resend when offline
        manager.resendMessage(chatId, msgId)

        val finalStatus = manager.messages.value[chatId]?.find { it.id == msgId }?.status
        assertEquals(MessageStatus.FAILED, finalStatus)
    }
}
