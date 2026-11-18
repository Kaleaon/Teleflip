package com.teleflip.app.ui

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import com.teleflip.app.model.MessageModel

object MessageTextFormatter {
    fun format(
        message: MessageModel,
        revealSpoilers: Boolean = false,
    ): CharSequence {
        val result = SpannableStringBuilder(message.text)
        val offset = if (message.mediaCaption.isNotEmpty() && message.text.endsWith(message.mediaCaption)) message.text.length - message.mediaCaption.length else 0
        if (message.mediaType != com.teleflip.app.model.MediaType.NONE && offset == 0) return result
        message.formatting.orEmpty().forEach { entity ->
            val start = offset.toLong() + entity.offset
            val end = start + entity.length
            if (entity.offset < 0 || entity.length <= 0 || start < 0 || end > result.length) return@forEach
            if (entity.kind == "spoiler" && !revealSpoilers) {
                result.replace(start.toInt(), end.toInt(), "█".repeat(entity.length))
            } else {
                val span =
                    when (entity.kind) {
                        "bold" -> StyleSpan(Typeface.BOLD)
                        "italic" -> StyleSpan(Typeface.ITALIC)
                        "underline", "url" -> UnderlineSpan()
                        "strike" -> StrikethroughSpan()
                        "code" -> TypefaceSpan("monospace")
                        else -> null
                    }
                if (span != null) result.setSpan(span, start.toInt(), end.toInt(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return result
    }
}
