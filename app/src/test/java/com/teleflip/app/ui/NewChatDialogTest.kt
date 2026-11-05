package com.teleflip.app.ui

import android.app.Application
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teleflip.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewChatDialogTest {
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.Theme_TeleFlip)
    }

    @Test
    fun testDpadRightDelegatesToSuperWhenInputHasFocus() {
        var startChatCalled = false
        val dialog =
            NewChatDialog(
                context = context,
                onStartChatByQuery = { startChatCalled = true },
                onOpenSavedMessages = {},
                onCreateGroup = {},
                onCreateChannel = {},
            )
        dialog.show()

        val etInput = dialog.findViewById<com.teleflip.app.ui.LabeledInputView>(R.id.liv_new_chat_input).editText
        assertTrue("Input should have focus initially", etInput.hasFocus())

        val dpadRightEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, dpadRightEvent)

        assertFalse("KEYCODE_DPAD_RIGHT should not be consumed as an input key when input has focus", handled)
        assertFalse("Start chat should not be triggered by DPAD_RIGHT", startChatCalled)
    }

    @Test
    fun testInputKeysHandledWhenInputHasFocus() {
        val dialog =
            NewChatDialog(
                context = context,
                onStartChatByQuery = {},
                onOpenSavedMessages = {},
                onCreateGroup = {},
                onCreateChannel = {},
            )
        dialog.show()

        val etInput = dialog.findViewById<com.teleflip.app.ui.LabeledInputView>(R.id.liv_new_chat_input).editText
        assertTrue("Input should have focus initially", etInput.hasFocus())

        val inputKeys =
            listOf(
                KeyEvent.KEYCODE_0,
                KeyEvent.KEYCODE_1,
                KeyEvent.KEYCODE_2,
                KeyEvent.KEYCODE_3,
                KeyEvent.KEYCODE_4,
                KeyEvent.KEYCODE_5,
                KeyEvent.KEYCODE_6,
                KeyEvent.KEYCODE_7,
                KeyEvent.KEYCODE_8,
                KeyEvent.KEYCODE_9,
                KeyEvent.KEYCODE_STAR,
                KeyEvent.KEYCODE_POUND,
                KeyEvent.KEYCODE_DEL,
            )

        for (keyCode in inputKeys) {
            val event = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val handled = dialog.onKeyDown(keyCode, event)
            assertTrue("Keycode $keyCode should be classified and handled as input key when input has focus", handled)
        }
    }

    @Test
    fun testShortcutKeysTriggeredWhenInputLosesFocus() {
        var directQuery: String? = null
        var openSavedCalled = false
        var groupTitle: String? = null
        var channelTitle: String? = null
        var secretQuery: String? = null

        val dialog =
            NewChatDialog(
                context = context,
                onStartChatByQuery = { directQuery = it },
                onOpenSavedMessages = { openSavedCalled = true },
                onCreateGroup = { groupTitle = it },
                onCreateChannel = { channelTitle = it },
                onStartSecretChat = { secretQuery = it },
            )
        dialog.show()

        val etInput = dialog.findViewById<com.teleflip.app.ui.LabeledInputView>(R.id.liv_new_chat_input).editText
        etInput.clearFocus()
        assertFalse("Input focus cleared", etInput.hasFocus())

        val key1Event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1)
        val handled1 = dialog.onKeyDown(KeyEvent.KEYCODE_1, key1Event)
        assertTrue("Shortcut 1 should be handled", handled1)
        assertEquals("", directQuery)
        assertFalse("Dialog should be dismissed after action", dialog.isShowing)

        dialog.show()
        etInput.clearFocus()

        val key2Event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        val handled2 = dialog.onKeyDown(KeyEvent.KEYCODE_2, key2Event)
        assertTrue("Shortcut 2 should be handled", handled2)
        assertTrue(openSavedCalled)

        dialog.show()
        etInput.clearFocus()

        val key3Event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_3)
        val handled3 = dialog.onKeyDown(KeyEvent.KEYCODE_3, key3Event)
        assertTrue("Shortcut 3 should be handled", handled3)
        assertEquals("", groupTitle)

        dialog.show()
        etInput.clearFocus()

        val key4Event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_4)
        val handled4 = dialog.onKeyDown(KeyEvent.KEYCODE_4, key4Event)
        assertTrue("Shortcut 4 should be handled", handled4)
        assertEquals("", channelTitle)

        dialog.show()
        etInput.clearFocus()

        val key5Event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_5)
        val handled5 = dialog.onKeyDown(KeyEvent.KEYCODE_5, key5Event)
        assertTrue("Shortcut 5 should be handled", handled5)
        assertEquals("", secretQuery)
    }

    @Test
    fun testSoftLeftClearsInputText() {
        val dialog =
            NewChatDialog(
                context = context,
                onStartChatByQuery = {},
                onOpenSavedMessages = {},
                onCreateGroup = {},
                onCreateChannel = {},
            )
        dialog.show()

        val etInput = dialog.findViewById<com.teleflip.app.ui.LabeledInputView>(R.id.liv_new_chat_input).editText
        etInput.setText("Test Query")

        val softLeftEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_LEFT)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_SOFT_LEFT, softLeftEvent)

        assertTrue(handled)
        assertEquals("", etInput.text.toString())
    }
}
