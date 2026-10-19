package com.teleflip.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.teleflip.app.R
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageStatus
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
class MessageAdapterCustomEmojiTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TelegramManager.clearCustomEmojiCache()
    }

    @Test
    fun testCustomEmojiStickerRegistrationAndFetch() {
        val customItem =
            StickerItem(
                fileId = 2001,
                emoji = "🚀",
                filePath = "/tmp/test_rocket.png",
                setName = "Space Set",
            )
        TelegramManager.registerCustomEmojiSticker("custom_emoji_2001", customItem)

        val retrieved = TelegramManager.getCustomEmojiSticker("custom_emoji_2001")
        assertNotNull("Sticker should be retrieved from cache", retrieved)
        assertEquals(2001, retrieved?.fileId)
        assertEquals("🚀", retrieved?.emoji)

        val telegramManager = TelegramManager(context)
        var fetchedList: List<StickerItem>? = null
        telegramManager.fetchCustomEmojiStickers { items ->
            fetchedList = items
        }
        assertNotNull("Fetched custom emoji stickers should not be null", fetchedList)
        assertTrue("Fetched list should contain fallback/default custom emoji stickers", fetchedList!!.isNotEmpty())
    }

    @Test
    fun testCustomEmojiReactionTogglingInTelegramManager() {
        val telegramManager = TelegramManager(context)
        val msg =
            MessageModel(
                id = 101L,
                chatId = 1L,
                senderName = "Alice",
                text = "Hello custom emoji",
                time = "12:00",
                isOutbound = false,
                status = MessageStatus.READ,
                reactions = emptyList(),
            )
        telegramManager.setMessagesForTesting(mapOf(1L to listOf(msg)))

        // Toggle custom emoji reaction
        telegramManager.toggleMessageReaction(1L, 101L, "custom_emoji_1001")
        var currentMsgs = telegramManager.messages.value[1L] ?: emptyList()
        assertTrue("Message reactions should contain custom_emoji_1001", currentMsgs.first().reactions.contains("custom_emoji_1001"))

        // Untoggle custom emoji reaction
        telegramManager.toggleMessageReaction(1L, 101L, "custom_emoji_1001")
        currentMsgs = telegramManager.messages.value[1L] ?: emptyList()
        assertTrue("Message reactions should no longer contain custom_emoji_1001", currentMsgs.first().reactions.isEmpty())
    }

    @Test
    fun testMessageAdapterCustomEmojiViewBinding() {
        val parent = FrameLayout(context)
        val adapter =
            MessageAdapter(
                customEmojiResolver = { key ->
                    if (key == "custom_emoji_1001") "/tmp/valid_emoji_1001.png" else null
                },
            )
        val holder = adapter.onCreateViewHolder(parent, 0)

        // Test standard emoji reaction
        val msgStandard =
            MessageModel(
                id = 1L,
                chatId = 100L,
                senderName = "Bob",
                text = "Nice post",
                time = "10:00",
                isOutbound = false,
                reactions = listOf("👍", "❤️"),
            )
        holder.bind(msgStandard)

        val tvReactions = holder.itemView.findViewById<TextView>(R.id.tv_reactions)
        assertNotNull("tvReactions view should exist", tvReactions)
        assertEquals(TextView.VISIBLE, tvReactions.visibility)
        assertTrue("tvReactions text should include standard emojis", tvReactions.text.toString().contains("👍"))

        // Test custom emoji reaction fallback placeholder (file does not exist)
        val msgCustomFallback =
            MessageModel(
                id = 2L,
                chatId = 100L,
                senderName = "Bob",
                text = "Custom emoji test",
                time = "10:01",
                isOutbound = false,
                reactions = listOf("custom_emoji_9999"),
            )
        holder.bind(msgCustomFallback)
        assertEquals(TextView.VISIBLE, tvReactions.visibility)
        assertTrue("tvReactions should render fallback placeholder string", tvReactions.text.toString().contains("✨"))

        // Test custom emoji reaction with valid image file
        val tempImageFile = File(context.cacheDir, "valid_emoji_1001.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        FileOutputStream(tempImageFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        TelegramManager.registerCustomEmojiSticker(
            "custom_emoji_1001",
            StickerItem(fileId = 1001, emoji = "⭐", filePath = tempImageFile.absolutePath, setName = "Star Set"),
        )

        val adapterWithResolver =
            MessageAdapter(
                customEmojiResolver = { key -> TelegramManager.getCustomEmojiFilePath(key) },
            )
        val holder2 = adapterWithResolver.onCreateViewHolder(parent, 0)

        val msgCustomValid =
            MessageModel(
                id = 3L,
                chatId = 100L,
                senderName = "Bob",
                text = "Valid custom emoji image",
                time = "10:02",
                isOutbound = false,
                reactions = listOf("👍", "custom_emoji_1001"),
            )
        holder2.bind(msgCustomValid)

        val tvReactions2 = holder2.itemView.findViewById<TextView>(R.id.tv_reactions)
        assertEquals(TextView.VISIBLE, tvReactions2.visibility)
        assertTrue("tvReactions text should not be empty", tvReactions2.text.isNotEmpty())

        tempImageFile.delete()
    }
}
