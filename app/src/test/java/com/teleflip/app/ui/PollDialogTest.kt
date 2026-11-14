package com.teleflip.app.ui

import android.app.Application
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
    fun testNext_emptyQuestion_setsFieldErrorFocusesAndPreservesStep1() {
        var callbackCalled = false
        val dialog = PollDialog(context, onCreatePoll = { _, _, _ -> callbackCalled = true })
        dialog.show()

        val etQuestion = dialog.findViewById<EditText>(R.id.et_poll_question)
        val btnNext = dialog.findViewById<Button>(R.id.btn_poll_next)
        val layoutStep1 = dialog.findViewById<LinearLayout>(R.id.layout_step1)

        btnNext.performClick()

        assertEquals("Question cannot be empty", etQuestion.error.toString())
        assertTrue("Question field should gain focus on validation failure", etQuestion.isFocused)
        assertEquals(View.VISIBLE, layoutStep1.visibility)
        assertFalse("onCreatePoll callback should not be invoked when question is empty", callbackCalled)
    }

    @Test
    fun testNext_fewerThanTwoOptions_setsFieldErrorFocusesAndPreservesStep1() {
        var callbackCalled = false
        val dialog = PollDialog(context, onCreatePoll = { _, _, _ -> callbackCalled = true })
        dialog.show()

        val etQuestion = dialog.findViewById<EditText>(R.id.et_poll_question)
        val etOpt1 = dialog.findViewById<EditText>(R.id.et_poll_opt1)
        val etOpt2 = dialog.findViewById<EditText>(R.id.et_poll_opt2)
        val btnNext = dialog.findViewById<Button>(R.id.btn_poll_next)
        val layoutStep1 = dialog.findViewById<LinearLayout>(R.id.layout_step1)

        etQuestion.setText("Valid Question?")

        btnNext.performClick()

        assertEquals("At least 2 options required", etOpt1.error.toString())
        assertTrue("Option 1 field should gain focus when option count is invalid", etOpt1.isFocused)
        assertEquals(View.VISIBLE, layoutStep1.visibility)
        assertFalse("onCreatePoll should not be invoked", callbackCalled)

        etOpt1.setText("Option 1")
        btnNext.performClick()

        assertEquals("At least 2 options required", etOpt2.error.toString())
        assertTrue("Option 2 field should gain focus when only 1 option provided", etOpt2.isFocused)
        assertEquals(View.VISIBLE, layoutStep1.visibility)
        assertFalse("onCreatePoll should not be invoked", callbackCalled)
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

    @Test
    fun testStepNavigationAndStatePersistence() {
        val dialog = PollDialog(context, onCreatePoll = { _, _, _ -> })
        dialog.show()

        val layoutStep1 = dialog.findViewById<LinearLayout>(R.id.layout_step1)
        val layoutStep2 = dialog.findViewById<LinearLayout>(R.id.layout_step2)
        val etQuestion = dialog.findViewById<EditText>(R.id.et_poll_question)
        val etOpt1 = dialog.findViewById<EditText>(R.id.et_poll_opt1)
        val etOpt2 = dialog.findViewById<EditText>(R.id.et_poll_opt2)
        val btnNext = dialog.findViewById<Button>(R.id.btn_poll_next)

        // Step 1 initially visible
        assertEquals(View.VISIBLE, layoutStep1.visibility)
        assertEquals(View.GONE, layoutStep2.visibility)

        // Enter input
        etQuestion.setText("Capital of France?")
        etOpt1.setText("London")
        etOpt2.setText("Paris")

        // Click Next
        btnNext.performClick()

        // Now in Step 2
        assertEquals(View.GONE, layoutStep1.visibility)
        assertEquals(View.VISIBLE, layoutStep2.visibility)

        // Click Back
        val btnBack = dialog.findViewById<Button>(R.id.btn_poll_back)
        btnBack.performClick()

        // Returned to Step 1 without losing form state
        assertEquals(View.VISIBLE, layoutStep1.visibility)
        assertEquals(View.GONE, layoutStep2.visibility)
        assertEquals("Capital of France?", etQuestion.text.toString())
        assertEquals("London", etOpt1.text.toString())
        assertEquals("Paris", etOpt2.text.toString())
    }

    @Test
    fun testQuizModeToggleAndOptionSelection() {
        var createdQuestion = ""
        var createdOpts = emptyList<String>()
        var createdQuiz = false
        var createdCorrectIdx = -1
        var createdExp = ""

        val dialog =
            PollDialog(
                context,
                onCreatePoll = { question, options, _, isQuiz, correctIndex, explanation, _ ->
                    createdQuestion = question
                    createdOpts = options
                    createdQuiz = isQuiz
                    createdCorrectIdx = correctIndex
                    createdExp = explanation
                },
            )
        dialog.show()

        dialog.findViewById<EditText>(R.id.et_poll_question).setText("What is 5 x 5?")
        dialog.findViewById<EditText>(R.id.et_poll_opt1).setText("10")
        dialog.findViewById<EditText>(R.id.et_poll_opt2).setText("25")
        dialog.findViewById<EditText>(R.id.et_poll_opt3).setText("50")

        dialog.findViewById<Button>(R.id.btn_poll_next).performClick()

        val btnModeToggle = dialog.findViewById<Button>(R.id.btn_poll_mode_toggle)
        val layoutQuizSettings = dialog.findViewById<LinearLayout>(R.id.layout_quiz_settings)
        val btnCorrectChoice = dialog.findViewById<Button>(R.id.btn_poll_correct_choice)

        // Toggle Quiz Mode ON
        btnModeToggle.performClick()
        assertEquals(View.VISIBLE, layoutQuizSettings.visibility)

        // Cycle correct answer to index 1 ("25")
        btnCorrectChoice.performClick() // correctOptionIndex becomes 1

        dialog.findViewById<EditText>(R.id.et_poll_explanation).setText("5 multiplied by 5 is 25")

        // Submit poll
        dialog.findViewById<Button>(R.id.btn_poll_submit).performClick()

        assertEquals("What is 5 x 5?", createdQuestion)
        assertEquals(listOf("10", "25", "50"), createdOpts)
        assertTrue(createdQuiz)
        assertEquals(1, createdCorrectIdx)
        assertEquals("5 multiplied by 5 is 25", createdExp)
    }

    @Test
    fun testRegularPollMultipleAnswersSubmission() {
        var createdQuiz = true
        var createdMulti = false

        val dialog =
            PollDialog(
                context,
                onCreatePoll = { _, _, _, isQuiz, _, _, allowsMultipleAnswers ->
                    createdQuiz = isQuiz
                    createdMulti = allowsMultipleAnswers
                },
            )
        dialog.show()

        dialog.findViewById<EditText>(R.id.et_poll_question).setText("Which fruits do you like?")
        dialog.findViewById<EditText>(R.id.et_poll_opt1).setText("Apple")
        dialog.findViewById<EditText>(R.id.et_poll_opt2).setText("Banana")

        dialog.findViewById<Button>(R.id.btn_poll_next).performClick()

        // Toggle Multiple Answers ON
        dialog.findViewById<Button>(R.id.btn_poll_multiple_answers_toggle).performClick()

        // Submit
        dialog.findViewById<Button>(R.id.btn_poll_submit).performClick()

        assertFalse(createdQuiz)
        assertTrue(createdMulti)
    }

    @Test
    fun testStep2BackPressedNavigatesToStep1() {
        val dialog = PollDialog(context, onCreatePoll = { _, _, _ -> })
        dialog.show()

        dialog.findViewById<EditText>(R.id.et_poll_question).setText("Test Question")
        dialog.findViewById<EditText>(R.id.et_poll_opt1).setText("Opt 1")
        dialog.findViewById<EditText>(R.id.et_poll_opt2).setText("Opt 2")

        dialog.findViewById<Button>(R.id.btn_poll_next).performClick()

        val layoutStep1 = dialog.findViewById<LinearLayout>(R.id.layout_step1)
        val layoutStep2 = dialog.findViewById<LinearLayout>(R.id.layout_step2)
        assertEquals(View.VISIBLE, layoutStep2.visibility)

        // Send BACK key event while in Step 2
        val backEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        val handled = dialog.onKeyDown(KeyEvent.KEYCODE_BACK, backEvent)

        assertTrue("Back key should be handled on Step 2", handled)
        assertEquals(View.VISIBLE, layoutStep1.visibility)
        assertEquals(View.GONE, layoutStep2.visibility)
        assertTrue("Dialog should remain showing when navigating back from Step 2", dialog.isShowing)
    }
}
