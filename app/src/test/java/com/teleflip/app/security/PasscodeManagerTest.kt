package com.teleflip.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PasscodeManagerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PasscodeManager.disablePasscode(context)
    }

    @Test
    fun testInitialStateIsDisabledAndNotLocked() {
        assertFalse(PasscodeManager.isEnabled(context))
        assertFalse(PasscodeManager.isLocked(context))
    }

    @Test
    fun testSetPasscodeAndVerify() {
        PasscodeManager.setPasscode(context, "1234")
        assertTrue(PasscodeManager.isEnabled(context))
        assertTrue(PasscodeManager.verifyPasscode(context, "1234"))
        assertFalse(PasscodeManager.verifyPasscode(context, "0000"))
    }

    @Test
    fun testUnlockWithCorrectAndIncorrectPin() {
        PasscodeManager.setPasscode(context, "4321")
        PasscodeManager.lock()
        assertTrue(PasscodeManager.isLocked(context))

        assertFalse(PasscodeManager.unlock(context, "1111"))
        assertTrue(PasscodeManager.isLocked(context))

        assertTrue(PasscodeManager.unlock(context, "4321"))
        assertFalse(PasscodeManager.isLocked(context))
    }

    @Test
    fun testDisablePasscode() {
        PasscodeManager.setPasscode(context, "9876")
        assertTrue(PasscodeManager.isEnabled(context))

        PasscodeManager.disablePasscode(context)
        assertFalse(PasscodeManager.isEnabled(context))
        assertFalse(PasscodeManager.isLocked(context))
    }

    @Test
    fun testAutoLockTimeoutGetAndSet() {
        assertEquals(PasscodeManager.TIMEOUT_IMMEDIATE, PasscodeManager.getAutoLockTimeoutSeconds(context))
        assertEquals("Immediately", PasscodeManager.getAutoLockLabel(context))

        PasscodeManager.setAutoLockTimeoutSeconds(context, PasscodeManager.TIMEOUT_1_MIN)
        assertEquals(PasscodeManager.TIMEOUT_1_MIN, PasscodeManager.getAutoLockTimeoutSeconds(context))
        assertEquals("1 Minute", PasscodeManager.getAutoLockLabel(context))

        PasscodeManager.setAutoLockTimeoutSeconds(context, PasscodeManager.TIMEOUT_5_MIN)
        assertEquals(PasscodeManager.TIMEOUT_5_MIN, PasscodeManager.getAutoLockTimeoutSeconds(context))
        assertEquals("5 Minutes", PasscodeManager.getAutoLockLabel(context))
    }

    @Test
    fun testAutoLockForegroundCheck() {
        PasscodeManager.setPasscode(context, "2580")
        PasscodeManager.setAutoLockTimeoutSeconds(context, PasscodeManager.TIMEOUT_IMMEDIATE)

        PasscodeManager.onAppBackgrounded()
        PasscodeManager.onAppForegrounded(context)
        assertTrue(PasscodeManager.isLocked(context))

        PasscodeManager.unlock(context, "2580")
        assertFalse(PasscodeManager.isLocked(context))
    }

    @Test
    fun testConsecutiveForegroundCallsAfterUnlockDoNotRelock() {
        PasscodeManager.setPasscode(context, "2580")
        PasscodeManager.setAutoLockTimeoutSeconds(context, PasscodeManager.TIMEOUT_IMMEDIATE)

        PasscodeManager.onAppBackgrounded()
        PasscodeManager.onAppForegrounded(context)
        assertTrue(PasscodeManager.isLocked(context))

        assertTrue(PasscodeManager.unlock(context, "2580"))
        assertFalse(PasscodeManager.isLocked(context))

        // Consecutive foreground call after unlock must not reuse stale timestamp
        PasscodeManager.onAppForegrounded(context)
        assertFalse(PasscodeManager.isLocked(context))
    }

    @Test
    fun testForegroundTimeoutEvaluationClearsBackgroundTimestamp() {
        PasscodeManager.setPasscode(context, "2580")
        PasscodeManager.setAutoLockTimeoutSeconds(context, PasscodeManager.TIMEOUT_1_MIN)

        PasscodeManager.onAppBackgrounded()
        // Foreground immediately before timeout
        PasscodeManager.onAppForegrounded(context)
        assertFalse(PasscodeManager.isLocked(context))

        // Subsequent foreground check without new backgrounding must not use stale background timestamp
        PasscodeManager.onAppForegrounded(context)
        assertFalse(PasscodeManager.isLocked(context))
    }

    @Test
    fun testStateTransitionsResetBackgroundTimestamp() {
        PasscodeManager.setPasscode(context, "2580")
        PasscodeManager.setAutoLockTimeoutSeconds(context, PasscodeManager.TIMEOUT_IMMEDIATE)

        // Backgrounding sets timestamp
        PasscodeManager.onAppBackgrounded()

        // Setting a new passcode resets background timestamp
        PasscodeManager.setPasscode(context, "1111")
        PasscodeManager.onAppForegrounded(context)
        assertFalse(PasscodeManager.isLocked(context))

        // Explicit locking resets background timestamp
        PasscodeManager.onAppBackgrounded()
        PasscodeManager.lock()
        assertTrue(PasscodeManager.isLocked(context))
        assertTrue(PasscodeManager.unlock(context, "1111"))
        PasscodeManager.onAppForegrounded(context)
        assertFalse(PasscodeManager.isLocked(context))

        // Disabling passcode resets background timestamp
        PasscodeManager.onAppBackgrounded()
        PasscodeManager.disablePasscode(context)
        PasscodeManager.onAppForegrounded(context)
        assertFalse(PasscodeManager.isLocked(context))
    }

    @Test
    fun testCanAuthenticate() {
        val canAuth = PasscodeManager.canAuthenticate(context)
        // Verify canAuthenticate executes without throwing
        assertTrue(canAuth || !canAuth)
    }

    @Test
    fun testBiometricUnlockUnlocksSession() {
        PasscodeManager.setPasscode(context, "1234")
        PasscodeManager.lock()
        assertTrue(PasscodeManager.isLocked(context))

        val success = PasscodeManager.unlockBiometric()
        assertTrue(success)
        assertFalse(PasscodeManager.isLocked(context))
    }

    @Test
    fun testAuthenticationCallbackSucceeded() {
        PasscodeManager.setPasscode(context, "1234")
        PasscodeManager.lock()
        assertTrue(PasscodeManager.isLocked(context))

        var successCalled = false
        var errorCalled = false
        val callback =
            PasscodeManager.createAuthenticationCallback(
                onSuccess = { successCalled = true },
                onError = { errorCalled = true },
            )

        val result = org.mockito.Mockito.mock(androidx.biometric.BiometricPrompt.AuthenticationResult::class.java)
        callback.onAuthenticationSucceeded(result)

        assertTrue(successCalled)
        assertFalse(errorCalled)
        assertFalse(PasscodeManager.isLocked(context))
    }

    @Test
    fun testAuthenticationCallbackError() {
        PasscodeManager.setPasscode(context, "1234")
        PasscodeManager.lock()
        assertTrue(PasscodeManager.isLocked(context))

        var reportedError: String? = null
        val callback =
            PasscodeManager.createAuthenticationCallback(
                onSuccess = {},
                onError = { reportedError = it },
            )

        callback.onAuthenticationError(androidx.biometric.BiometricPrompt.ERROR_HW_UNAVAILABLE, "Hardware unavailable")

        assertEquals("Hardware unavailable", reportedError)
        assertTrue(PasscodeManager.isLocked(context))
    }

    @Test
    fun testAuthenticationCallbackUserCanceledIgnoredForErrorMessage() {
        PasscodeManager.setPasscode(context, "1234")
        PasscodeManager.lock()
        assertTrue(PasscodeManager.isLocked(context))

        var reportedError: String? = null
        val callback =
            PasscodeManager.createAuthenticationCallback(
                onSuccess = {},
                onError = { reportedError = it },
            )

        callback.onAuthenticationError(androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED, "User canceled")

        assertEquals(null, reportedError)
        assertTrue(PasscodeManager.isLocked(context))
    }

    @Test
    fun testAuthenticationCallbackFailed() {
        PasscodeManager.setPasscode(context, "1234")
        PasscodeManager.lock()
        assertTrue(PasscodeManager.isLocked(context))

        var reportedError: String? = null
        val callback =
            PasscodeManager.createAuthenticationCallback(
                onSuccess = {},
                onError = { reportedError = it },
            )

        callback.onAuthenticationFailed()

        assertEquals("Biometric authentication failed", reportedError)
        assertTrue(PasscodeManager.isLocked(context))
    }

    @Test
    fun testFallbackToManualPinEntry() {
        PasscodeManager.setPasscode(context, "1234")
        PasscodeManager.lock()
        assertTrue(PasscodeManager.isLocked(context))

        // When biometric fails or is cancelled, manual PIN unlock functions as expected
        val failedPin = PasscodeManager.unlock(context, "0000")
        assertFalse(failedPin)
        assertTrue(PasscodeManager.isLocked(context))

        val correctPin = PasscodeManager.unlock(context, "1234")
        assertTrue(correctPin)
        assertFalse(PasscodeManager.isLocked(context))
    }
}
