package com.teleflip.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object QrCodeGenerator {
    /**
     * Generates a square QR code Bitmap from the given [text] URI string asynchronously on [Dispatchers.Default].
     */
    suspend fun generateQrCodeBitmapAsync(
        text: String,
        size: Int = 512,
    ): Bitmap? =
        withContext(Dispatchers.Default) {
            generateQrCodeBitmapInternal(text, size)
        }

    /**
     * Generates a square QR code Bitmap from the given [text] URI string synchronously.
     * Maintained for backward compatibility.
     */
    fun generateQrCodeBitmap(
        text: String,
        size: Int = 512,
    ): Bitmap? {
        return generateQrCodeBitmapInternal(text, size)
    }

    private fun generateQrCodeBitmapInternal(
        text: String,
        size: Int = 512,
    ): Bitmap? {
        if (text.isBlank()) return null
        return try {
            val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                val offset = y * size
                for (x in 0 until size) {
                    pixels[offset + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            bitmap
        } catch (e: Exception) {
            com.teleflip.app.report.AdbReporter.reportError("QrCodeGenerator", "Failed to generate QR code bitmap: ${e.message}")
            null
        }
    }
}
