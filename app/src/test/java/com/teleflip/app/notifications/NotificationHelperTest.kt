package com.teleflip.app.notifications

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class NotificationHelperTest {
    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        NotificationHelper.channelsCreated = false
    }

    @Test
    fun testEnsureChannelsRegistersChannelsOnFirstExecution() {
        assertFalse(NotificationHelper.channelsCreated)

        NotificationHelper.ensureChannels(context)

        assertTrue(NotificationHelper.channelsCreated)
        assertNotNull(notificationManager.getNotificationChannel(NotificationHelper.CHANNEL_MESSAGES))
        assertNotNull(notificationManager.getNotificationChannel(NotificationHelper.CHANNEL_CALLS))
        assertNotNull(notificationManager.getNotificationChannel(NotificationHelper.CHANNEL_BACKGROUND_SYNC))
    }

    @Test
    fun testEnsureChannelsReturnsImmediatelyOnSubsequentCalls() {
        NotificationHelper.ensureChannels(context)
        assertTrue(NotificationHelper.channelsCreated)

        val spyContext = spy(context)
        NotificationHelper.ensureChannels(spyContext)

        verify(spyContext, never()).getSystemService(Context.NOTIFICATION_SERVICE)
    }

    @Test
    fun testEnsureChannelsRetriesWhenNotificationManagerIsNull() {
        val mockContext = mock(Context::class.java)
        `when`(mockContext.getSystemService(Context.NOTIFICATION_SERVICE)).thenReturn(null)

        NotificationHelper.ensureChannels(mockContext)

        assertFalse(NotificationHelper.channelsCreated)
    }

    @Test
    fun testEnsureChannelsThreadSafety() {
        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        repeat(threadCount) {
            executor.execute {
                try {
                    NotificationHelper.ensureChannels(context)
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        assertTrue(NotificationHelper.channelsCreated)
        assertEquals(3, notificationManager.notificationChannels.size)
    }
}
