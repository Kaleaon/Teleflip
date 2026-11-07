package com.teleflip.app.ui

import android.app.Application
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teleflip.app.R
import com.teleflip.app.telegram.TelegramManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock

@RunWith(AndroidJUnit4::class)
class ProxySettingsDialogTest {
    private lateinit var context: Application
    private lateinit var mockTelegramManager: TelegramManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.Theme_TeleFlip)
        mockTelegramManager = mock(TelegramManager::class.java)
    }

    @Test
    fun testEmptyServerSubmission_showsInlineErrorAndFocusesServerField() {
        val dialog = ProxySettingsDialog(context, mockTelegramManager)
        dialog.show()

        val btnSaveEnable = dialog.findViewById<Button>(R.id.btn_proxy_save_enable)
        val tvError = dialog.findViewById<TextView>(R.id.tv_proxy_error)
        val etServer = dialog.findViewById<EditText>(R.id.et_proxy_server)

        btnSaveEnable.performClick()

        assertEquals(View.VISIBLE, tvError.visibility)
        assertEquals("Please enter a valid server address", tvError.text.toString())
        assertTrue("etServer should have focus when server is empty", etServer.hasFocus())
    }

    @Test
    fun testOnKeyDown_inputKeysConsumedWhenEditTextFocused() {
        val dialog = ProxySettingsDialog(context, mockTelegramManager)
        dialog.show()

        val etServer = dialog.findViewById<EditText>(R.id.et_proxy_server)
        etServer.requestFocus()

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_2, event)

        assertTrue("KEYCODE_2 should be consumed when EditText is focused", handled)
        assertEquals("A", etServer.text.toString())
    }

    @Test
    fun testOnKeyDown_dpadRightNotConsumedAsInputKey() {
        val dialog = ProxySettingsDialog(context, mockTelegramManager)
        dialog.show()

        val etServer = dialog.findViewById<EditText>(R.id.et_proxy_server)
        etServer.requestFocus()

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, event)

        assertFalse("KEYCODE_DPAD_RIGHT should not be consumed as input key in ProxySettingsDialog", handled)
    }

    @Test
    fun testInputKeysNotConsumedWhenNoEditTextFocused() {
        val dialog = ProxySettingsDialog(context, mockTelegramManager)
        dialog.show()

        dialog.currentFocus?.clearFocus()

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_2, event)

        assertFalse("KEYCODE_2 should not be consumed when no EditText is focused", handled)
    }

    @Test
    fun testBackNavigationCommitsPendingCharacter() {
        val dialog = ProxySettingsDialog(context, mockTelegramManager)
        dialog.show()

        val etServer = dialog.findViewById<EditText>(R.id.et_proxy_server)
        etServer.requestFocus()

        val event2 = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        dialog.onKeyDown(KeyEvent.KEYCODE_2, event2)

        val eventBack = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_BACK, eventBack)

        assertTrue("KEYCODE_BACK should be handled by onKeyDown", handled)
        assertEquals("A", etServer.text.toString())
        assertFalse("Dialog should be dismissed after back key", dialog.isShowing)
    }

    @Test
    fun testSoftRightKeyCommitsAndDismissesDialog() {
        val dialog = ProxySettingsDialog(context, mockTelegramManager)
        dialog.show()

        val etServer = dialog.findViewById<EditText>(R.id.et_proxy_server)
        etServer.requestFocus()

        val event2 = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        dialog.onKeyDown(KeyEvent.KEYCODE_2, event2)

        assertTrue(dialog.isShowing)

        val softRightEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_RIGHT)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_SOFT_RIGHT, softRightEvent)

        assertTrue("Soft Right key should be handled in onKeyDown", handled)
        assertFalse("Dialog should be dismissed after Soft Right key", dialog.isShowing)
        assertEquals("A", etServer.text.toString())
    }

    @Test
    fun testFocusChangeCommitsCharacterAndUpdatesModeIndicator() {
        val dialog = ProxySettingsDialog(context, mockTelegramManager)
        dialog.show()

        val etServer = dialog.findViewById<EditText>(R.id.et_proxy_server)
        val etPort = dialog.findViewById<EditText>(R.id.et_proxy_port)
        val tvMode = dialog.findViewById<TextView>(R.id.tv_proxy_input_mode)

        etServer.requestFocus()
        val event2 = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        dialog.onKeyDown(KeyEvent.KEYCODE_2, event2)

        assertEquals("A", etServer.text.toString())

        // Move focus to etPort (which defaults to NUMERIC mode)
        etPort.requestFocus()

        assertEquals("A", etServer.text.toString())
        assertEquals("[#] 123", tvMode.text.toString())
    }

    @Test
    fun testSoftLeftKeyClearsEditText() {
        val dialog = ProxySettingsDialog(context, mockTelegramManager)
        dialog.show()

        val etServer = dialog.findViewById<EditText>(R.id.et_proxy_server)
        etServer.requestFocus()

        val event2 = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        dialog.onKeyDown(KeyEvent.KEYCODE_2, event2)

        val eventLsk = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_LEFT)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_SOFT_LEFT, eventLsk)

        assertTrue("Soft Left Key should be handled when EditText is focused", handled)
        assertEquals("", etServer.text.toString())
    }

    @Test
    fun testOnBackPressedCommitsCharacterAndDismisses() {
        val dialog = ProxySettingsDialog(context, mockTelegramManager)
        dialog.show()

        val etServer = dialog.findViewById<EditText>(R.id.et_proxy_server)
        etServer.requestFocus()

        val event2 = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        dialog.onKeyDown(KeyEvent.KEYCODE_2, event2)

        dialog.onBackPressed()

        assertEquals("A", etServer.text.toString())
        assertFalse("Dialog should be dismissed after onBackPressed", dialog.isShowing)
    }
}
