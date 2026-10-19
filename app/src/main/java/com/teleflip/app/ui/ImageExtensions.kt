package com.teleflip.app.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import coil.load
import coil.transform.CircleCropTransformation
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
    if (path.isNullOrEmpty()) {
        visibility = View.GONE
        fallbackTextView?.visibility = View.VISIBLE
        fallbackTextView?.text = fallbackLetters ?: ""
        return
    }

    try {
        val file = File(path)
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
 * Extension function for [ImageView] to handle loading standard image attachments from file paths safely.
 *
 * @param path The local file path to the image file.
 */
fun ImageView.loadBitmapFromFile(path: String?) {
    if (path.isNullOrEmpty()) {
        visibility = View.GONE
        return
    }

    try {
        val file = File(path)
        load(file) {
            listener(
                onSuccess = { _, _ ->
                    visibility = View.VISIBLE
                },
                onError = { _, _ ->
                    visibility = View.GONE
                },
            )
        }
    } catch (_: Throwable) {
        visibility = View.GONE
    }
}

/**
 * Extension function for [ImageView] to load photos, stickers, and animated GIFs safely.
 */
fun ImageView.loadMediaFromFile(path: String?) {
    if (path.isNullOrEmpty()) {
        setImageDrawable(null)
        visibility = View.GONE
        return
    }

    try {
        val file = File(path)
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
