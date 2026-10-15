package com.teleflip.app.ui

import android.content.Context
import com.teleflip.app.R

object ContentDescriptionFormatter {

    fun formatAvatarDescription(title: String?, formatPattern: String, fallbackString: String): String {
        val trimmed = title?.trim().orEmpty()
        return if (trimmed.isNotEmpty()) {
            try {
                String.format(formatPattern, trimmed)
            } catch (e: Exception) {
                fallbackString
            }
        } else {
            fallbackString
        }
    }

    fun formatPhotoDescription(caption: String?, formatPattern: String, fallbackString: String): String {
        val trimmed = caption?.trim().orEmpty()
        return if (trimmed.isNotEmpty()) {
            try {
                String.format(formatPattern, trimmed)
            } catch (e: Exception) {
                fallbackString
            }
        } else {
            fallbackString
        }
    }

    fun getAvatarDescription(context: Context, title: String?): String {
        return formatAvatarDescription(
            title = title,
            formatPattern = context.getString(R.string.cd_avatar_format),
            fallbackString = context.getString(R.string.cd_contact_avatar)
        )
    }

    fun getPhotoDescription(context: Context, caption: String?): String {
        return formatPhotoDescription(
            caption = caption,
            formatPattern = context.getString(R.string.cd_photo_attachment_format),
            fallbackString = context.getString(R.string.cd_photo_attachment)
        )
    }
}
