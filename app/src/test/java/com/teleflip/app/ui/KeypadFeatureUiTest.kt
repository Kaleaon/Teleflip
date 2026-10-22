package com.teleflip.app.ui

import android.app.Application
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.teleflip.app.R
import com.teleflip.app.model.MessageFormatting
import com.teleflip.app.model.MessageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 27], qualifiers = "w320dp-h533dp-hdpi", application = Application::class)
class KeypadFeatureUiTest {
    private fun context() = ApplicationProvider.getApplicationContext<Application>().apply { setTheme(R.style.Theme_TeleFlip) }

    private fun key(code: Int) = KeyEvent(KeyEvent.ACTION_DOWN, code)

    @Test fun menuPagesExposeEveryActionUsingLocalNumberKeys() {
        var selected = -1
        val dialog = OptionsMenuDialog(context(), "Options", (0..20).map { index -> MenuOption(index, "Action $index") { selected = index } })
        dialog.show()
        dialog.dispatchKeyEvent(key(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(1, dialog.currentPage)
        dialog.dispatchKeyEvent(key(KeyEvent.KEYCODE_2))
        assertEquals(10, selected)
        assertFalse(dialog.isShowing)
    }

    @Test fun lastPageDoesNotInvokeAbsentNumberAndWrapsBack() {
        var selected = -1
        val dialog = OptionsMenuDialog(context(), "Options", (0..10).map { index -> MenuOption(index, "Action $index") { selected = index } }, initialPage = 1)
        dialog.show()
        dialog.dispatchKeyEvent(key(KeyEvent.KEYCODE_9))
        assertEquals(-1, selected)
        assertTrue(dialog.isShowing)
        dialog.dispatchKeyEvent(key(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(0, dialog.currentPage)
        dialog.dismiss()
    }

    @Test fun heldNumberDoesNotActivateMenuAction() {
        var selected = false
        val dialog = OptionsMenuDialog(context(), "Options", listOf(MenuOption(1, "Delete") { selected = true }))
        dialog.show()
        dialog.onKeyDown(KeyEvent.KEYCODE_1, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1, 1))
        assertFalse(selected)
        assertTrue(dialog.isShowing)
        dialog.dismiss()
    }

    @Test fun numericEntryUsesDigitsAndCenterSubmits() {
        var submitted: String? = null
        val dialog = KeypadTextDialog(context(), "Minutes", numeric = true) { submitted = it }
        dialog.show()
        dialog.dispatchKeyEvent(key(KeyEvent.KEYCODE_2))
        dialog.dispatchKeyEvent(key(KeyEvent.KEYCODE_5))
        dialog.dispatchKeyEvent(key(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals("25", submitted)
        assertFalse(dialog.isShowing)
    }

    @Test fun dispatchedNumberKeysUseMultitapAndDoNotReachAndroidTextInput() {
        var submitted: String? = null
        val dialog = KeypadTextDialog(context(), "Message") { submitted = it }
        dialog.show()
        repeat(2) {
            dialog.dispatchKeyEvent(key(KeyEvent.KEYCODE_2))
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_2))
        }
        dialog.dispatchKeyEvent(key(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals("b", submitted)
    }

    @Test fun backCancelsWithoutSubmitting() {
        var submitted = false
        val dialog = KeypadTextDialog(context(), "Caption", initial = "Keep") { submitted = true }
        dialog.show()
        dialog.dispatchKeyEvent(key(KeyEvent.KEYCODE_BACK))
        assertFalse(submitted)
        assertFalse(dialog.isShowing)
    }

    @Test fun spoilersStayMaskedWithUtf16OffsetsAndMalformedEntitiesAreIgnored() {
        val message = MessageModel(1, 2, "Sender", "👋 secret", "", false, formatting = listOf(MessageFormatting(3, 6, "spoiler"), MessageFormatting(Int.MAX_VALUE, 8, "bold")))
        assertEquals("👋 ██████", MessageTextFormatter.format(message).toString())
        assertEquals("👋 secret", MessageTextFormatter.format(message, revealSpoilers = true).toString())
    }
}
