package com.teleflip.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.text.style.ImageSpan
import androidx.test.core.app.ApplicationProvider
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.StickerItem
import com.teleflip.app.telegram.TelegramManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
class StickerEffectDecoratorTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TelegramManager.clearCustomEmojiCache()
    }

    @Test
    fun testFormatStickerHeaderNonPremium() {
        val headerWithEmoji = StickerEffectDecorator.formatStickerHeader("🔥", isPremium = false)
        assertEquals("🎨 [Sticker 🔥]", headerWithEmoji)

        val headerBlank = StickerEffectDecorator.formatStickerHeader("", isPremium = false)
        assertEquals("🎨 [Sticker]", headerBlank)

        val msg =
            MessageModel(
                id = 1L,
                chatId = 100L,
                senderName = "Alice",
                text = "",
                time = "10:00",
                isOutbound = false,
                mediaType = MediaType.STICKER,
                stickerEmoji = "🎉",
                isPremiumSticker = false,
            )
        val msgHeader = StickerEffectDecorator.formatStickerHeader(msg)
        assertEquals("🎨 [Sticker 🎉]", msgHeader)
    }

    @Test
    fun testFormatStickerHeaderPremium() {
        val headerWithEmoji = StickerEffectDecorator.formatStickerHeader("⭐", isPremium = true)
        assertEquals("⭐ [Premium Sticker ⭐]", headerWithEmoji)

        val headerBlank = StickerEffectDecorator.formatStickerHeader("", isPremium = true)
        assertEquals("⭐ [Premium Sticker]", headerBlank)

        val msg =
            MessageModel(
                id = 2L,
                chatId = 100L,
                senderName = "Bob",
                text = "",
                time = "10:01",
                isOutbound = false,
                mediaType = MediaType.STICKER,
                stickerEmoji = "💎",
                isPremiumSticker = true,
            )
        val msgHeader = StickerEffectDecorator.formatStickerHeader(msg)
        assertEquals("⭐ [Premium Sticker 💎]", msgHeader)
    }

    @Test
    fun testFormatStickerContentDescriptionNonPremium() {
        val cdWithEmoji = StickerEffectDecorator.formatStickerContentDescription("🔥", isPremium = false)
        assertEquals("Sticker 🔥", cdWithEmoji)

        val cdBlank = StickerEffectDecorator.formatStickerContentDescription("", isPremium = false)
        assertEquals("Sticker", cdBlank)

        val msg =
            MessageModel(
                id = 3L,
                chatId = 100L,
                senderName = "Alice",
                text = "",
                time = "10:02",
                isOutbound = false,
                mediaType = MediaType.STICKER,
                stickerEmoji = "🚀",
                isPremiumSticker = false,
            )
        val msgCd = StickerEffectDecorator.formatStickerContentDescription(msg)
        assertEquals("Sticker 🚀", msgCd)
    }

    @Test
    fun testFormatStickerContentDescriptionPremium() {
        val cdWithEmoji = StickerEffectDecorator.formatStickerContentDescription("⭐", isPremium = true)
        assertEquals("Premium Sticker ⭐", cdWithEmoji)

        val cdBlank = StickerEffectDecorator.formatStickerContentDescription("", isPremium = true)
        assertEquals("Premium Sticker", cdBlank)

        val msg =
            MessageModel(
                id = 4L,
                chatId = 100L,
                senderName = "Bob",
                text = "",
                time = "10:03",
                isOutbound = false,
                mediaType = MediaType.STICKER,
                stickerEmoji = "⚡",
                isPremiumSticker = true,
            )
        val msgCd = StickerEffectDecorator.formatStickerContentDescription(msg)
        assertEquals("Premium Sticker ⚡", msgCd)
    }

    @Test
    fun testFormatReactionsStandardAndCustomEmojiFallback() {
        // Standard emoji reactions
        val resultStandard = StickerEffectDecorator.formatReactions(context, listOf("👍", "❤️"))
        assertEquals("👍 ❤️", resultStandard.toString())

        // Custom emoji reaction un-cached on disk but with metadata (e.g. custom_emoji_1001 mapped to "⭐")
        val resultCustomWithMetadata =
            StickerEffectDecorator.formatReactions(
                context = context,
                reactions = listOf("custom_emoji_1001"),
                customEmojiStickerResolver = { key ->
                    if (key == "custom_emoji_1001") {
                        StickerItem(fileId = 1001, emoji = "⭐", filePath = null, setName = "Star")
                    } else {
                        null
                    }
                },
            )
        assertEquals("⭐", resultCustomWithMetadata.toString())

        // Custom emoji reaction with no image and no metadata falls back to generic spark "✨"
        val resultCustomUnknown =
            StickerEffectDecorator.formatReactions(
                context = context,
                reactions = listOf("custom_emoji_9999"),
                customEmojiStickerResolver = { null },
            )
        assertEquals("✨", resultCustomUnknown.toString())
    }

    @Test
    fun testFormatReactionsWithValidImageFile() {
        val tempImageFile = File(context.cacheDir, "test_emoji.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        FileOutputStream(tempImageFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        val result =
            StickerEffectDecorator.formatReactions(
                context = context,
                reactions = listOf("custom_emoji_500"),
                customEmojiResolver = { tempImageFile.absolutePath },
            )

        assertNotNull(result)
        val spannable = result as? android.text.Spannable
        assertNotNull("Result should be a Spannable", spannable)

        val spans = spannable?.getSpans(0, spannable.length, ImageSpan::class.java)
        assertNotNull(spans)
        assertTrue("Spannable should contain ImageSpan for valid image file", spans!!.isNotEmpty())

        tempImageFile.delete()
    }
}
