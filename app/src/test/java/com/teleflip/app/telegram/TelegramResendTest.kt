package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
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
        manager.enableDemoMode()

        val chatId = 2L
        val initialMsgs = manager.messages.value[chatId] ?: emptyList()
        val targetMsg = initialMsgs.first()

        manager.updateMessageStatus(chatId, targetMsg.id, MessageStatus.FAILED)

        val updatedMsgs = manager.messages.value[chatId] ?: emptyList()
        val updatedMsg = updatedMsgs.find { it.id == targetMsg.id }

        assertNotNull(updatedMsg)
        assertEquals(MessageStatus.FAILED, updatedMsg?.status)
    }

    @Test
    fun testResendMessageInDemoMode() {
        val manager = TelegramManager(createMockContext())
        manager.enableDemoMode()

        val chatId = 2L
        manager.sendMessage(chatId, "Test resend outbound")

        val outboundMsg = manager.messages.value[chatId]?.last()
        assertNotNull(outboundMsg)
        val msgId = outboundMsg!!.id

        // Mark as FAILED
        manager.updateMessageStatus(chatId, msgId, MessageStatus.FAILED)
        assertEquals(MessageStatus.FAILED, manager.messages.value[chatId]?.find { it.id == msgId }?.status)

        // Trigger resend
        manager.resendMessage(chatId, msgId)

        // Status should immediately change to SENDING
        val sendingStatus = manager.messages.value[chatId]?.find { it.id == msgId }?.status
        assertEquals(MessageStatus.SENDING, sendingStatus)

        // Wait for demo coroutine to transition to SENT
        Thread.sleep(1200)

        val finalStatus = manager.messages.value[chatId]?.find { it.id == msgId }?.status
        assertEquals(MessageStatus.SENT, finalStatus)
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
