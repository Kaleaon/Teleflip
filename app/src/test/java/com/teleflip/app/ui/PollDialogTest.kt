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
    fun testPollSubmission_missingQuestion_showsInlineErrorAndFocusesQuestionInput() {
        val dialog = PollDialog(context, onCreatePoll = { _, _, _ -> })
        dialog.show()

        val btnSubmit = dialog.findViewById<Button>(R.id.btn_poll_submit)
        val tvError = dialog.findViewById<TextView>(R.id.tv_poll_error)
        val etQuestion = dialog.findViewById<EditText>(R.id.et_poll_question)

        btnSubmit.performClick()

        assertEquals(View.VISIBLE, tvError.visibility)
        assertEquals("Please enter a poll question", tvError.text.toString())
        assertTrue("et_poll_question should have focus when question is missing", etQuestion.hasFocus())
    }

    @Test
    fun testPollSubmission_missingOptions_showsInlineErrorAndFocusesOptionInput() {
        val dialog = PollDialog(context, onCreatePoll = { _, _, _ -> })
        dialog.show()

        val etQuestion = dialog.findViewById<EditText>(R.id.et_poll_question)
        val btnSubmit = dialog.findViewById<Button>(R.id.btn_poll_submit)
        val tvError = dialog.findViewById<TextView>(R.id.tv_poll_error)
        val etOpt1 = dialog.findViewById<EditText>(R.id.et_poll_opt1)

        etQuestion.setText("Sample Question")
        btnSubmit.performClick()

        assertEquals(View.VISIBLE, tvError.visibility)
        assertEquals("Please enter at least 2 poll options", tvError.text.toString())
        assertTrue("et_poll_opt1 should have focus when options are missing", etOpt1.hasFocus())
    }

    @Test
    fun testPollSubmission_validInput_clearsErrorAndSubmits() {
        var createdQuestion = ""
        var createdOptions = emptyList<String>()
        val dialog = PollDialog(context, onCreatePoll = { q, opts, _ ->
            createdQuestion = q
            createdOptions = opts
        })
        dialog.show()

        val etQuestion = dialog.findViewById<EditText>(R.id.et_poll_question)
        val etOpt1 = dialog.findViewById<EditText>(R.id.et_poll_opt1)
        val etOpt2 = dialog.findViewById<EditText>(R.id.et_poll_opt2)
        val btnSubmit = dialog.findViewById<Button>(R.id.btn_poll_submit)

        etQuestion.setText("Favorite Color?")
        etOpt1.setText("Red")
        etOpt2.setText("Blue")

        btnSubmit.performClick()

        assertEquals("Favorite Color?", createdQuestion)
        assertEquals(listOf("Red", "Blue"), createdOptions)
    }

    @Test
    fun testOnKeyDown_dpadRightNotConsumedAsInputKey() {
        val dialog = PollDialog(context, onCreatePoll = { _, _, _ -> })
        dialog.show()

        val etQuestion = dialog.findViewById<EditText>(R.id.et_poll_question)
        etQuestion.requestFocus()

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, event)

        assertFalse("KEYCODE_DPAD_RIGHT should not be consumed as input key in PollDialog", handled)
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
