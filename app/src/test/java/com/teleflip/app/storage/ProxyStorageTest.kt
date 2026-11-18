package com.teleflip.app.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.teleflip.app.model.ProxyItem
import com.teleflip.app.model.ProxyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class ProxyStorageTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ProxyStorage.clearCache()
        val prefs = context.getSharedPreferences("teleflip_proxy_secure_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        ProxyStorage.clearCache()
    }

    @Test
    fun testInMemoryCachingOnGetProxies() {
        val proxy =
            ProxyItem(
                server = "192.168.1.50",
                port = 1080,
                type = ProxyType.SOCKS5,
                username = "user1",
                password = "pass1",
                isEnabled = true,
            )
        ProxyStorage.saveProxy(context, proxy)

        // First call gets cached list
        val proxies1 = ProxyStorage.getProxies(context)
        val proxies2 = ProxyStorage.getProxies(context)

        // Verify identical object reference returned from in-memory cache
        assertSame("Second getProxies call must return cached list reference", proxies1, proxies2)
        assertEquals(1, proxies1.size)
        assertEquals("192.168.1.50", proxies1[0].server)
    }

    @Test
    fun testSecretKeyCaching() {
        val secretKeyField = ProxyStorage::class.java.getDeclaredField("cachedSecretKey")
        secretKeyField.isAccessible = true

        ProxyStorage.clearCache()
        assertNull("Secret key cache should be null after clearCache", secretKeyField.get(ProxyStorage))

        val encrypted = ProxyStorage.encrypt("Hello World")
        val key1 = secretKeyField.get(ProxyStorage) as? SecretKeySpec
        assertNotNull("Secret key should be cached after encrypt call", key1)

        val decrypted = ProxyStorage.decrypt(encrypted)
        val key2 = secretKeyField.get(ProxyStorage) as? SecretKeySpec

        assertSame("Secret key instance must be cached and reused", key1, key2)
        assertEquals("Hello World", decrypted)
    }

    @Test
    fun testWriteThroughCacheOnSaveProxy() {
        val proxy =
            ProxyItem(
                server = "proxy.example.com",
                port = 1080,
                type = ProxyType.SOCKS5,
                isEnabled = true,
            )
        val saved = ProxyStorage.saveProxy(context, proxy)

        // Verify in-memory immediately reflects saved proxy
        val inMemoryList = ProxyStorage.getProxies(context)
        assertEquals(1, inMemoryList.size)
        assertEquals(saved.id, inMemoryList[0].id)

        // Verify raw shared preferences was written
        val rawPrefs = context.getSharedPreferences("teleflip_proxy_secure_prefs", Context.MODE_PRIVATE)
        val encryptedVal = rawPrefs.getString("pref_encrypted_proxy_list", null)
        assertNotNull("Disk storage must contain encrypted payload", encryptedVal)

        // Clear cache and verify disk payload parses back identically
        ProxyStorage.clearCache()
        val reloadedList = ProxyStorage.getProxies(context)
        assertEquals(1, reloadedList.size)
        assertEquals(saved.id, reloadedList[0].id)
        assertEquals("proxy.example.com", reloadedList[0].server)
    }

    @Test
    fun testWriteThroughCacheOnEnableDisableDelete() {
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

        // Enable p2 -> cache and disk updated
        ProxyStorage.enableProxy(context, p2.id)
        assertEquals(p2.id, ProxyStorage.getEnabledProxy(context)?.id)

        // Disable all -> cache and disk updated
        ProxyStorage.disableAllProxies(context)
        assertNull(ProxyStorage.getEnabledProxy(context))

        // Delete p1 -> cache and disk updated
        ProxyStorage.deleteProxy(context, p1.id)
        val remaining = ProxyStorage.getProxies(context)
        assertEquals(1, remaining.size)
        assertEquals(p2.id, remaining[0].id)

        // Verify disk state after clearing cache
        ProxyStorage.clearCache()
        val reloaded = ProxyStorage.getProxies(context)
        assertEquals(1, reloaded.size)
        assertEquals(p2.id, reloaded[0].id)
        assertFalse(reloaded[0].isEnabled)
    }

    @Test
    fun testClearCacheMethod() {
        val proxy = ProxyItem(server = "clear.test.com", port = 1080, type = ProxyType.SOCKS5)
        ProxyStorage.saveProxy(context, proxy)

        val cached1 = ProxyStorage.getProxies(context)

        ProxyStorage.clearCache()

        val reloaded = ProxyStorage.getProxies(context)
        assertEquals(1, reloaded.size)
        assertEquals("clear.test.com", reloaded[0].server)

        // `reloaded` should be a new list instance populated from SharedPreferences
        assertTrue("New list instance should be created after clearCache", cached1 !== reloaded)
    }

    @Test
    fun testConcurrentReadsAndWrites() {
        val threadCount = 8
        val executor = Executors.newFixedThreadPool(threadCount)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

        for (i in 0 until 50) {
            val index = i
            executor.execute {
                try {
                    val item =
                        ProxyItem(
                            server = "server$index.com",
                            port = 1000 + index,
                            type = ProxyType.SOCKS5,
                            isEnabled = (index % 2 == 0),
                        )
                    ProxyStorage.saveProxy(context, item)
                    ProxyStorage.getProxies(context)
                    ProxyStorage.getEnabledProxy(context)
                    if (index % 5 == 0) {
                        ProxyStorage.clearCache()
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }

        executor.shutdown()
        val finished = executor.awaitTermination(10, TimeUnit.SECONDS)
        assertTrue("Concurrent operations should complete in time", finished)
        assertTrue("No thread exceptions should occur during concurrent access: ${errors.peek()}", errors.isEmpty())
    }
}
