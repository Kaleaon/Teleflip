package com.teleflip.app.ui

import android.app.Application
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teleflip.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun testInitialFocusDefaultsToStartDirectChat() {
        val dialog =
            NewChatDialog(
                context = context,
                onStartChatByQuery = {},
                onOpenSavedMessages = {},
                onCreateGroup = {},
                onCreateChannel = {},
            )
        dialog.show()

        val btnDirect = dialog.findViewById<Button>(R.id.btn_start_direct_chat)
        val etInput = dialog.findViewById<EditText>(R.id.et_new_chat_input)

        assertTrue("Direct chat button should have focus initially", btnDirect.hasFocus())
        assertFalse("Input field should not have focus initially", etInput.hasFocus())
    }

    @Test
    fun testSoftkeyBarPresentAndLabelsConfigured() {
        val dialog =
            NewChatDialog(
                context = context,
                onStartChatByQuery = {},
                onOpenSavedMessages = {},
                onCreateGroup = {},
                onCreateChannel = {},
            )
        dialog.show()

        val softkeyBar = dialog.findViewById<View>(R.id.softkey_bar)
        val tvLeft = dialog.findViewById<TextView>(R.id.tv_softkey_left)
        val tvCenter = dialog.findViewById<TextView>(R.id.tv_softkey_center)
        val tvRight = dialog.findViewById<TextView>(R.id.tv_softkey_right)

        assertNotNull("Softkey bar should be included", softkeyBar)
        assertNotNull("Left softkey view should be present", tvLeft)
        assertNotNull("Center softkey view should be present", tvCenter)
        assertNotNull("Right softkey view should be present", tvRight)

        assertEquals("", tvLeft.text.toString())
        assertEquals("Select", tvCenter.text.toString())
        assertEquals("Back", tvRight.text.toString())
    }

    @Test
    fun testSoftkeyLabelsUpdateOnFocusChange() {
        val dialog =
            NewChatDialog(
                context = context,
                onStartChatByQuery = {},
                onOpenSavedMessages = {},
                onCreateGroup = {},
                onCreateChannel = {},
            )
        dialog.show()

        val etInput = dialog.findViewById<EditText>(R.id.et_new_chat_input)
        val tvLeft = dialog.findViewById<TextView>(R.id.tv_softkey_left)
        val tvCenter = dialog.findViewById<TextView>(R.id.tv_softkey_center)
        val tvRight = dialog.findViewById<TextView>(R.id.tv_softkey_right)

        // Initially btn_start_direct_chat is focused and etInput is empty
        assertEquals("", tvLeft.text.toString())
        assertEquals("Select", tvCenter.text.toString())
        assertEquals("Back", tvRight.text.toString())

        // Add text to input field while button is focused
        etInput.setText("UserQuery")
        assertEquals("Clear Input", tvLeft.text.toString())

        // Focus input field
        etInput.requestFocus()
        assertTrue("Input field should have focus", etInput.hasFocus())
        assertEquals("Clear", tvLeft.text.toString())
        assertEquals("Start", tvCenter.text.toString())
        assertEquals("Back", tvRight.text.toString())
    }

    @Test
    fun testCenterSoftkeyTriggersFocusedActionButton() {
        var openSavedCalled = false
        val dialog =
            NewChatDialog(
                context = context,
                onStartChatByQuery = {},
                onOpenSavedMessages = { openSavedCalled = true },
                onCreateGroup = {},
                onCreateChannel = {},
            )
        dialog.show()

        val btnSaved = dialog.findViewById<Button>(R.id.btn_open_saved_messages)
        btnSaved.requestFocus()
        assertTrue(btnSaved.hasFocus())

        val tvCenter = dialog.findViewById<TextView>(R.id.tv_softkey_center)
        tvCenter.performClick()

        assertTrue("Open Saved Messages should be called when Center softkey is clicked", openSavedCalled)
        assertFalse("Dialog should be dismissed after action", dialog.isShowing)
    }

    @Test
    fun testNumericShortcutsTriggeredOnLaunch() {
        var directQuery: String? = null
        var openSavedCalled = false
        var groupTitle: String? = null
        var channelTitle: String? = null
        var secretQuery: String? = null

        // Key 1 -> Start chat by query
        val dialog1 =
            NewChatDialog(
                context = context,
                onStartChatByQuery = { directQuery = it },
                onOpenSavedMessages = {},
                onCreateGroup = {},
                onCreateChannel = {},
                onStartSecretChat = {},
            )
        dialog1.show()
        val key1Event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1)
        val handled1 = dialog1.onKeyDown(KeyEvent.KEYCODE_1, key1Event)
        assertTrue("Shortcut 1 should be handled on launch", handled1)
        assertEquals("", directQuery)
        assertFalse("Dialog should be dismissed after action 1", dialog1.isShowing)

        // Key 2 -> Open saved messages
        val dialog2 =
            NewChatDialog(
                context = context,
                onStartChatByQuery = {},
                onOpenSavedMessages = { openSavedCalled = true },
                onCreateGroup = {},
                onCreateChannel = {},
                onStartSecretChat = {},
            )
        dialog2.show()
        val key2Event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        val handled2 = dialog2.onKeyDown(KeyEvent.KEYCODE_2, key2Event)
        assertTrue("Shortcut 2 should be handled on launch", handled2)
        assertTrue("Saved messages should be opened", openSavedCalled)
        assertFalse("Dialog should be dismissed after action 2", dialog2.isShowing)

        // Key 3 -> Create group
        val dialog3 =
            NewChatDialog(
                context = context,
                onStartChatByQuery = {},
                onOpenSavedMessages = {},
                onCreateGroup = { groupTitle = it },
                onCreateChannel = {},
                onStartSecretChat = {},
            )
        dialog3.show()
        val key3Event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_3)
        val handled3 = dialog3.onKeyDown(KeyEvent.KEYCODE_3, key3Event)
        assertTrue("Shortcut 3 should be handled on launch", handled3)
        assertEquals("", groupTitle)
        assertFalse("Dialog should be dismissed after action 3", dialog3.isShowing)

        // Key 4 -> Create channel
        val dialog4 =
            NewChatDialog(
                context = context,
                onStartChatByQuery = {},
                onOpenSavedMessages = {},
                onCreateGroup = {},
                onCreateChannel = { channelTitle = it },
                onStartSecretChat = {},
            )
        dialog4.show()
        val key4Event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_4)
        val handled4 = dialog4.onKeyDown(KeyEvent.KEYCODE_4, key4Event)
        assertTrue("Shortcut 4 should be handled on launch", handled4)
        assertEquals("", channelTitle)
        assertFalse("Dialog should be dismissed after action 4", dialog4.isShowing)

        // Key 5 -> Start secret chat
        val dialog5 =
            NewChatDialog(
                context = context,
                onStartChatByQuery = {},
                onOpenSavedMessages = {},
                onCreateGroup = {},
                onCreateChannel = {},
                onStartSecretChat = { secretQuery = it },
            )
        dialog5.show()
        val key5Event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_5)
        val handled5 = dialog5.onKeyDown(KeyEvent.KEYCODE_5, key5Event)
        assertTrue("Shortcut 5 should be handled on launch", handled5)
        assertEquals("", secretQuery)
        assertFalse("Dialog should be dismissed after action 5", dialog5.isShowing)
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

        val etInput = dialog.findViewById<EditText>(R.id.et_new_chat_input)
        etInput.requestFocus()
        assertTrue("Input should have focus when requested", etInput.hasFocus())

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

        val etInput = dialog.findViewById<EditText>(R.id.et_new_chat_input)
        etInput.requestFocus()
        assertTrue("Input should have focus when requested", etInput.hasFocus())

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

        val etInput = dialog.findViewById<EditText>(R.id.et_new_chat_input)
        etInput.requestFocus()
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

        val etInput = dialog.findViewById<EditText>(R.id.et_new_chat_input)
        etInput.setText("Test Query")

        val softLeftEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_LEFT)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_SOFT_LEFT, softLeftEvent)

        assertTrue(handled)
        assertEquals("", etInput.text.toString())
    }

    @Test
    fun testSoftLeftClickClearsInputText() {
        val dialog =
            NewChatDialog(
                context = context,
                onStartChatByQuery = {},
                onOpenSavedMessages = {},
                onCreateGroup = {},
                onCreateChannel = {},
            )
        dialog.show()

        val etInput = dialog.findViewById<EditText>(R.id.et_new_chat_input)
        etInput.setText("Sample Query")

        val tvLeft = dialog.findViewById<TextView>(R.id.tv_softkey_left)
        tvLeft.performClick()

        assertEquals("", etInput.text.toString())
    }

    @Test
    fun testSoftCenterSelectTriggersFocusedButton() {
        var openSavedCalled = false
        val dialog =
            NewChatDialog(
                context = context,
                onStartChatByQuery = {},
                onOpenSavedMessages = { openSavedCalled = true },
                onCreateGroup = {},
                onCreateChannel = {},
            )
        dialog.show()

        val btnSaved = dialog.findViewById<Button>(R.id.btn_open_saved_messages)
        btnSaved.requestFocus()
        assertTrue(btnSaved.hasFocus())

        val centerEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, centerEvent)

        assertTrue("Center key should be handled", handled)
        assertTrue("Saved messages should be opened", openSavedCalled)
        assertFalse("Dialog should be dismissed", dialog.isShowing)
    }

    @Test
    fun testSoftCenterClickTriggersFocusedAction() {
        var directQuery: String? = null
        val dialog =
            NewChatDialog(
                context = context,
                onStartChatByQuery = { directQuery = it },
                onOpenSavedMessages = {},
                onCreateGroup = {},
                onCreateChannel = {},
            )
        dialog.show()

        val etInput = dialog.findViewById<EditText>(R.id.et_new_chat_input)
        etInput.setText("alice")

        val tvCenter = dialog.findViewById<TextView>(R.id.tv_softkey_center)
        tvCenter.performClick()

        assertEquals("alice", directQuery)
        assertFalse("Dialog should be dismissed", dialog.isShowing)
    }

    @Test
    fun testSoftRightAndBackDismissDialog() {
        val dialog =
            NewChatDialog(
                context = context,
                onStartChatByQuery = {},
                onOpenSavedMessages = {},
                onCreateGroup = {},
                onCreateChannel = {},
            )
        dialog.show()
        assertTrue(dialog.isShowing)

        val tvRight = dialog.findViewById<TextView>(R.id.tv_softkey_right)
        tvRight.performClick()
        assertFalse("Dialog should be dismissed by right softkey click", dialog.isShowing)

        dialog.show()
        assertTrue(dialog.isShowing)

        val rskEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_RIGHT)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_SOFT_RIGHT, rskEvent)
        assertTrue(handled)
        assertFalse("Dialog should be dismissed by right softkey keycode", dialog.isShowing)
    }

    @Test
    fun testMovingFocusToInputRestoresMultiTapTextEntry() {
        var openSavedCalled = false
        val dialog =
            NewChatDialog(
                context = context,
                onStartChatByQuery = {},
                onOpenSavedMessages = { openSavedCalled = true },
                onCreateGroup = {},
                onCreateChannel = {},
            )
        dialog.show()

        val btnDirect = dialog.findViewById<Button>(R.id.btn_start_direct_chat)
        val etInput = dialog.findViewById<EditText>(R.id.et_new_chat_input)

        assertTrue(btnDirect.hasFocus())
        assertFalse(etInput.hasFocus())

        // Move focus to input
        etInput.requestFocus()
        assertTrue(etInput.hasFocus())

        // Press key 2 (which is shortcut for Saved Messages when button is focused)
        val key2Event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_2, key2Event)

        assertTrue("Key 2 should be consumed by keypad input engine", handled)
        assertFalse("Shortcut for saved messages must not be triggered when input has focus", openSavedCalled)
        assertTrue("Input should contain text from multi-tap engine", etInput.text.isNotEmpty())
    }
}
