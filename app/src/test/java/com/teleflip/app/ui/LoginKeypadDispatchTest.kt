package com.teleflip.app.ui

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.teleflip.app.MainActivity
import com.teleflip.app.R
import com.teleflip.app.security.PasscodeManager
import com.teleflip.app.telegram.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LoginKeypadDispatchTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PasscodeManager.disablePasscode(context)
    }

    private fun setAuthState(
        activity: MainActivity,
        state: AuthState,
    ) {
        activity.telegramManager.setAuthStateForTesting(state)
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun testKeypadDispatchInPhoneModeRoutesFocusToInput() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.window.callback?.onWindowFocusChanged(true)

        setAuthState(activity, AuthState.WaitPhoneNumber(null))

        val etLoginInput = activity.findViewById<EditText>(R.id.et_login_input)
        val inputContainer = activity.findViewById<View>(R.id.layout_login_input_container)
        assertNotNull(etLoginInput)
        assertEquals("Input container should be VISIBLE in Phone mode", View.VISIBLE, inputContainer.visibility)
        assertTrue("etLoginInput should be shown in Phone Login mode", etLoginInput.isShown)

        etLoginInput.requestFocus()
        assertTrue("etLoginInput should have focus in Phone Login mode", etLoginInput.hasFocus())

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_5)
        activity.dispatchKeyEvent(event)

        assertTrue("etLoginInput should retain focus when keypad key is pressed in Phone Login mode", etLoginInput.hasFocus())
    }

    @Test
    fun testKeypadDispatchInQrModeRetainsFocusOnActionButton() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.window.callback?.onWindowFocusChanged(true)

        setAuthState(activity, AuthState.WaitOtherDeviceConfirmation("tg://login?token=123"))

        val etLoginInput = activity.findViewById<EditText>(R.id.et_login_input)
        val inputContainer = activity.findViewById<View>(R.id.layout_login_input_container)
        val btnPhoneLogin = activity.findViewById<Button>(R.id.btn_phone_login)

        assertNotNull(etLoginInput)
        assertNotNull(btnPhoneLogin)
        assertEquals("Input container should be GONE in QR mode", View.GONE, inputContainer.visibility)
        assertFalse("etLoginInput should not be shown in QR mode", etLoginInput.isShown)

        assertTrue("btnPhoneLogin should have focus in QR mode", btnPhoneLogin.hasFocus())

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_5)
        activity.dispatchKeyEvent(event)

        assertFalse("etLoginInput should not gain focus when keypad key is pressed in QR mode", etLoginInput.hasFocus())
        assertTrue("Focus should remain on btnPhoneLogin in QR mode", btnPhoneLogin.hasFocus())
    }

    @Test
    fun testTransitionFromQrToPhoneModeRestoresKeypadRouting() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.window.callback?.onWindowFocusChanged(true)

        // Enter QR mode
        setAuthState(activity, AuthState.WaitOtherDeviceConfirmation("tg://login?token=123"))
        var etLoginInput = activity.findViewById<EditText>(R.id.et_login_input)
        var inputContainer = activity.findViewById<View>(R.id.layout_login_input_container)
        assertEquals("Input container should be GONE in QR mode", View.GONE, inputContainer.visibility)
        assertFalse("etLoginInput should not be shown in QR mode", etLoginInput.isShown)

        // Switch back to Phone mode
        setAuthState(activity, AuthState.WaitPhoneNumber(null))
        etLoginInput = activity.findViewById(R.id.et_login_input)
        inputContainer = activity.findViewById(R.id.layout_login_input_container)

        assertEquals("Input container should be VISIBLE after returning to Phone mode", View.VISIBLE, inputContainer.visibility)
        assertTrue("etLoginInput should be shown after returning to Phone mode", etLoginInput.isShown)

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        activity.dispatchKeyEvent(event)

        assertTrue("etLoginInput should regain focus on keypad press after returning to Phone mode", etLoginInput.hasFocus())
    }
}
