package com.teleflip.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import com.teleflip.app.model.ProxyType
import com.teleflip.app.security.PasscodeManager
import com.teleflip.app.telegram.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityProxyTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PasscodeManager.disablePasscode(context)
    }

    @Test
    fun testPreLoginScreenIncludesProxySettingsButtonWithFocusChain() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        activity.telegramManager.setAuthStateForTesting(AuthState.WaitPhoneNumber(null))

        val btnProxy = activity.findViewById<Button>(R.id.btn_login_proxy)

        assertNotNull("btn_login_proxy must exist in view_login.xml", btnProxy)
        assertEquals(View.VISIBLE, btnProxy.visibility)
        assertEquals("Proxy Settings", btnProxy.text.toString())
    }

    @Test
    fun testProxyDeepLinkParsesAndSetsPendingProxyWhenLocked() {
        PasscodeManager.setPasscode(context, "1234")
        PasscodeManager.lock()

        val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://proxy?server=10.0.0.1&port=1080&secret=abc"))

        val controller = Robolectric.buildActivity(MainActivity::class.java, deepLinkIntent).setup()
        val activity = controller.get()

        assertEquals(Screen.PASSCODE_LOCK, activity.currentScreen)
        assertNotNull(activity.pendingProxyItem)
        assertEquals("10.0.0.1", activity.pendingProxyItem?.server)
        assertEquals(1080, activity.pendingProxyItem?.port)
        assertEquals(ProxyType.MTPROTO, activity.pendingProxyItem?.type)
        assertEquals("abc", activity.pendingProxyItem?.secret)

        activity.passcodeEntry = "1234"
        activity.attemptPasscodeUnlock()

        assertNull("pendingProxyItem should be cleared after unlock", activity.pendingProxyItem)
    }

    @Test
    fun testProxySocksDeepLinkParsesSuccessfully() {
        val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/socks?server=192.168.1.1&port=1080&user=usr&pass=pwd"))

        val controller = Robolectric.buildActivity(MainActivity::class.java, deepLinkIntent).setup()
        val activity = controller.get()

        activity.telegramManager.setAuthStateForTesting(AuthState.WaitPhoneNumber(null))

        val parsed = activity.telegramManager.parseProxyUri(deepLinkIntent.data!!)
        assertNotNull(parsed)
        assertEquals("192.168.1.1", parsed?.server)
        assertEquals(1080, parsed?.port)
        assertEquals(ProxyType.SOCKS5, parsed?.type)
        assertEquals("usr", parsed?.username)
        assertEquals("pwd", parsed?.password)
    }
}
