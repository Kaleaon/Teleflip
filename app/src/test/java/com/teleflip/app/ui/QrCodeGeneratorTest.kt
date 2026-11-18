package com.teleflip.app.ui

import kotlinx.coroutines.runBlocking
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

    @Test
    fun testGenerateQrCodeBitmapAsyncWithValidText() {
        runBlocking {
            val testLink = "tg://login?token=AQAA_test_token_123"
            val bitmap = QrCodeGenerator.generateQrCodeBitmapAsync(testLink, 256)

            assertNotNull("Bitmap should be generated asynchronously for valid text", bitmap)
            assertEquals(256, bitmap?.width)
            assertEquals(256, bitmap?.height)
        }
    }

    @Test
    fun testGenerateQrCodeBitmapAsyncWithBlankTextReturnsNull() {
        runBlocking {
            val bitmap = QrCodeGenerator.generateQrCodeBitmapAsync("  ", 256)
            assertNull("Bitmap should be null asynchronously when text is blank", bitmap)
        }
    }
}
