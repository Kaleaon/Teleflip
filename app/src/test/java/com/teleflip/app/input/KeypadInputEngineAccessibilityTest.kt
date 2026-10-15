package com.teleflip.app.input

import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class KeypadInputEngineAccessibilityTest {
    @Test
    fun testModeIndicatorAnnouncesOnPoundKey() {
        val mockEditText = mock(EditText::class.java)
        val mockModeIndicator = mock(TextView::class.java)
        val editable = SpannableStringBuilder("")
        `when`(mockEditText.text).thenReturn(editable)

        val engine = KeypadInputEngine(mockEditText, mockModeIndicator)

        // Initially in CAP_FIRST ("Abc")
        assertEquals(InputMode.CAP_FIRST, engine.currentMode)
        verify(mockModeIndicator).announceForAccessibility("Input mode Abc")

        // Press # key to cycle to LOWER ("abc")
        val poundDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POUND)
        engine.handleKeyEvent(KeyEvent.KEYCODE_POUND, poundDown)

        assertEquals(InputMode.LOWER, engine.currentMode)
        verify(mockModeIndicator).announceForAccessibility("Input mode abc")

        // Press # key again to cycle to UPPER ("ABC")
        engine.handleKeyEvent(KeyEvent.KEYCODE_POUND, poundDown)
        assertEquals(InputMode.UPPER, engine.currentMode)
        verify(mockModeIndicator).announceForAccessibility("Input mode ABC")

        // Press # key again to cycle to NUMERIC ("123")
        engine.handleKeyEvent(KeyEvent.KEYCODE_POUND, poundDown)
        assertEquals(InputMode.NUMERIC, engine.currentMode)
        verify(mockModeIndicator).announceForAccessibility("Input mode 123")
    }
}
