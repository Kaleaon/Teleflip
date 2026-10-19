package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.ChatType
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class TelegramManagerTest {
    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockPrefs = mock(SharedPreferences::class.java)
        mockEditor = mock(SharedPreferences.Editor::class.java)

        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putInt(anyString(), anyInt())).thenReturn(mockEditor)
        `when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        `when`(mockEditor.putLong(anyString(), anyLong())).thenReturn(mockEditor)
    }

    @Test
    fun testKeypadTimeoutMsDefaultValue() {
        `when`(mockPrefs.getLong(eq("keypad_timeout_ms"), anyLong())).thenReturn(500L)

        val manager = TelegramManager(mockContext)
        assertEquals(500L, manager.keypadTimeoutMs)
    }

    @Test
    fun testKeypadTimeoutMsInvalidFallback() {
        `when`(mockPrefs.getLong(eq("keypad_timeout_ms"), anyLong())).thenReturn(1200L)

        val manager = TelegramManager(mockContext)
        assertEquals(500L, manager.keypadTimeoutMs)
    }

    @Test
    fun testKeypadTimeoutMsValidSelections() {
        val manager = TelegramManager(mockContext)

        manager.keypadTimeoutMs = 300L
        verify(mockEditor).putLong("keypad_timeout_ms", 300L)

        manager.keypadTimeoutMs = 900L
        verify(mockEditor).putLong("keypad_timeout_ms", 900L)

        manager.keypadTimeoutMs = 750L // invalid value fallback
        verify(mockEditor).putLong("keypad_timeout_ms", 500L)
    }

    @Test
    fun testUninitializedClientSubmissionDoesNotLockInFlightState() {
        val manager = TelegramManager(mockContext)

        assertFalse("Initial in-flight query state should be false", manager.isAuthQueryInProgress)

        // Attempting to send phone number when native client is unavailable (not started or init error)
        manager.sendPhoneNumber("+15551234567")

        assertFalse("isAuthQueryInProgress must remain false when tdClient is unavailable", manager.isAuthQueryInProgress)

        val authState = manager.authState.value
        assertTrue("Auth state should be WaitPhoneNumber with error message", authState is AuthState.WaitPhoneNumber)
        val waitPhoneState = authState as AuthState.WaitPhoneNumber
        assertTrue(
            "Error message should inform user that client is unavailable",
            waitPhoneState.errorMessage?.contains("unavailable") == true,
        )
    }

    @Test
    fun testUninitializedCodeAndPasswordSubmissionDoNotLockInFlightState() {
        val manager = TelegramManager(mockContext)

        // Test checkCode when client is unavailable
        manager.checkCode("12345")
        assertFalse("isAuthQueryInProgress must remain false for code submission without active client", manager.isAuthQueryInProgress)

        // Test checkPassword when client is unavailable
        manager.checkPassword("secret")
        assertFalse("isAuthQueryInProgress must remain false for password submission without active client", manager.isAuthQueryInProgress)
    }

    @Test
    fun testTestingHooksAndStateFlows() {
        val manager = TelegramManager(mockContext)
        val chat = ChatModel(10L, "Test", "Msg", "12:00", 0, ChatType.DIRECT, "T")
        manager.setChatsForTesting(listOf(chat))
        assertEquals(1, manager.chats.value.size)
        assertEquals("Test", manager.chats.value[0].title)

        manager.setConnectionStateForTesting("Ready")
        assertEquals("Ready", manager.connectionState.value)

        manager.setAuthStateForTesting(AuthState.Ready)
        assertEquals(AuthState.Ready, manager.authState.value)
    }

    @Test
    fun testRetryConnectionResetsInFlightQueryState() {
        val manager = TelegramManager(mockContext)
        manager.retryConnection()

        assertFalse("retryConnection must reset isAuthQueryInProgress to false", manager.isAuthQueryInProgress)
    }

    @Test
    fun testExtractStickerInfoNullOrNonStickerContent() {
        val manager = TelegramManager(mockContext)

        val nullResult = manager.extractStickerInfo(null)
        assertEquals("", nullResult.first)
        assertFalse(nullResult.second)

        val textContent = TdApi.MessageText()
        val textResult = manager.extractStickerInfo(textContent)
        assertEquals("", textResult.first)
        assertFalse(textResult.second)
    }

    @Test
    fun testExtractStickerInfoStandardSticker() {
        val manager = TelegramManager(mockContext)

        val sticker = TdApi.Sticker()
        sticker.emoji = "😀"
        sticker.fullType = TdApi.StickerFullTypeRegular()

        val content = TdApi.MessageSticker()
        content.sticker = sticker

        val result = manager.extractStickerInfo(content)
        assertEquals("😀", result.first)
        assertFalse("Standard sticker should not be premium", result.second)
    }

    @Test
    fun testExtractStickerInfoPremiumSticker() {
        val manager = TelegramManager(mockContext)

        class StickerFullTypePremium : TdApi.StickerFullType() {
            override fun getConstructor(): Int = 0
        }

        val sticker = TdApi.Sticker()
        sticker.emoji = "🔥"
        sticker.fullType = StickerFullTypePremium()

        val content = TdApi.MessageSticker()
        content.sticker = sticker

        val result = manager.extractStickerInfo(content)
        assertEquals("🔥", result.first)
        assertTrue("Premium sticker should be detected as premium", result.second)
    }

    @Test
    fun testExtractStickerInfoNullEmojiAndFullType() {
        val manager = TelegramManager(mockContext)

        val sticker = TdApi.Sticker()
        sticker.emoji = null
        sticker.fullType = null

        val content = TdApi.MessageSticker()
        content.sticker = sticker

        val result = manager.extractStickerInfo(content)
        assertEquals("", result.first)
        assertFalse("Null fullType should safely evaluate isPremium to false", result.second)
    }
}
