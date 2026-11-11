package com.teleflip.app.ui

import android.app.Application
import android.os.Looper
import android.view.KeyEvent
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.teleflip.app.R
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.ChatType
import com.teleflip.app.model.SavedGifItem
import com.teleflip.app.model.StickerItem
import com.teleflip.app.telegram.TelegramManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w320dp-h533dp-hdpi", application = Application::class)
class GifStickerPickerDialogTest {
    private lateinit var context: Application
    private lateinit var telegramManager: TelegramManager
    private val testChat = ChatModel(1001L, "Alice", "Hello", "10:00", 0, ChatType.DIRECT, "A")

    @Before
    fun setUp() {
        context =
            ApplicationProvider.getApplicationContext<Application>().apply {
                setTheme(R.style.Theme_TeleFlip)
            }
        telegramManager = TelegramManager(context)
    }

    private fun key(code: Int) = KeyEvent(KeyEvent.ACTION_DOWN, code)

    @Test
    fun testDialogInitializesWithGifPack() {
        val dialog =
            GifStickerPickerDialog(
                context = context,
                chat = testChat,
                telegramManager = telegramManager,
                initialIsGif = true,
            )
        dialog.show()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, dialog.currentPackIndex)
        assertTrue(dialog.currentPack?.isGif == true)

        val tvTitle = dialog.findViewById<TextView>(R.id.tv_picker_title)
        assertNotNull(tvTitle)
        assertTrue(tvTitle.text.contains("1/${dialog.packCount}"))

