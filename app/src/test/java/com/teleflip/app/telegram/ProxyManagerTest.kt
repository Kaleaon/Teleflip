package com.teleflip.app.telegram

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.teleflip.app.model.ProxyItem
import com.teleflip.app.model.ProxyType
import com.teleflip.app.storage.ProxyStorage
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProxyManagerTest {
    private lateinit var context: Context
    private lateinit var telegramManager: TelegramManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ProxyStorage.disableAllProxies(context)
        ProxyStorage.getProxies(context).forEach {
            ProxyStorage.deleteProxy(context, it.id)
        }
        telegramManager = TelegramManager(context)
    }

    @Test
    fun testProxyCredentialsEncryptionInPreferences() {
        val proxy =
            ProxyItem(
                server = "192.168.1.100",
                port = 1080,
                type = ProxyType.SOCKS5,
                username = "secretuser",
                password = "supersecretpassword123",
                isEnabled = true,
            )
        ProxyStorage.saveProxy(context, proxy)

        val rawPrefs = context.getSharedPreferences("teleflip_proxy_secure_prefs", Context.MODE_PRIVATE)
        val encryptedVal = rawPrefs.getString("pref_encrypted_proxy_list", "") ?: ""

        assertNotNull(encryptedVal)
        assertTrue("Encrypted payload must not be blank", encryptedVal.isNotBlank())
        assertFalse(
            "Plaintext password must not appear in raw shared preferences",
            encryptedVal.contains("supersecretpassword123"),
        )

        val proxies = ProxyStorage.getProxies(context)
        assertEquals(1, proxies.size)
        assertEquals("secretuser", proxies[0].username)
        assertEquals("supersecretpassword123", proxies[0].password)
        assertEquals("192.168.1.100", proxies[0].server)
    }

    @Test
    fun testAddSocks5AndMtprotoProxies() {
        val socksProxy =
            ProxyItem(
                server = "socks.proxy.com",
                port = 1080,
                type = ProxyType.SOCKS5,
                username = "socksuser",
                password = "sockspassword",
                isEnabled = true,
            )
        ProxyStorage.saveProxy(context, socksProxy)

        val enabled = ProxyStorage.getEnabledProxy(context)
        assertNotNull(enabled)
        assertEquals(ProxyType.SOCKS5, enabled?.type)
        assertEquals("socks.proxy.com", enabled?.server)

        val mtprotoProxy =
            ProxyItem(
                server = "mtproto.proxy.com",
                port = 443,
                type = ProxyType.MTPROTO,
                secret = "ee123456789012345678901234567890",
                isEnabled = true,
            )
        ProxyStorage.saveProxy(context, mtprotoProxy)

        val newEnabled = ProxyStorage.getEnabledProxy(context)
        assertNotNull(newEnabled)
        assertEquals(ProxyType.MTPROTO, newEnabled?.type)
        assertEquals("mtproto.proxy.com", newEnabled?.server)

        val allProxies = ProxyStorage.getProxies(context)
        assertEquals(2, allProxies.size)
    }

    @Test
    fun testEnableDisableDeleteProxy() {
        val p1 =
            ProxyStorage.saveProxy(
                context,
                ProxyItem(server = "p1.com", port = 1080, type = ProxyType.SOCKS5, isEnabled = true),
            )
        val p2 =
            ProxyStorage.saveProxy(
                context,
                ProxyItem(server = "p2.com", port = 1080, type = ProxyType.SOCKS5, isEnabled = false),
            )

        assertEquals(p1.id, ProxyStorage.getEnabledProxy(context)?.id)

        ProxyStorage.enableProxy(context, p2.id)
        assertEquals(p2.id, ProxyStorage.getEnabledProxy(context)?.id)

        ProxyStorage.disableAllProxies(context)
        assertNull(ProxyStorage.getEnabledProxy(context))

        ProxyStorage.deleteProxy(context, p1.id)
        assertEquals(1, ProxyStorage.getProxies(context).size)
        assertEquals(p2.id, ProxyStorage.getProxies(context)[0].id)
    }

    @Test
    fun testTelegramManagerDispatchesAddAndEnableProxy() {
        val mockClient = mock(Client::class.java)
        telegramManager.setTdClientForTesting(mockClient)

        val latch = java.util.concurrent.CountDownLatch(1)
        val queryCaptor = ArgumentCaptor.forClass(TdApi.Function::class.java)

        telegramManager.addProxy(
            server = "proxy.telegram.org",
            port = 443,
            type = ProxyType.MTPROTO,
            secret = "1234567890abcdef1234567890abcdef",
            enableNow = true,
            callback = { _, _ -> latch.countDown() },
        )

        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)

        verify(mockClient).send(queryCaptor.capture(), any())
        val query = queryCaptor.value
        assertTrue("Captured function should be TdApi.AddProxy", query is TdApi.AddProxy)
        val addProxyQuery = query as TdApi.AddProxy
        assertEquals("proxy.telegram.org", addProxyQuery.proxy.server)
        assertEquals(443, addProxyQuery.proxy.port)
        assertTrue(addProxyQuery.enable)
        assertTrue("Proxy type should be MTProto", addProxyQuery.proxy.type is TdApi.ProxyTypeMtproto)
        val mtprotoType = addProxyQuery.proxy.type as TdApi.ProxyTypeMtproto
        assertEquals("1234567890abcdef1234567890abcdef", mtprotoType.secret)
    }

    @Test
    fun testTelegramManagerConnectionStateOnlineViaProxy() {
        ProxyStorage.saveProxy(
            context,
            ProxyItem(server = "10.0.0.1", port = 1080, type = ProxyType.SOCKS5, isEnabled = true),
        )

        val mockClient = mock(Client::class.java)
        telegramManager.setTdClientForTesting(mockClient)

        val handleMethod =
            TelegramManager::class.java.getDeclaredMethod(
                "handleConnectionState",
                TdApi.ConnectionState::class.java,
            )
        handleMethod.isAccessible = true
        handleMethod.invoke(telegramManager, TdApi.ConnectionStateReady())

        assertEquals("Online via Proxy", telegramManager.connectionState.value)
    }

    @Test
    fun testParseProxyUriAndUrl() {
        val mtprotoTgUri = android.net.Uri.parse("tg://proxy?server=1.2.3.4&port=1080&secret=ee12345")
        val parsed1 = telegramManager.parseProxyUri(mtprotoTgUri)
        assertNotNull(parsed1)
        assertEquals("1.2.3.4", parsed1?.server)
        assertEquals(1080, parsed1?.port)
        assertEquals(ProxyType.MTPROTO, parsed1?.type)
        assertEquals("ee12345", parsed1?.secret)

        val socksTmeUrl = "https://t.me/socks?server=5.6.7.8&port=1080&user=usr&pass=pwd"
        val parsed2 = telegramManager.parseProxyUrl(socksTmeUrl)
        assertNotNull(parsed2)
        assertEquals("5.6.7.8", parsed2?.server)
        assertEquals(1080, parsed2?.port)
        assertEquals(ProxyType.SOCKS5, parsed2?.type)
        assertEquals("usr", parsed2?.username)
        assertEquals("pwd", parsed2?.password)
    }

    @Test
    fun testPingProxyDispatchesPingQuery() {
        val mockClient = mock(Client::class.java)
        var capturedPingLatency: Long? = null
        val latch = java.util.concurrent.CountDownLatch(1)

        org.mockito.Mockito.`doAnswer` { invocation ->
            val handler = invocation.getArgument<Client.ResultHandler>(1)
            handler.onResult(TdApi.Seconds(0.25))
            null
        }.`when`(mockClient).send(any(), any())

        telegramManager.setTdClientForTesting(mockClient)

        val proxyItem = ProxyItem(server = "1.2.3.4", port = 1080, type = ProxyType.SOCKS5)
        val queryCaptor = ArgumentCaptor.forClass(TdApi.Function::class.java)

        telegramManager.pingProxy(proxyItem) { latency ->
            capturedPingLatency = latency
            latch.countDown()
        }

        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        verify(mockClient).send(queryCaptor.capture(), any())
        val query = queryCaptor.value
        assertTrue("Captured function should be TdApi.PingProxy", query is TdApi.PingProxy)
        val pingQuery = query as TdApi.PingProxy
        assertEquals("1.2.3.4", pingQuery.proxy.server)
        assertEquals(1080, pingQuery.proxy.port)
        assertEquals(250L, capturedPingLatency)
    }
}
