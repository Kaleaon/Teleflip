package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class TelegramManager2FaTest {
    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var prefsEditor: SharedPreferences.Editor
    private lateinit var telegramManager: TelegramManager

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        sharedPreferences = mock(SharedPreferences::class.java)
        prefsEditor = mock(SharedPreferences.Editor::class.java)

        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPreferences)
        `when`(sharedPreferences.edit()).thenReturn(prefsEditor)
        `when`(prefsEditor.putInt(anyString(), anyInt())).thenReturn(prefsEditor)
        `when`(prefsEditor.putString(anyString(), anyString())).thenReturn(prefsEditor)

        telegramManager = TelegramManager(context)
    }

    @Test
    fun testWaitPasswordStateMetadata() {
        val waitPass =
            AuthState.WaitPassword(
                hint = "Favorite color",
                hasRecoveryEmailAddress = true,
                recoveryEmailAddressPattern = "u***r@example.com",
            )

        assertEquals("Favorite color", waitPass.hint)
        assertTrue(waitPass.hasRecoveryEmailAddress)
        assertEquals("u***r@example.com", waitPass.recoveryEmailAddressPattern)
    }

    @Test
    fun testDemoModeRequestPasswordRecovery() {
        telegramManager.enableDemoMode()
        telegramManager.requestPasswordRecovery()

        val state = telegramManager.authState.value
        assertTrue(state is AuthState.WaitPasswordRecoveryCode)
        val recoveryState = state as AuthState.WaitPasswordRecoveryCode
        assertEquals("d***o@example.com", recoveryState.recoveryEmailPattern)
    }

    @Test
    fun testDemoModeRecoverPassword() {
        telegramManager.enableDemoMode()
        telegramManager.requestPasswordRecovery()
        telegramManager.recoverPassword("123456")

        val state = telegramManager.authState.value
        assertEquals(AuthState.Ready, state)
    }

    @Test
    fun testDemoModeResetAccount() {
        telegramManager.enableDemoMode()
        telegramManager.resetAccount()

        val state = telegramManager.authState.value
        assertTrue(state is AuthState.WaitPhoneNumber)
        val phoneState = state as AuthState.WaitPhoneNumber
        assertEquals("Demo account reset successfully.", phoneState.errorMessage)
    }

    @Test
    fun testEmailCodeInfoResultHandler() {
        val codeInfo =
            TdApi.EmailAddressAuthenticationCodeInfo().apply {
                emailAddressPattern = "a***b@test.com"
                length = 6
            }

        telegramManager.onResult(codeInfo)

        val state = telegramManager.authState.value
        assertTrue(state is AuthState.WaitPasswordRecoveryCode)
        val recoveryState = state as AuthState.WaitPasswordRecoveryCode
        assertEquals("a***b@test.com", recoveryState.recoveryEmailPattern)
    }

    @Test
    fun testErrorMappingForWaitPasswordRecoveryCode() {
        val codeInfo =
            TdApi.EmailAddressAuthenticationCodeInfo().apply {
                emailAddressPattern = "a***b@test.com"
            }
        telegramManager.onResult(codeInfo)

        val tdError = TdApi.Error(400, "PASSWORD_RECOVERY_EXPIRED")
        telegramManager.onResult(tdError)

        val state = telegramManager.authState.value
        assertTrue(state is AuthState.WaitPasswordRecoveryCode)
        val recoveryState = state as AuthState.WaitPasswordRecoveryCode
        assertEquals("PASSWORD_RECOVERY_EXPIRED", recoveryState.errorMessage)
    }
}
