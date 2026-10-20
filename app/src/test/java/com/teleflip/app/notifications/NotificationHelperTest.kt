package com.teleflip.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class NotificationHelperTest {
    @Before
    fun setUp() {
        NotificationHelper.resetChannelsCreatedForTests()
    }

    @Test
    fun testEnsureChannelsRegistersChannelsOnceAndIsIdempotent() {
        val context = mock(Context::class.java)
        val notificationManager = mock(NotificationManager::class.java)
        `when`(context.getSystemService(Context.NOTIFICATION_SERVICE)).thenReturn(notificationManager)

        NotificationHelper.ensureChannels(context)
        NotificationHelper.ensureChannels(context)
        NotificationHelper.ensureChannels(context)

        @Suppress("UNCHECKED_CAST")
        val captor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<NotificationChannel>>
        verify(notificationManager, times(1)).createNotificationChannels(captor.capture())

        val channels = captor.value
        assertEquals(3, channels.size)
        val channelIds = channels.map { it.id }.toSet()
        assertTrue(channelIds.contains(NotificationHelper.CHANNEL_MESSAGES))
        assertTrue(channelIds.contains(NotificationHelper.CHANNEL_CALLS))
        assertTrue(channelIds.contains(NotificationHelper.CHANNEL_BACKGROUND_SYNC))
    }

    @Test
    fun testEnsureChannelsWithNullNotificationManagerDoesNotSetGuard() {
        val context = mock(Context::class.java)
        `when`(context.getSystemService(Context.NOTIFICATION_SERVICE)).thenReturn(null)

        NotificationHelper.ensureChannels(context)

        val notificationManager = mock(NotificationManager::class.java)
        `when`(context.getSystemService(Context.NOTIFICATION_SERVICE)).thenReturn(notificationManager)

        NotificationHelper.ensureChannels(context)
        verify(notificationManager, times(1)).createNotificationChannels(anyList())
    }

    @Test
    fun testEnsureChannelsIsThreadSafe() {
        val context = mock(Context::class.java)
        val notificationManager = mock(NotificationManager::class.java)
        `when`(context.getSystemService(Context.NOTIFICATION_SERVICE)).thenReturn(notificationManager)

        val threadCount = 10
        val service = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        repeat(threadCount) {
            service.submit {
                try {
                    startLatch.await()
                    NotificationHelper.ensureChannels(context)
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        doneLatch.await()
        service.shutdown()

        verify(notificationManager, times(1)).createNotificationChannels(anyList())
    }

    @Test
    fun testRobolectricEnsureChannelsIntegration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        NotificationHelper.ensureChannels(context)

        val channelMessages = notificationManager.getNotificationChannel(NotificationHelper.CHANNEL_MESSAGES)
        val channelCalls = notificationManager.getNotificationChannel(NotificationHelper.CHANNEL_CALLS)
        val channelSync = notificationManager.getNotificationChannel(NotificationHelper.CHANNEL_BACKGROUND_SYNC)

        assertNotNull(channelMessages)
        assertNotNull(channelCalls)
        assertNotNull(channelSync)

        assertEquals("Telegram Messages", channelMessages.name)
        assertEquals("Telegram Voice Calls", channelCalls.name)
        assertEquals("Background Flip-Closed Push Sync", channelSync.name)
    }
}
