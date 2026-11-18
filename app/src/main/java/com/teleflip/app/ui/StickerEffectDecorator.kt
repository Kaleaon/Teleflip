package com.teleflip.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.StickerItem
import com.teleflip.app.telegram.TelegramManager
import java.io.File

object StickerEffectDecorator {
    fun formatStickerHeader(
        stickerEmoji: String,
        mediaCaption: String = "",
        isPremium: Boolean = false,
    ): String {
        val emoji = stickerEmoji.ifBlank { mediaCaption }
        return if (isPremium) {
            if (emoji.isNotBlank()) "⭐ [Premium Sticker $emoji]" else "⭐ [Premium Sticker]"
        } else {
            if (emoji.isNotBlank()) "🎨 [Sticker $emoji]" else "🎨 [Sticker]"
        }
    }

    fun formatStickerHeader(message: MessageModel): String =
        formatStickerHeader(
            stickerEmoji = message.stickerEmoji,
            mediaCaption = message.mediaCaption,
            isPremium = message.isPremiumSticker,
        )

    fun formatStickerContentDescription(
        stickerEmoji: String,
        mediaCaption: String = "",
        isPremium: Boolean = false,
    ): String {
        val emoji = stickerEmoji.ifBlank { mediaCaption }
        return if (isPremium) {
            if (emoji.isNotBlank()) "Premium Sticker $emoji" else "Premium Sticker"
        } else {
            if (emoji.isNotBlank()) "Sticker $emoji" else "Sticker"
        }
    }

    fun formatStickerContentDescription(message: MessageModel): String =
        formatStickerContentDescription(
            stickerEmoji = message.stickerEmoji,
            mediaCaption = message.mediaCaption,
            isPremium = message.isPremiumSticker,
        )

    fun formatReactions(
        context: Context,
        reactions: List<String>,
        customEmojiResolver: ((String) -> String?)? = null,
        customEmojiStickerResolver: ((String) -> StickerItem?)? = { TelegramManager.getCustomEmojiSticker(it) },
    ): CharSequence {
        if (reactions.isEmpty()) return ""

        val ssb = SpannableStringBuilder()
        val density = context.resources.displayMetrics.density
        val targetSize = (16 * density).toInt().coerceAtLeast(1)

        reactions.forEachIndexed { idx, reaction ->
            if (idx > 0) ssb.append(" ")

            if (reaction.startsWith("custom_emoji_")) {
                val filePath =
                    customEmojiResolver?.invoke(reaction)
                        ?: TelegramManager.getCustomEmojiFilePath(reaction)
                var drawableAdded = false
                if (!filePath.isNullOrBlank()) {
                    try {
                        val file = File(filePath)
                        if (file.exists()) {
                            val bitmap = BitmapFactory.decodeFile(filePath)
                            if (bitmap != null) {
                                val drawable = BitmapDrawable(context.resources, bitmap)
                                drawable.setBounds(0, 0, targetSize, targetSize)
                                val startPos = ssb.length
                                ssb.append(" ")
                                ssb.setSpan(
                                    ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM),
                                    startPos,
                                    startPos + 1,
                                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                                )
                                drawableAdded = true
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
                if (!drawableAdded) {
                    val sticker =
                        customEmojiStickerResolver?.invoke(reaction)
                            ?: TelegramManager.getCustomEmojiSticker(reaction)
                    val fallbackSymbol = sticker?.emoji?.ifBlank { "✨" } ?: "✨"
                    ssb.append(fallbackSymbol)
                }
            } else {
                ssb.append(reaction)
            }
        }
        return ssb
    }

    fun formatReactions(
        context: Context,
        message: MessageModel,
        customEmojiResolver: ((String) -> String?)? = null,
        customEmojiStickerResolver: ((String) -> StickerItem?)? = { TelegramManager.getCustomEmojiSticker(it) },
    ): CharSequence =
        formatReactions(
            context = context,
            reactions = message.reactions,
            customEmojiResolver = customEmojiResolver,
            customEmojiStickerResolver = customEmojiStickerResolver,
        )
}
