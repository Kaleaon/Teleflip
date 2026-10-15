package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

class TelegramManagerTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        prefs = mock(SharedPreferences::class.java)
        editor = mock(SharedPreferences.Editor::class.java)

        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.putLong(anyString(), anyLong())).thenReturn(editor)
    }

    @Test
    fun testKeypadTimeoutMsDefaultValue() {
        `when`(prefs.getLong(eq("keypad_timeout_ms"), anyLong())).thenReturn(500L)

        val manager = TelegramManager(context)
        assertEquals(500L, manager.keypadTimeoutMs)
    }

    @Test
    fun testKeypadTimeoutMsInvalidFallback() {
        `when`(prefs.getLong(eq("keypad_timeout_ms"), anyLong())).thenReturn(1200L)

        val manager = TelegramManager(context)
        assertEquals(500L, manager.keypadTimeoutMs)
    }

    @Test
    fun testKeypadTimeoutMsValidSelections() {
        val manager = TelegramManager(context)

        manager.keypadTimeoutMs = 300L
        verify(editor).putLong("keypad_timeout_ms", 300L)

        manager.keypadTimeoutMs = 900L
        verify(editor).putLong("keypad_timeout_ms", 900L)

        manager.keypadTimeoutMs = 750L // invalid value fallback
        verify(editor).putLong("keypad_timeout_ms", 500L)
    }
}
