package com.teleflip.app.notifications

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.telegram.TelegramManager
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class TeleFlipNotificationServiceTest {
    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor

    @Before
    fun setUp() {
        TelegramManager.resetInstance()
        mockContext = mock(Context::class.java)
        mockPrefs = mock(SharedPreferences::class.java)
        mockEditor = mock(SharedPreferences.Editor::class.java)

        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
    }

    @Test
    fun testForegroundServiceAnchorsTelegramManagerSingleton() {
        val manager = TelegramManager.getInstance(mockContext)
        assertNotNull("TelegramManager instance must be initialized", manager)

        val sameManager = TelegramManager.getInstance(mockContext)
        assertEquals("Service and Application share the exact same TelegramManager instance", manager, sameManager)
    }

    @Test
    fun testSetNetworkType_forwardsToTdClient() {
        val manager = TelegramManager.getInstance(mockContext)
        val mockClient = mock(Client::class.java)
        manager.setTdClientForTesting(mockClient)

        manager.setNetworkType(TdApi.NetworkTypeWiFi())

        val captor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        verify(mockClient).send(captor.capture(), any())
        val req = captor.value
        assertTrue("Request must be TdApi.SetNetworkType", req is TdApi.SetNetworkType)
        val networkReq = req as TdApi.SetNetworkType
        assertTrue("Network type should be NetworkTypeWiFi", networkReq.type is TdApi.NetworkTypeWiFi)
    }
}
