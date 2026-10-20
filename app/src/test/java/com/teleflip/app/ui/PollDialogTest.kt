package com.teleflip.app.ui

import android.app.Application
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teleflip.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun testSubmit_emptyQuestion_setsFieldErrorFocusesAndPreservesDialog() {
        var callbackCalled = false
        val dialog = PollDialog(context, onCreatePoll = { _, _, _ -> callbackCalled = true })
        dialog.show()

        val etQuestion = dialog.findViewById<EditText>(R.id.et_poll_question)
        val btnSubmit = dialog.findViewById<Button>(R.id.btn_poll_submit)

        btnSubmit.performClick()

        assertEquals("Question cannot be empty", etQuestion.error.toString())
        assertTrue("Question field should gain focus on validation failure", etQuestion.isFocused)
        assertTrue("Dialog should remain showing on validation failure", dialog.isShowing)
        assertFalse("onCreatePoll callback should not be invoked when question is empty", callbackCalled)
    }

    @Test
    fun testSubmit_fewerThanTwoOptions_setsFieldErrorFocusesAndPreservesDialog() {
        var callbackCalled = false
        val dialog = PollDialog(context, onCreatePoll = { _, _, _ -> callbackCalled = true })
        dialog.show()

        val etQuestion = dialog.findViewById<EditText>(R.id.et_poll_question)
        val etOpt1 = dialog.findViewById<EditText>(R.id.et_poll_opt1)
        val etOpt2 = dialog.findViewById<EditText>(R.id.et_poll_opt2)
        val btnSubmit = dialog.findViewById<Button>(R.id.btn_poll_submit)

        etQuestion.setText("Valid Question?")

        // Attempt 1: 0 options provided -> etOpt1 should get error and focus
        btnSubmit.performClick()

        assertEquals("At least 2 options required", etOpt1.error.toString())
        assertTrue("Option 1 field should gain focus when option count is invalid", etOpt1.isFocused)
        assertTrue("Dialog should remain showing", dialog.isShowing)
        assertFalse("onCreatePoll should not be invoked", callbackCalled)

        // Attempt 2: Only 1 option provided -> etOpt2 should get error and focus
        etOpt1.setText("Option 1")
        btnSubmit.performClick()

        assertEquals("At least 2 options required", etOpt2.error.toString())
        assertTrue("Option 2 field should gain focus when only 1 option provided", etOpt2.isFocused)
        assertTrue("Dialog should remain showing", dialog.isShowing)
        assertFalse("onCreatePoll should not be invoked", callbackCalled)
    }

    @Test
    fun testSubmit_whitespaceOnlyOptions_treatedAsEmpty() {
        var callbackCalled = false
        val dialog = PollDialog(context, onCreatePoll = { _, _, _ -> callbackCalled = true })
        dialog.show()

        val etQuestion = dialog.findViewById<EditText>(R.id.et_poll_question)
        val etOpt1 = dialog.findViewById<EditText>(R.id.et_poll_opt1)
        val etOpt2 = dialog.findViewById<EditText>(R.id.et_poll_opt2)
        val btnSubmit = dialog.findViewById<Button>(R.id.btn_poll_submit)

        etQuestion.setText("Question?")
        etOpt1.setText("   ")
        etOpt2.setText("   ")

        btnSubmit.performClick()

        assertEquals("At least 2 options required", etOpt1.error.toString())
        assertTrue(dialog.isShowing)
        assertFalse(callbackCalled)
    }

    @Test
    fun testTypingClearsFieldError() {
        val dialog = PollDialog(context, onCreatePoll = { _, _, _ -> })
        dialog.show()

        val etQuestion = dialog.findViewById<EditText>(R.id.et_poll_question)
        val btnSubmit = dialog.findViewById<Button>(R.id.btn_poll_submit)

        btnSubmit.performClick()
        assertNotNull(etQuestion.error)

        etQuestion.setText("Updated Question")
        assertNull("Error should be cleared when text is modified", etQuestion.error)
    }

    @Test
    fun testSubmit_validSubmission_invokesCallbackAndDismissesDialog() {
        var submittedQuestion = ""
        var submittedOptions = emptyList<String>()
        var submittedAnon = false
        var callbackCalled = false

        val dialog =
            PollDialog(
                context,
                onCreatePoll = { q, opts, anon ->
                    submittedQuestion = q
                    submittedOptions = opts
                    submittedAnon = anon
                    callbackCalled = true
                },
            )
        dialog.show()

        val etQuestion = dialog.findViewById<EditText>(R.id.et_poll_question)
        val etOpt1 = dialog.findViewById<EditText>(R.id.et_poll_opt1)
        val etOpt2 = dialog.findViewById<EditText>(R.id.et_poll_opt2)
        val btnSubmit = dialog.findViewById<Button>(R.id.btn_poll_submit)

        etQuestion.setText("What is your favorite color?")
        etOpt1.setText("Red")
        etOpt2.setText("Blue")

        btnSubmit.performClick()

        assertTrue(callbackCalled)
        assertEquals("What is your favorite color?", submittedQuestion)
        assertEquals(listOf("Red", "Blue"), submittedOptions)
        assertTrue(submittedAnon)
        assertFalse(dialog.isShowing)
    }
}
