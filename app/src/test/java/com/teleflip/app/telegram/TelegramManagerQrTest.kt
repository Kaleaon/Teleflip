package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class TelegramManagerQrTest {
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
    fun testWaitOtherDeviceConfirmationStateAndSwitchToPhoneLogin() {
        val manager = TelegramManager(mockContext)
        val qrState = AuthState.WaitOtherDeviceConfirmation("tg://login?token=xyz")

        manager.setAuthStateForTesting(qrState)
        assertEquals(qrState, manager.authState.value)

        val activeState = manager.authState.value as AuthState.WaitOtherDeviceConfirmation
        assertEquals("tg://login?token=xyz", activeState.link)

        manager.showPhoneNumberLogin()
        assertTrue("Auth state should transition back to WaitPhoneNumber", manager.authState.value is AuthState.WaitPhoneNumber)
    }

    @Test
    fun testRequestQrCodeLoginUninitializedClientHandlesError() {
        val manager = TelegramManager(mockContext)
        assertFalse("In-flight state should start as false", manager.isAuthQueryInProgress)

        manager.requestQrCodeLogin()

        assertFalse("isAuthQueryInProgress should remain false when client is unavailable", manager.isAuthQueryInProgress)
        val state = manager.authState.value
        assertTrue("Auth state should handle unavailable client", state is AuthState.WaitPhoneNumber || state is AuthState.WaitOtherDeviceConfirmation)
    }
}
