package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
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

    @Test
    fun testCallingLifecycleDemoMode() {
        telegramManager.enableDemoMode()

        var callInitiated = false
        telegramManager.initiateTelegramCall(2L) { success, _ ->
            callInitiated = success
        }
        assertTrue("Call initiation should succeed in demo mode", callInitiated)

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
    fun testLoadChatProfileDemoMode() {
        telegramManager.enableDemoMode()

        var profileLoaded: com.teleflip.app.model.ChatProfile? = null
        telegramManager.loadChatProfile(1L) { profile ->
            profileLoaded = profile
        }

        assertNotNull("Profile should be loaded", profileLoaded)
        assertEquals(1L, profileLoaded?.chatId)
        assertTrue("Status text should be present", profileLoaded?.statusText?.isNotEmpty() == true)
        assertTrue("Bio should be present", profileLoaded?.bio?.isNotEmpty() == true)
    }

    @Test
    fun testToggleChatMuteDemoMode() {
        telegramManager.enableDemoMode()

        var toggledResult: Boolean? = null
        telegramManager.toggleChatMute(1L, currentMuted = false) { newMuted ->
            toggledResult = newMuted
        }
        assertTrue("Should toggle to muted", toggledResult == true)

        telegramManager.toggleChatMute(1L, currentMuted = true) { newMuted ->
            toggledResult = newMuted
        }
        assertTrue("Should toggle to unmuted", toggledResult == false)
    }
}
