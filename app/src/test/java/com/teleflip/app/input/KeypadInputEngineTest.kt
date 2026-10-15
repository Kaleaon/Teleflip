package com.teleflip.app.input

import android.text.Editable
import android.view.KeyEvent
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class KeypadInputEngineTest {

    private lateinit var editText: EditText
    private lateinit var editable: Editable
    private lateinit var modeIndicator: TextView
    private val sb = StringBuilder()

    @Before
    fun setUp() {
        sb.clear()
        editText = mock(EditText::class.java)
        modeIndicator = mock(TextView::class.java)
        editable = mock(Editable::class.java)

        `when`(editText.text).thenReturn(editable)
        `when`(editText.selectionStart).thenAnswer { sb.length }
        `when`(editText.selectionEnd).thenAnswer { sb.length }

        `when`(editable.toString()).thenAnswer { sb.toString() }
        `when`(editable.length).thenAnswer { sb.length }
        `when`(editable.get(anyInt())).thenAnswer { invocation ->
            val idx = invocation.arguments[0] as Int
            if (idx in 0 until sb.length) sb[idx] else '\u0000'
        }
        `when`(editable.replace(anyInt(), anyInt(), any(CharSequence::class.java), anyInt(), anyInt())).thenAnswer { invocation ->
            val st = invocation.arguments[0] as Int
            val en = invocation.arguments[1] as Int
            val seq = invocation.arguments[2] as CharSequence
            val srcSt = invocation.arguments[3] as Int
            val srcEn = invocation.arguments[4] as Int
            val sub = seq.subSequence(srcSt, srcEn).toString()
            sb.replace(st, en, sub)
            editable
        }
        `when`(editable.replace(anyInt(), anyInt(), any(CharSequence::class.java))).thenAnswer { invocation ->
            val st = invocation.arguments[0] as Int
            val en = invocation.arguments[1] as Int
            val seq = invocation.arguments[2] as CharSequence
            sb.replace(st, en, seq.toString())
            editable
        }
    }

    @Test
    fun testDefaultAndCustomTimeoutMs() {
        val engineDefault = KeypadInputEngine(editText, modeIndicator)
        assertEquals(500L, engineDefault.timeoutMs)

        val engineCustom = KeypadInputEngine(editText, modeIndicator, timeoutMs = 300L)
        assertEquals(300L, engineCustom.timeoutMs)

        engineCustom.timeoutMs = 900L
        assertEquals(900L, engineCustom.timeoutMs)
    }

    @Test
    fun testHandleDpadRightManualCommit() {
        val engine = KeypadInputEngine(editText, modeIndicator)
        val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        val eventUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)

        // Press DPAD_RIGHT without active buffer
        val handledDown = engine.handleKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, eventDown)
        val handledUp = engine.handleKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, eventUp)

        assertTrue("DPAD_RIGHT ACTION_DOWN should be handled", handledDown)
        assertTrue("DPAD_RIGHT ACTION_UP should be handled", handledUp)
    }

    @Test
    fun testDpadRightCommitsCharacterImmediatelyInCapFirstMode() {
        val engine = KeypadInputEngine(editText, modeIndicator, timeoutMs = 900L)

        // Press key '2' ('A' in CAP_FIRST)
        val key2Down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, key2Down)

        // Press DPAD_RIGHT to manually commit 'A'
        val dpadRightDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        val handledRight = engine.handleKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, dpadRightDown)
        assertTrue("DPAD_RIGHT should be handled for manual commit", handledRight)

        // Next press of key '2' immediately after manual commit
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, key2Down)

        assertEquals("Aa", sb.toString())
    }

    @Test
    fun testDpadRightCommitsCharacterImmediatelyInLowerMode() {
        val engine = KeypadInputEngine(editText, modeIndicator, timeoutMs = 900L)
        engine.setMode(InputMode.LOWER)

        // Press key '2' ('a' in LOWER)
        val key2Down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, key2Down)

        // Press DPAD_RIGHT to manually commit 'a'
        val dpadRightDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        engine.handleKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, dpadRightDown)

        // Next press of key '2' immediately after manual commit
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, key2Down)

        assertEquals("aa", sb.toString())
    }
}
