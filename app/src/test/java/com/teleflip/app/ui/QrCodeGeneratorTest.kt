package com.teleflip.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QrCodeGeneratorTest {
    @Test
    fun testGenerateQrCodeBitmapWithValidText() {
        val testLink = "tg://login?token=AQAA_test_token_123"
        val bitmap = QrCodeGenerator.generateQrCodeBitmap(testLink, 256)

        assertNotNull("Bitmap should be generated for valid text", bitmap)
        assertEquals(256, bitmap?.width)
        assertEquals(256, bitmap?.height)
    }

    @Test
    fun testGenerateQrCodeBitmapWithBlankTextReturnsNull() {
        val bitmap = QrCodeGenerator.generateQrCodeBitmap("  ", 256)
        assertNull("Bitmap should be null when text is blank", bitmap)
    }
}
