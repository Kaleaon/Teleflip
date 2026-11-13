package com.teleflip.app.ui

import com.teleflip.app.model.SavedGifItem
import com.teleflip.app.model.StickerItem
import com.teleflip.app.model.StickerSetModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaPickerTest {
    @Test
    fun testMediaPickerNewInstance() {
        val dialog = MediaPickerBottomSheetDialog.newInstance(1001L)
        assertNotNull(dialog)
        assertEquals(1001L, dialog.arguments?.getLong("arg_chat_id"))
    }

    @Test
    fun testStickerSetModelData() {
        val stickers =
            listOf(
                StickerItem(fileId = 1, emoji = "👍", filePath = "/path/to/sticker1.png", setName = "Emojis"),
                StickerItem(fileId = 2, emoji = "❤️", filePath = null, setName = "Emojis"),
            )
        val pack = StickerSetModel(id = 10L, title = "Emojis", stickers = stickers)

        assertEquals(10L, pack.id)
        assertEquals("Emojis", pack.title)
        assertEquals(2, pack.stickers.size)
        assertEquals("/path/to/sticker1.png", pack.stickers[0].filePath)
    }

    @Test
    fun testSavedGifItemWithQueryId() {
        val item =
            SavedGifItem(
                fileId = 100,
                filePath = "/path/to/gif.mp4",
                durationSeconds = 3,
                caption = "Funny cat",
                queryId = 55555L,
                resultId = "cat_01",
            )

        assertEquals(100, item.fileId)
        assertEquals("/path/to/gif.mp4", item.filePath)
        assertEquals(3, item.durationSeconds)
        assertEquals("Funny cat", item.caption)
        assertEquals(55555L, item.queryId)
        assertEquals("cat_01", item.resultId)
    }
}