        dialog.dismiss()
    }

    @Test
    fun testDialogInitializesWithStickerPackWhenInitialIsGifFalse() {
        val dialog =
            GifStickerPickerDialog(
                context = context,
                chat = testChat,
                telegramManager = telegramManager,
                initialIsGif = false,
            )
        dialog.show()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(dialog.currentPack?.isGif == false)
        dialog.dismiss()
    }

    @Test
    fun testSoftkeysCyclePacksForwardAndBackward() {
        val dialog =
            GifStickerPickerDialog(
                context = context,
                chat = testChat,
                telegramManager = telegramManager,
                initialIsGif = true,
            )
        dialog.show()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, dialog.currentPackIndex)

        // Top right softkey (Next pack)
        dialog.onKeyDown(KeyEvent.KEYCODE_SOFT_RIGHT, key(KeyEvent.KEYCODE_SOFT_RIGHT))
        assertEquals(1, dialog.currentPackIndex)

        // Top left softkey (Previous pack)
        dialog.onKeyDown(KeyEvent.KEYCODE_SOFT_LEFT, key(KeyEvent.KEYCODE_SOFT_LEFT))
        assertEquals(0, dialog.currentPackIndex)

        // Top left softkey at beginning wraps to last pack
        dialog.onKeyDown(KeyEvent.KEYCODE_SOFT_LEFT, key(KeyEvent.KEYCODE_SOFT_LEFT))
        assertEquals(dialog.packCount - 1, dialog.currentPackIndex)

        // Top right softkey at last pack wraps to first pack
        dialog.onKeyDown(KeyEvent.KEYCODE_SOFT_RIGHT, key(KeyEvent.KEYCODE_SOFT_RIGHT))
        assertEquals(0, dialog.currentPackIndex)

        dialog.dismiss()
    }

    @Test
    fun testBackClosesDialogAndRightSoftkeyDoesNotClose() {
        val dialog =
            GifStickerPickerDialog(
                context = context,
                chat = testChat,
                telegramManager = telegramManager,
                initialIsGif = true,
            )
        dialog.show()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(dialog.isShowing)

        // Press Top Right Softkey -> should advance pack and remain OPEN
        dialog.onKeyDown(KeyEvent.KEYCODE_SOFT_RIGHT, key(KeyEvent.KEYCODE_SOFT_RIGHT))
        assertTrue("Right softkey must advance pack without dismissing dialog", dialog.isShowing)

        // Press Back -> should close dialog
        dialog.onKeyDown(KeyEvent.KEYCODE_BACK, key(KeyEvent.KEYCODE_BACK))
        assertFalse("Back button must dismiss dialog", dialog.isShowing)
    }

    @Test
    fun testCenterKeySendsFocusedItem() {
        telegramManager.saveGif(
            fileId = 1234,
            duration = 4,
            caption = "Test GIF",
            filePath = "/tmp/test.mp4",
        )

        var sentGif: SavedGifItem? = null
        val dialog =
            GifStickerPickerDialog(
                context = context,
                chat = testChat,
                telegramManager = telegramManager,
                initialIsGif = true,
                onSendGif = { sentGif = it },
            )
        dialog.show()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Center / Enter to select & send
        dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, key(KeyEvent.KEYCODE_DPAD_CENTER))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNotNull("Center key should have sent an item", sentGif)
        assertEquals(1234, sentGif?.fileId)
        assertFalse("Dialog should be dismissed after sending", dialog.isShowing)
    }

    @Test
    fun testCenterKeySendsFocusedSticker() {
        telegramManager.saveSticker(
            fileId = 5678,
            emoji = "⭐",
            setName = "Stars",
            filePath = "/tmp/star.webp",
        )

        var sentSticker: StickerItem? = null
        val dialog =
            GifStickerPickerDialog(
                context = context,
                chat = testChat,
                telegramManager = telegramManager,
                initialIsGif = false,
                onSendSticker = { sentSticker = it },
            )
        dialog.show()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Center / Enter to select & send
        dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, key(KeyEvent.KEYCODE_DPAD_CENTER))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNotNull("Center key should have sent a sticker", sentSticker)
        assertEquals(5678, sentSticker?.fileId)
        assertFalse("Dialog should be dismissed after sending", dialog.isShowing)
    }

    @Test
    fun testDpadNavigationInGrid() {
        val dialog =
            GifStickerPickerDialog(
                context = context,
                chat = testChat,
                telegramManager = telegramManager,
                initialIsGif = false,
            )
        dialog.show()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val rv = dialog.findViewById<RecyclerView>(R.id.rv_picker_grid)
        assertNotNull(rv)

        // D-pad down event should be accepted
        val consumed = dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, key(KeyEvent.KEYCODE_DPAD_DOWN))
        // Standard D-pad returns false from onKeyDown so RecyclerView can handle it
        assertFalse(consumed)

        dialog.dismiss()
    }

    @Test
    fun testSaveStickerAndSaveGifInTelegramManager() {
        var stickerSaved = false
        telegramManager.saveSticker(
            fileId = 7711,
            emoji = "🎉",
            setName = "PartySet",
            filePath = "/path/to/sticker.webp",
        ) { success ->
            stickerSaved = success
        }
        assertTrue(stickerSaved)

        val localStickers = telegramManager.getLocalSavedStickers()
        val foundSticker = localStickers.find { it.fileId == 7711 }
        assertNotNull("Saved sticker should be in local list", foundSticker)
        assertEquals("🎉", foundSticker?.emoji)

        var gifSaved = false
        telegramManager.saveGif(
            fileId = 8822,
            duration = 3,
            caption = "Cool Loop",
            filePath = "/path/to/anim.mp4",
        ) { success ->
            gifSaved = success
        }
        assertTrue(gifSaved)

        val localGifs = telegramManager.getLocalSavedGifs()
        val foundGif = localGifs.find { it.fileId == 8822 }
        assertNotNull("Saved GIF should be in local list", foundGif)
        assertEquals("Cool Loop", foundGif?.caption)
    }

    @Test
    fun testAttachMenuContainsOpenGifsOptionAndOpensPicker() {
        val controller = org.robolectric.Robolectric.buildActivity(com.teleflip.app.MainActivity::class.java).setup()
        val activity = controller.get()
        val showAttachMenuMethod = com.teleflip.app.MainActivity::class.java.getDeclaredMethod("showAttachMenu", ChatModel::class.java)
        showAttachMenuMethod.isAccessible = true
        showAttachMenuMethod.invoke(activity, testChat)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val latestDialog = org.robolectric.shadows.ShadowAlertDialog.getLatestDialog()
        assertNotNull(latestDialog)
        assertTrue(latestDialog is OptionsMenuDialog)

        val optionsField = OptionsMenuDialog::class.java.getDeclaredField("options")
        optionsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val options = optionsField.get(latestDialog) as List<MenuOption>

        val openGifsOption = options.find { it.title.contains("Open GIFs", ignoreCase = true) }
        assertNotNull("Attach options menu must contain an option to open gifs", openGifsOption)

        openGifsOption?.action?.invoke()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val pickerDialog = org.robolectric.shadows.ShadowAlertDialog.getLatestDialog()
        assertNotNull(pickerDialog)
        assertTrue("Picker dialog must be opened", pickerDialog is GifStickerPickerDialog)
    }

    @Test
    fun testGeneratePickerScreenshot() {
        telegramManager.saveGif(101, 3, "Party 🎉", null)
        telegramManager.saveSticker(202, "🔥", "Fire", null)
        val dialog =
            GifStickerPickerDialog(
                context = context,
                chat = testChat,
                telegramManager = telegramManager,
                initialIsGif = true,
            )
        dialog.show()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val rootView = dialog.findViewById<android.view.View>(R.id.layout_picker_dialog_root)
        assertNotNull(rootView)
        rootView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(320, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(533, android.view.View.MeasureSpec.EXACTLY),
        )
        rootView.layout(0, 0, 320, 533)

        val bitmap = android.graphics.Bitmap.createBitmap(320, 533, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        rootView.draw(canvas)

        val file = java.io.File("/tmp/picker_screenshot.png")
        java.io.FileOutputStream(file).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        assertTrue(file.exists() && file.length() > 0)
        dialog.dismiss()
    }
}
