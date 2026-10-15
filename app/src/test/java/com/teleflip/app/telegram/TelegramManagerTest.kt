package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

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
        assertTrue("Error message should inform user that client is unavailable", waitPhoneState.errorMessage?.contains("unavailable") == true)
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
    fun testDemoModeIsolation() {
        val manager = TelegramManager(mockContext)
        manager.enableDemoMode()

        assertTrue("Demo mode should be enabled", manager.isDemoMode)
        assertEquals("Demo (Offline)", manager.connectionState.value)

        // Demo submissions update auth state directly without touching native client or locking in-flight query
        manager.sendPhoneNumber("+15559990000")
        assertFalse("isAuthQueryInProgress must remain false in demo mode", manager.isAuthQueryInProgress)
        assertTrue("Demo phone submit transitions to WaitCode", manager.authState.value is AuthState.WaitCode)

        manager.checkCode("12345")
        assertFalse("isAuthQueryInProgress must remain false in demo code check", manager.isAuthQueryInProgress)
        assertTrue("Demo code check transitions to Ready", manager.authState.value is AuthState.Ready)
    }

    @Test
    fun testRetryConnectionResetsInFlightQueryState() {
        val manager = TelegramManager(mockContext)
        manager.retryConnection()

        assertFalse("retryConnection must reset isAuthQueryInProgress to false", manager.isAuthQueryInProgress)
    }
}
