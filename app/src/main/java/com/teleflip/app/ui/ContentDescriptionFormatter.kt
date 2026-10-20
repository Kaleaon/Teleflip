package com.teleflip.app.ui

import android.content.Context
import com.teleflip.app.R
import java.util.Locale

object ContentDescriptionFormatter {
    fun formatAvatarDescription(
        title: String?,
        formatPattern: String,
        fallbackString: String,
    ): String {
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

    fun formatPhotoDescription(
        caption: String?,
        formatPattern: String,
        fallbackString: String,
    ): String {
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

    fun getAvatarDescription(
        context: Context,
        title: String?,
    ): String {
        return formatAvatarDescription(
            title = title,
            formatPattern = context.getString(R.string.cd_avatar_format),
            fallbackString = context.getString(R.string.cd_contact_avatar),
        )
    }

    fun getPhotoDescription(
        context: Context,
        caption: String?,
    ): String {
        return formatPhotoDescription(
            caption = caption,
            formatPattern = context.getString(R.string.cd_photo_attachment_format),
            fallbackString = context.getString(R.string.cd_photo_attachment),
        )
    }

    fun getVoiceStateDescription(
        context: Context,
        isPlaying: Boolean,
        speedStr: String,
        durationSeconds: Int,
    ): String {
        return if (isPlaying) {
            context.getString(R.string.cd_voice_state_playing, speedStr)
        } else {
            context.getString(R.string.cd_voice_state_paused, durationSeconds)
        }
    }

    fun getVoiceMessageDescription(
        context: Context,
        isPlaying: Boolean,
        durationSeconds: Int,
    ): String {
        val stateText = if (isPlaying) "Playing" else "Paused"
        return context.getString(R.string.cd_voice_message_format, stateText, durationSeconds)
    }

    fun getZoomDescription(
        context: Context,
        zoomLevel: Float,
    ): String {
        val zoomStr = String.format(Locale.US, "%.1fx", zoomLevel)
        return context.getString(R.string.cd_zoom_scale_format, zoomStr)
    }
}
