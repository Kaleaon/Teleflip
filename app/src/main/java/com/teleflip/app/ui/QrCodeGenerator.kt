package com.teleflip.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

object QrCodeGenerator {
    /**
     * Generates a square QR code Bitmap from the given [text] URI string.
     */
    fun generateQrCodeBitmap(
        text: String,
        size: Int = 512,
    ): Bitmap? {
        if (text.isBlank()) return null
        return try {
            val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            com.teleflip.app.report.AdbReporter.reportError("QrCodeGenerator", "Failed to generate QR code bitmap: ${e.message}")
            null
        }
    }
}
