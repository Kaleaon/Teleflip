package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.model.SlowModeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramSlowModeTest {
    private lateinit var telegramManager: TelegramManager

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        Mockito.`when`(context.getSharedPreferences("teleflip_prefs", Context.MODE_PRIVATE)).thenReturn(prefs)
        Mockito.`when`(prefs.edit()).thenReturn(editor)
        Mockito.`when`(editor.putStringSet(Mockito.anyString(), Mockito.any())).thenReturn(editor)
        telegramManager = TelegramManager(context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun slowModeStateCalculatesRemainingSecondsCorrectly() {
        val now = System.currentTimeMillis()
        val stateActive = SlowModeState(chatId = 100L, delaySeconds = 60, expiresAtEpochMs = now + 25000L)
        assertTrue(stateActive.isActive)
        val remaining = stateActive.remainingSeconds(now)
        assertEquals(25, remaining)

        val stateExpired = SlowModeState(chatId = 100L, delaySeconds = 60, expiresAtEpochMs = now - 1000L)
        assertFalse(stateExpired.isActive)
        assertEquals(0, stateExpired.remainingSeconds(now))
    }

    @Test
    fun updateSlowModeStateFromSupergroupInfoUpdatesStateFlow() {
        val chatId = 200L
        telegramManager.updateSlowModeStateFromSupergroupInfo(
            chatId = chatId,
            delaySeconds = 60,
            expiresInSeconds = 30.0,
        )

        val state = telegramManager.getSlowModeState(chatId)
        assertNotNull(state)
        assertEquals(60, state?.delaySeconds)
        assertTrue((state?.remainingSeconds() ?: 0) > 0)
    }

    @Test
    fun updateSupergroupFullInfoEventUpdatesSlowModeState() {
        val chatId = 300L
        val supergroupId = 55L

        // Register chat mapping
        val supergroupType = TdApi.ChatTypeSupergroup(supergroupId, false)
        val chatObj =
            TdApi.Chat().apply {
                id = chatId
                title = "Supergroup Chat"
                type = supergroupType
            }

        // Trigger onResult with UpdateNewChat to populate mapping
        telegramManager.onResult(TdApi.UpdateNewChat(chatObj))

        // Trigger UpdateSupergroupFullInfo with slow mode delay
        val fullInfo =
            TdApi.SupergroupFullInfo().apply {
                slowModeDelay = 30
                slowModeDelayExpiresIn = 15.0
            }
        telegramManager.onResult(TdApi.UpdateSupergroupFullInfo(supergroupId, fullInfo))

        val state = telegramManager.getSlowModeState(chatId)
        assertNotNull(state)
        assertEquals(30, state?.delaySeconds)
        assertTrue((state?.remainingSeconds() ?: 0) > 0)
    }

    @Test
    fun sendMessageInSlowModeChatTriggersCooldown() {
        val chatId = 400L
        telegramManager.updateSlowModeStateFromSupergroupInfo(chatId, delaySeconds = 60, expiresInSeconds = 0.0)

        val client = mock(Client::class.java)
        telegramManager.setTdClientForTesting(client)

        telegramManager.sendMessage(chatId, "Hello World")

        val remaining = telegramManager.getSlowModeRemainingSeconds(chatId)
        assertTrue(remaining > 0)
    }

    @Test
    fun slowModeWaitErrorUpdatesCooldownAndSuppressesGenericToast() {
        val chatId = 500L
        val client = mock(Client::class.java)

        Mockito.doAnswer { invocation ->
            val handler = invocation.getArgument<Client.ResultHandler>(1)
            handler.onResult(TdApi.Error(400, "SLOWMODE_WAIT_42"))
            null
        }.`when`(client).send(Mockito.any<TdApi.Function<*>>(), Mockito.any<Client.ResultHandler>())

        telegramManager.setTdClientForTesting(client)
        telegramManager.sendMessage(chatId, "Test message")

        // Cooldown should be updated to 42 seconds
        val remaining = telegramManager.getSlowModeRemainingSeconds(chatId)
        assertTrue(remaining in 40..42)

        // operationError should be null (generic error toast suppressed)
        assertNull(telegramManager.operationError.value)
    }
}
