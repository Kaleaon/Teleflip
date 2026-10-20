package com.teleflip.app.ui

import android.app.Application
import android.view.KeyEvent
import android.widget.EditText
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
class PollDialogTest {
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.Theme_TeleFlip)
    }

    @Test
    fun testOnKeyDown_dpadRightConsumedAsInputKeyWhenEditTextFocused() {
        val dialog = PollDialog(context, onCreatePoll = { _, _, _ -> })
        dialog.show()

        val etQuestion = dialog.findViewById<EditText>(R.id.et_poll_question)
        etQuestion.requestFocus()

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, event)

        assertTrue("KEYCODE_DPAD_RIGHT should be consumed as input key in PollDialog when EditText is focused", handled)
    }

    @Test
    fun testDpadRightCommitsCharacterInPollDialog() {
        val dialog = PollDialog(context, onCreatePoll = { _, _, _ -> })
        dialog.show()

        val etQuestion = dialog.findViewById<EditText>(R.id.et_poll_question)
        etQuestion.requestFocus()

        val event2 = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        dialog.onKeyDown(KeyEvent.KEYCODE_2, event2)
        assertEquals("A", etQuestion.text.toString())

        val eventRight = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, eventRight)
        assertTrue("KEYCODE_DPAD_RIGHT should be handled as input key", handled)

        // Key '2' again should now append 'a' rather than cycling 'A' to 'b'
        dialog.onKeyDown(KeyEvent.KEYCODE_2, event2)
        assertEquals("Aa", etQuestion.text.toString())
    }

    @Test
    fun testDismissCommitsCharacterInPollDialog() {
        val dialog = PollDialog(context, onCreatePoll = { _, _, _ -> })
        dialog.show()

        val etQuestion = dialog.findViewById<EditText>(R.id.et_poll_question)
        etQuestion.requestFocus()

        val event2 = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        dialog.onKeyDown(KeyEvent.KEYCODE_2, event2)
        assertEquals("A", etQuestion.text.toString())

        val backEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        dialog.onKeyDown(KeyEvent.KEYCODE_BACK, backEvent)

        assertEquals("A", etQuestion.text.toString())
    }

    @Test
    fun testOnKeyDown_inputKeysConsumedWhenEditTextFocused() {
        val dialog = PollDialog(context, onCreatePoll = { _, _, _ -> })
        dialog.show()

        val etQuestion = dialog.findViewById<EditText>(R.id.et_poll_question)
        etQuestion.requestFocus()

        val event2 = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        val handled2 = dialog.onKeyDown(KeyEvent.KEYCODE_2, event2)
        assertTrue("KEYCODE_2 should be consumed when EditText is focused", handled2)
    }

    @Test
    fun testFocusChangeCommitsCharacter() {
        val dialog = PollDialog(context, onCreatePoll = { _, _, _ -> })
        dialog.show()

        val etQuestion = dialog.findViewById<EditText>(R.id.et_poll_question)
        val etOpt1 = dialog.findViewById<EditText>(R.id.et_poll_opt1)

        etQuestion.requestFocus()
        val event2 = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        dialog.onKeyDown(KeyEvent.KEYCODE_2, event2)

        // Text initially contains uncommitted character cycling
        assertEquals("A", etQuestion.text.toString())

        // Move focus to etOpt1
        etOpt1.requestFocus()

        // Active character should be committed upon focus loss
        assertEquals("A", etQuestion.text.toString())
    }
}
