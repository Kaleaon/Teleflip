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
}
