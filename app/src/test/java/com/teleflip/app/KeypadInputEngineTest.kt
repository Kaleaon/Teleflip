package com.teleflip.app

import com.teleflip.app.input.InputMode
import com.teleflip.app.input.KeypadInputEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class KeypadInputEngineTest {

    @Test
    fun testInputModeCycle() {
        var mode = InputMode.CAP_FIRST
        assertEquals("Abc", mode.label)

        mode = mode.next()
        assertEquals(InputMode.LOWER, mode)
        assertEquals("abc", mode.label)

        mode = mode.next()
        assertEquals(InputMode.UPPER, mode)
        assertEquals("ABC", mode.label)

        mode = mode.next()
        assertEquals(InputMode.NUMERIC, mode)
        assertEquals("123", mode.label)

        mode = mode.next()
        assertEquals(InputMode.CAP_FIRST, mode)
    }

    @Test
    fun testInputModeValues() {
        val modes = InputMode.values()
        assertEquals(4, modes.size)
        assertEquals(InputMode.CAP_FIRST, InputMode.valueOf("CAP_FIRST"))
        assertEquals(InputMode.LOWER, InputMode.valueOf("LOWER"))
        assertEquals(InputMode.UPPER, InputMode.valueOf("UPPER"))
        assertEquals(InputMode.NUMERIC, InputMode.valueOf("NUMERIC"))
    }
}
