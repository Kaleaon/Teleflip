package com.teleflip.app.ui

import android.app.Application
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teleflip.app.R
import com.teleflip.app.storage.ChatExportOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatExportDialogTest {
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.Theme_TeleFlip)
    }

    @Test
    fun testInitialFocusAndSoftkeyLabels() {
        val dialog = ChatExportDialog(context, chatId = 1L, chatTitle = "Test Chat", onStartExport = {})
        dialog.show()

        val btnText = dialog.findViewById<Button>(R.id.btn_toggle_text)
        assertNotNull("btnText should exist", btnText)
        assertTrue("Initial focus should be on btnText", btnText.hasFocus())

        val tvSoftkeyLeft = dialog.findViewById<TextView>(R.id.tv_softkey_left)
        val tvSoftkeyCenter = dialog.findViewById<TextView>(R.id.tv_softkey_center)
        val tvSoftkeyRight = dialog.findViewById<TextView>(R.id.tv_softkey_right)

        assertNotNull("Softkey left view should exist", tvSoftkeyLeft)
        assertNotNull("Softkey center view should exist", tvSoftkeyCenter)
        assertNotNull("Softkey right view should exist", tvSoftkeyRight)

        assertEquals("Start", tvSoftkeyLeft.text.toString())
        assertEquals("Toggle", tvSoftkeyCenter.text.toString())
        assertEquals("Cancel", tvSoftkeyRight.text.toString())
    }

    @Test
    fun testOptionButtonsFocusable() {
        val dialog = ChatExportDialog(context, chatId = 1L, chatTitle = "Test Chat", onStartExport = {})
        dialog.show()

        val buttonIds =
            listOf(
                R.id.btn_toggle_text,
                R.id.btn_toggle_photos,
                R.id.btn_toggle_gifs,
                R.id.btn_toggle_videos,
                R.id.btn_toggle_voice,
                R.id.btn_toggle_documents,
                R.id.btn_toggle_storage,
                R.id.btn_start_export,
                R.id.btn_cancel_export,
            )

        for (id in buttonIds) {
            val button = dialog.findViewById<Button>(id)
            assertNotNull("Button $id should exist", button)
            assertTrue("Button $id should be focusable", button.isFocusable)
            assertTrue("Button $id should be focusable in touch mode", button.isFocusableInTouchMode)
        }
    }

    @Test
    fun testSoftLeftKey_triggersExportCallback() {
        var exportedOptions: ChatExportOptions? = null
        val dialog =
            ChatExportDialog(
                context = context,
                chatId = 1L,
                chatTitle = "Test Chat",
                onStartExport = { exportedOptions = it },
            )
        dialog.show()

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_LEFT)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_SOFT_LEFT, event)

        assertTrue("Soft left key should be handled", handled)
        assertFalse("Dialog should be dismissed after starting export", dialog.isShowing)
        assertNotNull("Export options callback should be invoked", exportedOptions)
    }

    @Test
    fun testSoftLeftViewClick_triggersExportCallback() {
        var exportedOptions: ChatExportOptions? = null
        val dialog =
            ChatExportDialog(
                context = context,
                chatId = 1L,
                chatTitle = "Test Chat",
                onStartExport = { exportedOptions = it },
            )
        dialog.show()

        val tvSoftkeyLeft = dialog.findViewById<TextView>(R.id.tv_softkey_left)
        tvSoftkeyLeft.performClick()

        assertFalse("Dialog should be dismissed after clicking soft left view", dialog.isShowing)
        assertNotNull("Export options callback should be invoked", exportedOptions)
    }

    @Test
    fun testSoftRightKey_dismissesDialog() {
        val dialog = ChatExportDialog(context, chatId = 1L, chatTitle = "Test Chat", onStartExport = {})
        dialog.show()

        val tvSoftkeyRight = dialog.findViewById<TextView>(R.id.tv_softkey_right)
        tvSoftkeyRight.performClick()

        assertFalse("Dialog should be dismissed after clicking soft right view", dialog.isShowing)
    }

    @Test
    fun testCenterKey_togglesFocusedOption() {
        val dialog = ChatExportDialog(context, chatId = 1L, chatTitle = "Test Chat", onStartExport = {})
        dialog.show()

        val btnText = dialog.findViewById<Button>(R.id.btn_toggle_text)
        assertTrue("btnText initially checked", btnText.text.contains("☑"))

        val centerEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, centerEvent)

        assertTrue("Center key should be handled", handled)
        assertTrue("btnText should be unchecked after toggle", btnText.text.contains("☐"))
    }

    @Test
    fun testNumericShortcuts_toggleOptions() {
        val dialog = ChatExportDialog(context, chatId = 1L, chatTitle = "Test Chat", onStartExport = {})
        dialog.show()

        val btnText = dialog.findViewById<Button>(R.id.btn_toggle_text)
        val btnPhotos = dialog.findViewById<Button>(R.id.btn_toggle_photos)
        val btnGifs = dialog.findViewById<Button>(R.id.btn_toggle_gifs)
        val btnVideos = dialog.findViewById<Button>(R.id.btn_toggle_videos)
        val btnVoice = dialog.findViewById<Button>(R.id.btn_toggle_voice)
        val btnDocuments = dialog.findViewById<Button>(R.id.btn_toggle_documents)

        // Initial state is all checked
        assertTrue(btnText.text.contains("☑"))
        assertTrue(btnPhotos.text.contains("☑"))
        assertTrue(btnGifs.text.contains("☑"))
        assertTrue(btnVideos.text.contains("☑"))
        assertTrue(btnVoice.text.contains("☑"))
        assertTrue(btnDocuments.text.contains("☑"))

        val shortcuts =
            listOf(
                KeyEvent.KEYCODE_1 to btnText,
                KeyEvent.KEYCODE_2 to btnPhotos,
                KeyEvent.KEYCODE_3 to btnGifs,
                KeyEvent.KEYCODE_4 to btnVideos,
                KeyEvent.KEYCODE_5 to btnVoice,
                KeyEvent.KEYCODE_6 to btnDocuments,
            )

        for ((keyCode, button) in shortcuts) {
            val event = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val handled = dialog.onKeyDown(keyCode, event)
            assertTrue("Key $keyCode should be handled", handled)
            assertTrue("Button should be unchecked after pressing $keyCode", button.text.contains("☐"))
        }
    }
}
