package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.model.ActiveCall
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.ChatType
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

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
        val user =
            TdApi.User().apply {
                id = 555L
                phoneNumber = "+15551234567"
                isPremium = true
            }

        telegramManager.handleUserUpdate(user)

        assertEquals("+15551234567", telegramManager.getUserPhoneNumber(555L))
        assertEquals(555L, telegramManager.getDirectChatUserId(555L))
    }

    @Test
    fun testInitiateTelegramCallWithoutActiveClient() {
        var callResultSuccess: Boolean? = null
        var errorMessage: String? = null
        telegramManager.initiateTelegramCall(2L) { success, msg ->
            callResultSuccess = success
            errorMessage = msg
        }
        assertEquals(false, callResultSuccess)
        assertEquals("TDLib client is not available", errorMessage)
    }

    @Test
    fun testForwardMessageWithoutActiveClient() {
        var forwardSuccess: Boolean? = null
        telegramManager.forwardMessage(fromChatId = 2L, toChatId = 3L, messageId = 201L) { success ->
            forwardSuccess = success
        }
        assertEquals(false, forwardSuccess)
    }

    @Test
    fun testForwardMessageWithFlagsAndActiveClient() {
        val mockClient = mock(Client::class.java)
        telegramManager.setTdClientForTesting(mockClient)

        // Test default parameters
        telegramManager.forwardMessage(fromChatId = 2L, toChatId = 3L, messageId = 201L)

        val captor1 = ArgumentCaptor.forClass(TdApi.Function::class.java)
        verify(mockClient, atLeastOnce()).send(captor1.capture(), Mockito.any())

        val forwardReq1 = captor1.allValues.filterIsInstance<TdApi.ForwardMessages>().first()
        assertEquals(3L, forwardReq1.chatId)
        assertEquals(2L, forwardReq1.fromChatId)
        assertEquals(1, forwardReq1.messageIds.size)
        assertEquals(201L, forwardReq1.messageIds[0])
        assertFalse("sendCopy should be false by default", forwardReq1.sendCopy)
        assertFalse("removeCaption should be false by default", forwardReq1.removeCaption)

        // Test custom parameters: sendCopy = true, removeCaption = true
        val mockClient2 = mock(Client::class.java)
        telegramManager.setTdClientForTesting(mockClient2)

        telegramManager.forwardMessage(
            fromChatId = 2L,
            toChatId = 4L,
            messageId = 301L,
            sendCopy = true,
            removeCaption = true,
        )

        val captor2 = ArgumentCaptor.forClass(TdApi.Function::class.java)
        verify(mockClient2, atLeastOnce()).send(captor2.capture(), Mockito.any())

        val forwardReq2 = captor2.allValues.filterIsInstance<TdApi.ForwardMessages>().first()
        assertEquals(4L, forwardReq2.chatId)
        assertEquals(2L, forwardReq2.fromChatId)
        assertEquals(301L, forwardReq2.messageIds[0])
        assertTrue("sendCopy should be true when passed", forwardReq2.sendCopy)
        assertTrue("removeCaption should be true when passed", forwardReq2.removeCaption)
    }

    @Test
    fun testTelegramToSComplianceMethods() {
        // Calling ToS methods without active native client should be safe and no-op without crashing
        telegramManager.openChat(1L)
        telegramManager.closeChat(1L)
        telegramManager.markMessagesAsRead(1L, longArrayOf(101L))
        telegramManager.sendTypingAction(1L)
        telegramManager.cancelTypingAction(1L)
        telegramManager.loadSponsoredMessages(1L)
        telegramManager.clickSponsoredMessage(1L, 999L)
        telegramManager.downloadFilePriority(123)
    }

    @Test
    fun testCallingLifecycleAndAudioState() {
        val initialCall =
            ActiveCall(
                callId = 12345,
                userId = 2L,
                userName = "Alice Smith",
                isOutgoing = true,
                state = com.teleflip.app.model.CallState.READY,
                durationSec = 0,
            )
        telegramManager.setActiveCallForTesting(initialCall)

        val active = telegramManager.activeCall.value
        assertNotNull("Active call should be present", active)
        assertEquals(2L, active?.userId)

        telegramManager.updateCallAudioState(isMuted = true, isSpeakerOn = true)
        val mutedActive = telegramManager.activeCall.value
        assertTrue("Microphone should be muted", mutedActive?.isMuted == true)
        assertTrue("Speaker should be on", mutedActive?.isSpeakerOn == true)

        telegramManager.updateCallDuration(35)
        assertEquals(35, telegramManager.activeCall.value?.durationSec)

        var discardSuccess = false
        telegramManager.discardCall(active?.callId ?: 0, durationSec = 35) { success, _ ->
            discardSuccess = success
        }
        assertTrue("Call discard should succeed", discardSuccess)
        assertEquals(com.teleflip.app.model.CallState.DISCARDED, telegramManager.activeCall.value?.state)
    }

    @Test
    fun testHandleCallUpdateStateMapping() {
        val user =
            TdApi.User().apply {
                id = 777L
                firstName = "Alice"
                lastName = "Tester"
            }
        telegramManager.updateUserCache(user)

        val callPending =
            TdApi.Call().apply {
                id = 101
                userId = 777L
                isOutgoing = false
                state = TdApi.CallStatePending()
            }
        telegramManager.handleCallUpdate(callPending)

        var current = telegramManager.activeCall.value
        assertNotNull("Active call should be set", current)
        assertEquals(com.teleflip.app.model.CallState.PENDING, current?.state)
        assertEquals("Alice Tester", current?.userName)
        assertEquals(false, current?.isOutgoing)

        val callReady =
            TdApi.Call().apply {
                id = 101
                userId = 777L
                isOutgoing = false
                state = TdApi.CallStateReady()
            }
        telegramManager.handleCallUpdate(callReady)
        current = telegramManager.activeCall.value
        assertEquals(com.teleflip.app.model.CallState.READY, current?.state)

        val callDiscarded =
            TdApi.Call().apply {
                id = 101
                userId = 777L
                isOutgoing = false
                state = TdApi.CallStateDiscarded()
            }
        telegramManager.handleCallUpdate(callDiscarded)
        current = telegramManager.activeCall.value
        assertEquals(com.teleflip.app.model.CallState.DISCARDED, current?.state)
    }

    @Test
    fun testLoadChatProfileFallback() {
        val chat = ChatModel(1L, "Telegram News", "News", "12:00", 0, ChatType.CHANNEL, "TN", isPremium = true)
        telegramManager.setChatsForTesting(listOf(chat))

        var profileLoaded: com.teleflip.app.model.ChatProfile? = null
        telegramManager.loadChatProfile(1L) { profile ->
            profileLoaded = profile
        }

        Thread.sleep(300)
        assertNotNull("Profile should be loaded via fallback", profileLoaded)
        assertEquals(1L, profileLoaded?.chatId)
        assertEquals("Telegram News", profileLoaded?.title)
        assertEquals("Details unavailable", profileLoaded?.statusText)
    }

    @Test
    fun testToggleChatMuteWithoutActiveClient() {
        var toggledResult: Boolean? = null
        telegramManager.toggleChatMute(1L, currentMuted = false) { newMuted ->
            toggledResult = newMuted
        }
        assertEquals(false, toggledResult)

        telegramManager.toggleChatMute(1L, currentMuted = true) { newMuted ->
            toggledResult = newMuted
        }
        assertEquals(true, toggledResult)
    }
}
