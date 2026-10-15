package com.teleflip.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelephonyGettersTest {

    @Test
    fun testCleanAndFormatPhoneNumber_validInputs() {
        assertEquals("+15551234567", MainActivity.cleanAndFormatPhoneNumber("15551234567"))
        assertEquals("+15551234567", MainActivity.cleanAndFormatPhoneNumber("+1 555 123 4567"))
        assertEquals("+15551234567", MainActivity.cleanAndFormatPhoneNumber("+1 (555) 123-4567"))
        assertEquals("+447911123456", MainActivity.cleanAndFormatPhoneNumber("+44 7911 123456"))
    }

    @Test
    fun testCleanAndFormatPhoneNumber_invalidAndBlankInputs() {
        assertNull(MainActivity.cleanAndFormatPhoneNumber(null))
        assertNull(MainActivity.cleanAndFormatPhoneNumber(""))
        assertNull(MainActivity.cleanAndFormatPhoneNumber("   "))
        assertNull(MainActivity.cleanAndFormatPhoneNumber("abcdef"))
        assertNull(MainActivity.cleanAndFormatPhoneNumber("---"))
    }
}
