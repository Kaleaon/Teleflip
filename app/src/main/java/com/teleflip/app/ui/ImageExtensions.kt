package com.teleflip.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import coil.dispose
import coil.load
import coil.transform.CircleCropTransformation
import java.io.File

/**
 * Calculates an optimal [BitmapFactory.Options.inSampleSize] value based on requested display dimensions.
 */
fun calculateInSampleSize(
    options: BitmapFactory.Options,
    reqWidth: Int,
    reqHeight: Int,
): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1

    if (reqWidth <= 0 || reqHeight <= 0 || height <= 0 || width <= 0) {
        return inSampleSize
    }

    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2

        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }

    return inSampleSize
}

/**
 * Decodes a bitmap from file path with downsampling calculated using display bounds.
 */
fun decodeSampledBitmapFromFile(
    filePath: String,
    reqWidth: Int,
    reqHeight: Int,
): Bitmap? {
    return try {
        val file = File(filePath)
        if (!file.exists()) return null

        val options =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
        BitmapFactory.decodeFile(filePath, options)

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false

        BitmapFactory.decodeFile(filePath, options)
    } catch (_: Throwable) {
        null
    }
}

/**
 * Extension function for [ImageView] to handle loading circular avatars or falling back to letter initials.
 *
 * @param path The local file path to the avatar image.
 * @param fallbackLetters The letter initials to display if the avatar image is unavailable.
 * @param fallbackTextView Optional [TextView] used to display fallback letter initials.
 */
fun ImageView.loadCircularAvatarOrFallback(
    path: String?,
    fallbackLetters: String?,
    fallbackTextView: TextView?,
) {
    if (path.isNullOrEmpty()) {
        dispose()
        setImageDrawable(null)
        visibility = View.GONE
        fallbackTextView?.visibility = View.VISIBLE
        fallbackTextView?.text = fallbackLetters ?: ""
        return
    }

    try {
        val file = File(path)
        visibility = View.VISIBLE
        load(file) {
            transformations(CircleCropTransformation())
            listener(
                onSuccess = { _, _ ->
                    visibility = View.VISIBLE
                    fallbackTextView?.visibility = View.GONE
                },
                onError = { _, _ ->
                    visibility = View.GONE
                    fallbackTextView?.visibility = View.VISIBLE
                    fallbackTextView?.text = fallbackLetters ?: ""
                },
            )
        }
    } catch (_: Throwable) {
        visibility = View.GONE
        fallbackTextView?.visibility = View.VISIBLE
        fallbackTextView?.text = fallbackLetters ?: ""
    }
}

/**
 * Extension function for [ImageView] to load photos, stickers, and animated GIFs safely.
 */
fun ImageView.loadMediaFromFile(path: String?) {
    if (path.isNullOrEmpty()) {
        dispose()
        setImageDrawable(null)
        setImageDrawable(null)
        visibility = View.GONE
        return
    }

    try {
        val file = File(path)
        visibility = View.VISIBLE
        load(file) {
            listener(
                onSuccess = { _, _ ->
                    visibility = View.VISIBLE
                },
                onError = { _, _ ->
                    setImageDrawable(null)
                    visibility = View.GONE
                },
            )
        }
    } catch (_: Throwable) {
        setImageDrawable(null)
        visibility = View.GONE
    }
}
