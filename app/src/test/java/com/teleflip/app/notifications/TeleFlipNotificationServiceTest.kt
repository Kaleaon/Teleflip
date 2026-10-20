package com.teleflip.app.notifications

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.telegram.TelegramManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
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
}
