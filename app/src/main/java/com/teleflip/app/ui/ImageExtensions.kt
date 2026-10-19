package com.teleflip.app.ui

import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import coil.load
import java.io.File

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
    if (!path.isNullOrEmpty()) {
        try {
            val file = File(path)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap != null) {
                    val circularDrawable =
                        RoundedBitmapDrawableFactory.create(resources, bitmap).apply {
                            isCircular = true
                        }
                    setImageDrawable(circularDrawable)
                    visibility = View.VISIBLE
                    fallbackTextView?.visibility = View.GONE
                    return
                }
            }
        } catch (e: Throwable) {
            // Safely fallback on decoding or memory allocation failure
        }
    }
    visibility = View.GONE
    fallbackTextView?.visibility = View.VISIBLE
    fallbackTextView?.text = fallbackLetters ?: ""
}

/**
 * Extension function for [ImageView] to load photos, stickers, and animated GIFs safely.
 */
fun ImageView.loadMediaFromFile(path: String?) {
    if (!path.isNullOrEmpty()) {
        try {
            val file = File(path)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap != null) {
                    setImageBitmap(bitmap)
                    visibility = View.VISIBLE
                    return
                } else {
                    load(file)
                    visibility = View.VISIBLE
                    return
                }
            }
        } catch (_: Throwable) {
        }
    }
    setImageDrawable(null)
    visibility = View.GONE
}
