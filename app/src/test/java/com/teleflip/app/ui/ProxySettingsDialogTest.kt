package com.teleflip.app.ui

import android.app.Application
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
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

@RunWith(AndroidJUnit4::class)
class ProxySettingsDialogTest {
    private lateinit var context: Application
    private lateinit var telegramManager: TelegramManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.Theme_TeleFlip)
        telegramManager = TelegramManager(context)
    }

    @Test
    fun testInputKeysHandledWhenEditTextFocused() {
        val dialog = ProxySettingsDialog(context, telegramManager)
        dialog.show()

        val etServer = dialog.findViewById<EditText>(R.id.et_proxy_server)
        etServer.requestFocus()
        assertTrue("etServer should have focus", etServer.hasFocus())

        val event2 = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_2, event2)

        assertTrue("KEYCODE_2 should be consumed when EditText is focused", handled)
        assertEquals("A", etServer.text.toString())
    }

    @Test
    fun testSoftLeftClearsFocusedEditText() {
        val dialog = ProxySettingsDialog(context, telegramManager)
        dialog.show()

        val etServer = dialog.findViewById<EditText>(R.id.et_proxy_server)
        etServer.requestFocus()
        etServer.setText("proxy.example.com")

        val softLeftEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_LEFT)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_SOFT_LEFT, softLeftEvent)

        assertTrue("KEYCODE_SOFT_LEFT should be consumed when EditText is focused", handled)
        assertEquals("", etServer.text.toString())
    }

    @Test
    fun testFocusChangeCommitsPendingCharacter() {
        val dialog = ProxySettingsDialog(context, telegramManager)
        dialog.show()

        val etServer = dialog.findViewById<EditText>(R.id.et_proxy_server)
        val etPort = dialog.findViewById<EditText>(R.id.et_proxy_port)

        etServer.requestFocus()
        val event2 = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        dialog.onKeyDown(KeyEvent.KEYCODE_2, event2)

        assertEquals("A", etServer.text.toString())

        // Move focus to etPort
        etPort.requestFocus()

        // Active character should be committed upon focus loss
        assertEquals("A", etServer.text.toString())
    }

    @Test
    fun testBackPressedCommitsAndDismissesDialog() {
        val dialog = ProxySettingsDialog(context, telegramManager)
        dialog.show()

        val etServer = dialog.findViewById<EditText>(R.id.et_proxy_server)
        etServer.requestFocus()

        val event2 = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        dialog.onKeyDown(KeyEvent.KEYCODE_2, event2)

        assertTrue(dialog.isShowing)

        dialog.onBackPressed()

        assertFalse("Dialog should be dismissed after onBackPressed()", dialog.isShowing)
        assertEquals("A", etServer.text.toString())
    }

    @Test
    fun testSoftRightKeyCommitsAndDismissesDialog() {
        val dialog = ProxySettingsDialog(context, telegramManager)
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
    fun testDpadRightNotConsumedAsInputKeyWhenEditTextFocused() {
        val dialog = ProxySettingsDialog(context, telegramManager)
        dialog.show()

        val etServer = dialog.findViewById<EditText>(R.id.et_proxy_server)
        etServer.requestFocus()
        assertTrue("etServer should have focus", etServer.hasFocus())

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, event)

        assertFalse("KEYCODE_DPAD_RIGHT should not be consumed as an input key when EditText is focused", handled)
    }

    @Test
    fun testInputKeysNotConsumedWhenNoEditTextFocused() {
        val dialog = ProxySettingsDialog(context, telegramManager)
        dialog.show()

        dialog.currentFocus?.clearFocus()

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_2, event)

        assertFalse("KEYCODE_2 should not be consumed when no EditText is focused", handled)
    }
}
