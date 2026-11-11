package com.teleflip.app.telegram

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.teleflip.app.model.ProxyItem
import com.teleflip.app.model.ProxyType
import com.teleflip.app.storage.ProxyStorage
import kotlinx.coroutines.runBlocking
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
    fun testPingProxyDispatchesTdApiPingProxy() {
        val mockClient = mock(Client::class.java)
        telegramManager.setTdClientForTesting(mockClient)

        val proxy = ProxyItem(server = "ping.proxy.com", port = 1080, type = ProxyType.SOCKS5)

        val queryCaptor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        val answer = org.mockito.stubbing.Answer { invocation ->
            val resultHandler = invocation.getArgument(1) as Client.ResultHandler
            resultHandler.onResult(TdApi.Seconds(0.150))
            null
        }
        org.mockito.Mockito.doAnswer(answer).`when`(mockClient).send(queryCaptor.capture(), any())

        var pingMs: Long? = null
        runBlocking {
            pingMs = telegramManager.pingProxy(proxy)
        }

        assertNotNull(pingMs)
        assertEquals(150L, pingMs)

        val query = queryCaptor.value
        assertTrue("Captured function should be TdApi.PingProxy", query is TdApi.PingProxy)
        val pingQuery = query as TdApi.PingProxy
        assertEquals("ping.proxy.com", pingQuery.proxy.server)
        assertEquals(1080, pingQuery.proxy.port)
    }

    @Test
    fun testAutoProxyFailoverSelectsLowestLatencyProxy() {
        ProxyStorage.saveProxy(
            context,
            ProxyItem(server = "slow.proxy.com", port = 1080, type = ProxyType.SOCKS5, isEnabled = false),
        )
        ProxyStorage.saveProxy(
            context,
            ProxyItem(server = "fast.proxy.com", port = 1080, type = ProxyType.SOCKS5, isEnabled = false),
        )

        val mockClient = mock(Client::class.java)
        telegramManager.setTdClientForTesting(mockClient)

        val answer = org.mockito.stubbing.Answer { invocation ->
            val function = invocation.getArgument<TdApi.Function<*>>(0)
            val resultHandler = invocation.getArgument<Client.ResultHandler>(1)
            if (function is TdApi.PingProxy) {
                if (function.proxy.server == "slow.proxy.com") {
                    resultHandler.onResult(TdApi.Seconds(0.500))
                } else if (function.proxy.server == "fast.proxy.com") {
                    resultHandler.onResult(TdApi.Seconds(0.100))
                }
            } else if (function is TdApi.AddProxy) {
                resultHandler.onResult(TdApi.AddedProxy().apply { id = function.proxy.port })
            } else if (function is TdApi.EnableProxy) {
                resultHandler.onResult(TdApi.Ok())
            }
            null
        }
        org.mockito.Mockito.doAnswer(answer).`when`(mockClient).send(any(), any())

        val latch = java.util.concurrent.CountDownLatch(1)
        var selectedProxy: ProxyItem? = null
        var selectedLatency: Long? = null

        telegramManager.runAutoProxyFailover { proxy, latency ->
            selectedProxy = proxy
            selectedLatency = latency
            latch.countDown()
        }

        assertTrue(latch.await(4, java.util.concurrent.TimeUnit.SECONDS))
        assertNotNull(selectedProxy)
        assertEquals("fast.proxy.com", selectedProxy?.server)
        assertEquals(100L, selectedLatency)
        assertEquals(selectedProxy?.id, ProxyStorage.getEnabledProxy(context)?.id)
        assertNotNull(telegramManager.failoverStatus.value)
        assertTrue(telegramManager.failoverStatus.value!!.contains("Auto-selected Proxy"))
    }

    @Test
    fun testAutoProxyFailoverDisabledManualOverride() {
        ProxyStorage.setAutoProxyFailoverEnabled(context, false)
        assertFalse(ProxyStorage.isAutoProxyFailoverEnabled(context))

        ProxyStorage.saveProxy(
            context,
            ProxyItem(server = "test.proxy.com", port = 1080, type = ProxyType.SOCKS5),
        )

        val latch = java.util.concurrent.CountDownLatch(1)
        var callbackExecuted = false

        telegramManager.runAutoProxyFailover { proxy, _ ->
            callbackExecuted = true
            assertNull(proxy)
            latch.countDown()
        }

        assertTrue(latch.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(callbackExecuted)
        assertEquals("Auto failover disabled in settings", telegramManager.failoverStatus.value)
    }

    @Test
    fun testDeepLinkProxyAutoVerificationAndActivation() {
        val mockClient = mock(Client::class.java)
        telegramManager.setTdClientForTesting(mockClient)

        val answer = org.mockito.stubbing.Answer { invocation ->
            val function = invocation.getArgument<TdApi.Function<*>>(0)
            val resultHandler = invocation.getArgument<Client.ResultHandler>(1)
            if (function is TdApi.PingProxy) {
                resultHandler.onResult(TdApi.Seconds(0.120))
            } else if (function is TdApi.AddProxy) {
                resultHandler.onResult(TdApi.AddedProxy().apply { id = 101 })
            } else if (function is TdApi.EnableProxy) {
                resultHandler.onResult(TdApi.Ok())
            }
            null
        }
        org.mockito.Mockito.doAnswer(answer).`when`(mockClient).send(any(), any())

        val uri = android.net.Uri.parse("tg://proxy?server=deeplink.proxy.org&port=1080&user=testuser&pass=testpass")

        val latch = java.util.concurrent.CountDownLatch(1)
        var successResult = false

        telegramManager.handleDeepLinkProxy(uri) { success, _ ->
            successResult = success
            latch.countDown()
        }

        assertTrue(latch.await(4, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(successResult)

        val enabled = ProxyStorage.getEnabledProxy(context)
        assertNotNull(enabled)
        assertEquals("deeplink.proxy.org", enabled?.server)
        assertEquals(1080, enabled?.port)
        assertEquals("testuser", enabled?.username)
        assertTrue(telegramManager.failoverStatus.value!!.contains("Proxy deep-link activated"))
    }
}
