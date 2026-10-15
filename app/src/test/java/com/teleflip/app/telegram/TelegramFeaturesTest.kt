package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.model.ChatType
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock

class TelegramFeaturesTest {

    private lateinit var telegramManager: TelegramManager

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        Mockito.`when`(context.getSharedPreferences("teleflip_prefs", Context.MODE_PRIVATE)).thenReturn(prefs)
        telegramManager = TelegramManager(context)
    }

    @Test
    fun testUserPhoneCacheAndCallingHelpers() {
        val user = TdApi.User().apply {
            id = 555L
            phoneNumber = "+15551234567"
            isPremium = true
        }

        telegramManager.handleUserUpdate(user)

        assertEquals("+15551234567", telegramManager.getUserPhoneNumber(555L))
        assertEquals(555L, telegramManager.getDirectChatUserId(555L))
    }

    @Test
    fun testInitiateTelegramCallDemoMode() {
        telegramManager.enableDemoMode()
        var callResultSuccess = false
        telegramManager.initiateTelegramCall(2L) { success, _ ->
            callResultSuccess = success
        }
        assertTrue("Telegram call in demo mode should succeed", callResultSuccess)
    }

    @Test
    fun testForwardMessageDemoMode() {
        telegramManager.enableDemoMode()

        var forwardSuccess = false
        // Chat 2 has message 201L in demo mode: "Hey, does your flip phone have Telegram now?"
        telegramManager.forwardMessage(fromChatId = 2L, toChatId = 3L, messageId = 201L) { success ->
            forwardSuccess = success
        }

        assertTrue("Forward message should succeed in demo mode", forwardSuccess)
        val chat3Msgs = telegramManager.messages.value[3L] ?: emptyList()
        val forwarded = chat3Msgs.find { it.text.contains("Forwarded") }
        assertNotNull("Forwarded message should appear in target chat", forwarded)
        assertTrue("Forwarded message should contain original text", forwarded?.text?.contains("Hey, does your flip phone") == true)
    }

    @Test
    fun testTelegramToSComplianceMethods() {
        telegramManager.enableDemoMode()

        // Calling ToS methods in demo mode should be safe and no-op without exception
        telegramManager.openChat(1L)
        telegramManager.closeChat(1L)
        telegramManager.markMessagesAsRead(1L, longArrayOf(101L))
        telegramManager.sendTypingAction(1L)
        telegramManager.cancelTypingAction(1L)
        telegramManager.loadSponsoredMessages(1L)
        telegramManager.clickSponsoredMessage(1L, 999L)
        telegramManager.downloadFilePriority(123)
    }
}
