package com.teleflip.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.telegram.PhoneFormat.PhoneFormat

class PhoneFormatTest {

    @Test
    fun testStrip() {
        val input = "+1 (555) 123-4567 #12"
        val expected = "+15551234567#12"
        assertEquals(expected, PhoneFormat.strip(input))
    }

    @Test
    fun testStripExceptNumbersWithoutPlus() {
        val input = "+1 (555) 123-4567"
        val expected = "15551234567"
        assertEquals(expected, PhoneFormat.stripExceptNumbers(input, false))
    }

    @Test
    fun testStripExceptNumbersWithPlus() {
        val input = "+1 (555) 123-4567"
        val expected = "+15551234567"
        assertEquals(expected, PhoneFormat.stripExceptNumbers(input, true))
    }

    @Test
    fun testStripExceptNumbersNull() {
        assertNull(PhoneFormat.stripExceptNumbers(null, false))
    }
}
