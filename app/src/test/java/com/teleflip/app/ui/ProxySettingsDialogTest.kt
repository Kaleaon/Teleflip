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
import com.teleflip.app.model.ProxyItem
import com.teleflip.app.model.ProxyType
import com.teleflip.app.telegram.TelegramManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

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

    @Test
    fun testSoftkeyBarBoundAndVisible() {
        val dialog = ProxySettingsDialog(context, mockTelegramManager)
        dialog.show()

        val softkeyBar = dialog.findViewById<View>(R.id.softkey_bar)
        val tvLeft = dialog.findViewById<TextView>(R.id.tv_softkey_left)
        val tvCenter = dialog.findViewById<TextView>(R.id.tv_softkey_center)
        val tvRight = dialog.findViewById<TextView>(R.id.tv_softkey_right)

        assertNotNull("Softkey bar should be present in layout", softkeyBar)
        assertNotNull("Left softkey text should be present", tvLeft)
        assertNotNull("Center softkey text should be present", tvCenter)
        assertNotNull("Right softkey text should be present", tvRight)
        assertEquals("Softkey bar should be visible", View.VISIBLE, softkeyBar.visibility)
    }

    @Test
    fun testSoftkeysUpdatedOnEditTextFocus() {
        val dialog = ProxySettingsDialog(context, mockTelegramManager)
        dialog.show()

        val etServer = dialog.findViewById<EditText>(R.id.et_proxy_server)
        val tvLeft = dialog.findViewById<TextView>(R.id.tv_softkey_left)
        val tvCenter = dialog.findViewById<TextView>(R.id.tv_softkey_center)
        val tvRight = dialog.findViewById<TextView>(R.id.tv_softkey_right)

        etServer.requestFocus()

        assertEquals("Clear", tvLeft.text.toString())
        assertEquals("Save", tvCenter.text.toString())
        assertEquals("Close", tvRight.text.toString())
    }

    @Test
    fun testSoftkeysUpdatedOnButtonFocus() {
        val dialog = ProxySettingsDialog(context, mockTelegramManager)
        dialog.show()

        val btnSaveEnable = dialog.findViewById<Button>(R.id.btn_proxy_save_enable)
        val tvLeft = dialog.findViewById<TextView>(R.id.tv_softkey_left)
        val tvCenter = dialog.findViewById<TextView>(R.id.tv_softkey_center)
        val tvRight = dialog.findViewById<TextView>(R.id.tv_softkey_right)

        btnSaveEnable.requestFocus()

        assertEquals("", tvLeft.text.toString())
        assertEquals("Select", tvCenter.text.toString())
        assertEquals("Close", tvRight.text.toString())
    }

    @Test
    fun testDpadDirectionalNavigationCommitsCharacter() {
        val dialog = ProxySettingsDialog(context, mockTelegramManager)
        dialog.show()

        val etServer = dialog.findViewById<EditText>(R.id.et_proxy_server)

        val dpadKeys =
            listOf(
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
            )

        for (dpadKey in dpadKeys) {
            etServer.setText("")
            etServer.requestFocus()

            val key2Event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
            dialog.onKeyDown(KeyEvent.KEYCODE_2, key2Event)

            // Multi-tap has pending "A"
            assertEquals("A", etServer.text.toString())

            val navEvent = KeyEvent(KeyEvent.ACTION_DOWN, dpadKey)
            dialog.onKeyDown(dpadKey, navEvent)

            // Character must be committed
            assertEquals("D-pad key $dpadKey should commit character", "A", etServer.text.toString())
        }
    }

    @Test
    fun testSoftkeyCenterTriggersSaveWhenEditTextFocused() {
        val dialog = ProxySettingsDialog(context, mockTelegramManager)
        dialog.show()

        val etServer = dialog.findViewById<EditText>(R.id.et_proxy_server)
        etServer.requestFocus()

        val centerEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, centerEvent)

        assertTrue("Center key should be handled by SoftkeyManager", handled)
    }

    @Test
    fun testSoftkeyCenterTriggersButtonClickWhenButtonFocused() {
        val dialog = ProxySettingsDialog(context, mockTelegramManager)
        dialog.show()

        val btnClose = dialog.findViewById<Button>(R.id.btn_proxy_close)
        btnClose.requestFocus()

        val centerEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, centerEvent)

        assertTrue("Center key should be handled by SoftkeyManager", handled)
        assertFalse("Dialog should be dismissed after clicking Close button", dialog.isShowing)
    }

    @Test
    fun testFocusTraversalChainsSocks5AndMtproto() {
        val dialog = ProxySettingsDialog(context, mockTelegramManager)
        dialog.show()

        val etServer = dialog.findViewById<EditText>(R.id.et_proxy_server)
        val etPort = dialog.findViewById<EditText>(R.id.et_proxy_port)
        val etUsername = dialog.findViewById<EditText>(R.id.et_proxy_username)
        val etPassword = dialog.findViewById<EditText>(R.id.et_proxy_password)
        val etSecret = dialog.findViewById<EditText>(R.id.et_proxy_secret)
        val btnSaveEnable = dialog.findViewById<Button>(R.id.btn_proxy_save_enable)
        val btnMtproto = dialog.findViewById<Button>(R.id.btn_proxy_type_mtproto)
        val btnSocks5 = dialog.findViewById<Button>(R.id.btn_proxy_type_socks5)

        // SOCKS5 default
        assertEquals(R.id.et_proxy_port, etServer.nextFocusDownId)
        assertEquals(R.id.et_proxy_username, etPort.nextFocusDownId)
        assertEquals(R.id.et_proxy_password, etUsername.nextFocusDownId)
        assertEquals(R.id.btn_proxy_save_enable, etPassword.nextFocusDownId)
        assertEquals(R.id.et_proxy_password, btnSaveEnable.nextFocusUpId)

        // Switch to MTProto
        btnMtproto.performClick()
        assertEquals(R.id.et_proxy_secret, etPort.nextFocusDownId)
        assertEquals(R.id.btn_proxy_save_enable, etSecret.nextFocusDownId)
        assertEquals(R.id.et_proxy_port, etSecret.nextFocusUpId)
        assertEquals(R.id.et_proxy_secret, btnSaveEnable.nextFocusUpId)

        // Switch back to SOCKS5
        btnSocks5.performClick()
        assertEquals(R.id.et_proxy_username, etPort.nextFocusDownId)
        assertEquals(R.id.et_proxy_password, btnSaveEnable.nextFocusUpId)
    }

    @Test
    fun testSavedProxiesFocusTraversal() {
        val savedProxies =
            listOf(
                ProxyItem(id = 1, server = "1.1.1.1", port = 1080, type = ProxyType.SOCKS5, isEnabled = true),
                ProxyItem(id = 2, server = "2.2.2.2", port = 8080, type = ProxyType.MTPROTO, isEnabled = false),
            )
        `when`(mockTelegramManager.getProxies()).thenReturn(savedProxies)
        `when`(mockTelegramManager.getEnabledProxy()).thenReturn(savedProxies[0])

        val dialog = ProxySettingsDialog(context, mockTelegramManager)
        dialog.show()

        val btnDisable = dialog.findViewById<Button>(R.id.btn_proxy_disable)
        val btnClose = dialog.findViewById<Button>(R.id.btn_proxy_close)

        assertTrue("Disable button should navigate down to first saved proxy", btnDisable.nextFocusDownId != View.NO_ID)
        assertTrue("Close button should navigate up to last saved proxy", btnClose.nextFocusUpId != View.NO_ID)
        assertTrue(
            "Disable nextFocusDown and Close nextFocusUp should not be each other when proxies exist",
            btnDisable.nextFocusDownId != R.id.btn_proxy_close,
        )
    }

    @Test
    fun testRenderProxySettingsScreenshot() {
        val dialog = ProxySettingsDialog(context, mockTelegramManager)
        dialog.show()

        val view = dialog.window?.decorView ?: return
        view.measure(
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 320, 480)

        val bitmap = android.graphics.Bitmap.createBitmap(320, 480, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        view.draw(canvas)

        java.io.File("/tmp/proxy_settings_dialog.png").outputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }

        assertTrue(java.io.File("/tmp/proxy_settings_dialog.png").exists())
    }
}
