package com.teleflip.app.security

import android.content.Context
import android.view.KeyEvent
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import com.teleflip.app.MainActivity
import com.teleflip.app.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
class PasscodeLockFocusTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PasscodeManager.disablePasscode(context)
    }

    @Test
    fun testForgotPasscodeButtonNotFocusableAndCenterKeyDelegation() {
        PasscodeManager.setPasscode(context, "1234")
        PasscodeManager.lock()

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        val btnForgot = activity.findViewById<Button>(R.id.btn_passcode_forgot)
        assertNotNull("Forgot Passcode button should be present", btnForgot)
        assertFalse("Forgot Passcode button should not be focusable", btnForgot.isFocusable)

        // Press digit '1' (incomplete PIN)
        val digitEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1)
        activity.dispatchKeyEvent(digitEvent)

        // Press D-pad Center with incomplete PIN (1 digit entered)
        val centerEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        val handled = activity.dispatchKeyEvent(centerEvent)

        assertTrue("Center key press should be handled and consumed", handled)

        // Confirm logout dialog was NOT triggered by D-pad center
        val latestDialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue("No alert dialog should be displayed on center key press with incomplete passcode", latestDialog == null || !latestDialog.isShowing)
    }

    @Test
    fun testForgotPasscodeButtonTouchClickStillTriggersDialog() {
        PasscodeManager.setPasscode(context, "1234")
        PasscodeManager.lock()

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        val btnForgot = activity.findViewById<Button>(R.id.btn_passcode_forgot)
        assertNotNull("Forgot Passcode button should be present", btnForgot)

        // Direct touch/click on the button should still present the dialog
        btnForgot.performClick()

        val latestDialog = ShadowAlertDialog.getLatestDialog()
        assertNotNull("Dialog should be displayed when forgot button is clicked directly", latestDialog)
        assertTrue("Dialog should be showing", latestDialog.isShowing)
    }

    @Test
    fun testCompletePasscodeEntryUnlocksOnCenterKey() {
        PasscodeManager.setPasscode(context, "1234")
        PasscodeManager.lock()

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        // Enter 4 digits "1234"
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_3))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_4))

        // Note: 4th digit automatically calls attemptPasscodeUnlock()
        assertFalse("App should be unlocked after correct 4-digit passcode entry", PasscodeManager.isLocked(context))
    }
}
