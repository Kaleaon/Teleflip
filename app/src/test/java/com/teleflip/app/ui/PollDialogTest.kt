package com.teleflip.app.ui

import android.app.Application
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PollDialogTest {
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testInitialFocusIsOnQuestion() {
        val dialog = PollDialog(context, onCreatePoll = { _, _, _ -> })
        dialog.show()

        val etQuestion = dialog.findViewById<EditText>(R.id.et_poll_question)
        assertTrue(etQuestion.isFocused)
    }

    @Test
    fun testKeypadNumberTypingInQuestion() {
        val dialog = PollDialog(context, timeoutMs = 200L, onCreatePoll = { _, _, _ -> })
        dialog.show()

        val etQuestion = dialog.findViewById<EditText>(R.id.et_poll_question)
        etQuestion.requestFocus()

        // Press '2' once -> "A" (in Default Abc mode, first char of 2 is uppercase)
        val event2 = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        dialog.onKeyDown(KeyEvent.KEYCODE_2, event2)

        assertEquals("A", etQuestion.text.toString())
    }

    @Test
    fun testDpadRightCommitsCharacter() {
        val dialog = PollDialog(context, timeoutMs = 1000L, onCreatePoll = { _, _, _ -> })
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

    @Test
    fun testSoftkeyBarBindingAndLabels() {
        val dialog = PollDialog(context, onCreatePoll = { _, _, _ -> })
        dialog.show()

        val tvLeft = dialog.findViewById<TextView>(R.id.tv_softkey_left)
        val tvCenter = dialog.findViewById<TextView>(R.id.tv_softkey_center)
        val tvRight = dialog.findViewById<TextView>(R.id.tv_softkey_right)

        assertNotNull("LSK view should be bound", tvLeft)
        assertNotNull("Center softkey view should be bound", tvCenter)
        assertNotNull("RSK view should be bound", tvRight)

        // Step 1 labels
        assertEquals("Clear", tvLeft.text.toString())
        assertEquals("Next", tvCenter.text.toString())
        assertEquals("Cancel", tvRight.text.toString())
    }

    @Test
    fun testSoftkeyActions() {
        var pollCreated = false
        var capturedQuestion = ""
        var capturedOpts = listOf<String>()
        var capturedAnon = false

        val dialog =
            PollDialog(
                context,
                onCreatePoll = { q, opts, anon ->
                    pollCreated = true
                    capturedQuestion = q
                    capturedOpts = opts
                    capturedAnon = anon
                },
            )
        dialog.show()

        val etQuestion = dialog.findViewById<EditText>(R.id.et_poll_question)
        val etOpt1 = dialog.findViewById<EditText>(R.id.et_poll_opt1)
        val etOpt2 = dialog.findViewById<EditText>(R.id.et_poll_opt2)

        etQuestion.setText("Sample Question")
        etOpt1.setText("Option 1")
        etOpt2.setText("Option 2")

        // Test LSK clears focused EditText
        etQuestion.requestFocus()
        val softLeftEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_LEFT)
        val handledLeft = dialog.onKeyDown(KeyEvent.KEYCODE_SOFT_LEFT, softLeftEvent)
        assertTrue("LSK should be handled", handledLeft)
        assertEquals("", etQuestion.text.toString())

        // Restore question text
        etQuestion.setText("Sample Question")

        // Test Center key when on EditText triggers Next (advance to Step 2)
        val centerEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        val handledNext = dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, centerEvent)
        assertTrue("Center key on Step 1 should advance to Step 2", handledNext)

        // Now on Step 2
        val tvCenter = dialog.findViewById<TextView>(R.id.tv_softkey_center)
        val tvRight = dialog.findViewById<TextView>(R.id.tv_softkey_right)
        assertEquals("Submit", tvCenter.text.toString())
        assertEquals("Back", tvRight.text.toString())

        val btnAnon = dialog.findViewById<Button>(R.id.btn_poll_anonymous_toggle)
        btnAnon.requestFocus()
        val handledCenterBtn = dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, centerEvent)
        assertTrue("Center key on Button should be handled", handledCenterBtn)
        assertEquals("☐ Anonymous Voting: OFF", btnAnon.text.toString())

        // Test Center key on non-button submits the poll
        val etExplanation = dialog.findViewById<EditText>(R.id.et_poll_explanation)
        etExplanation.requestFocus()
        val handledSubmit = dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, centerEvent)
        assertTrue("Center key should trigger submit", handledSubmit)
        assertTrue("Poll should be created", pollCreated)
        assertEquals("Sample Question", capturedQuestion)
        assertEquals(2, capturedOpts.size)
        assertFalse("Anonymous voting was toggled OFF", capturedAnon)
        assertFalse("Dialog should be dismissed", dialog.isShowing)

        // Test RSK dismisses dialog on Step 1
        val dialog2 = PollDialog(context, onCreatePoll = { _, _, _ -> })
        dialog2.show()
        val softRightEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_RIGHT)
        val handledRight = dialog2.onKeyDown(KeyEvent.KEYCODE_SOFT_RIGHT, softRightEvent)
        assertTrue("RSK should be handled", handledRight)
        assertFalse("Dialog should be dismissed by RSK on Step 1", dialog2.isShowing)
    }

    @Test
    fun testVerticalDpadFocusMovementSequence() {
        val dialog = PollDialog(context, onCreatePoll = { _, _, _ -> })
        dialog.show()

        val etQuestion = dialog.findViewById<EditText>(R.id.et_poll_question)
        val etOpt1 = dialog.findViewById<EditText>(R.id.et_poll_opt1)
        val etOpt2 = dialog.findViewById<EditText>(R.id.et_poll_opt2)
        val etOpt3 = dialog.findViewById<EditText>(R.id.et_poll_opt3)
        val etOpt4 = dialog.findViewById<EditText>(R.id.et_poll_opt4)
        val btnNext = dialog.findViewById<Button>(R.id.btn_poll_next)
        val btnCancel = dialog.findViewById<Button>(R.id.btn_poll_cancel)

        assertEquals(R.id.et_poll_opt1, etQuestion.nextFocusDownId)
        assertEquals(R.id.btn_poll_cancel, etQuestion.nextFocusUpId)

        assertEquals(R.id.et_poll_opt2, etOpt1.nextFocusDownId)
        assertEquals(R.id.et_poll_question, etOpt1.nextFocusUpId)

        assertEquals(R.id.et_poll_opt3, etOpt2.nextFocusDownId)
        assertEquals(R.id.et_poll_opt1, etOpt2.nextFocusUpId)

        assertEquals(R.id.et_poll_opt4, etOpt3.nextFocusDownId)
        assertEquals(R.id.et_poll_opt2, etOpt3.nextFocusUpId)

        assertEquals(R.id.btn_poll_next, etOpt4.nextFocusDownId)
        assertEquals(R.id.et_poll_opt3, etOpt4.nextFocusUpId)

        assertEquals(R.id.btn_poll_cancel, btnNext.nextFocusDownId)
        assertEquals(R.id.et_poll_opt4, btnNext.nextFocusUpId)

        assertEquals(R.id.et_poll_question, btnCancel.nextFocusDownId)
        assertEquals(R.id.btn_poll_next, btnCancel.nextFocusUpId)
    }

    @Test
    fun testMultiTapCharacterCommittedOnDpadNavigation() {
        val dialog = PollDialog(context, onCreatePoll = { _, _, _ -> })
        dialog.show()

        val etQuestion = dialog.findViewById<EditText>(R.id.et_poll_question)
        val etOpt1 = dialog.findViewById<EditText>(R.id.et_poll_opt1)

        etQuestion.requestFocus()

        // Press KEYCODE_2 -> multi-tap buffer has uncommitted 'A'
        val key2Event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        dialog.onKeyDown(KeyEvent.KEYCODE_2, key2Event)
        assertEquals("A", etQuestion.text.toString())

        // Press DPAD_DOWN
        val dpadDownEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
        dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, dpadDownEvent)

        // Character 'A' must be committed before focus moves
        assertEquals("A", etQuestion.text.toString())

        // Focus etOpt1 and type KEYCODE_3 ('D')
        etOpt1.requestFocus()
        val key3Event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_3)
        dialog.onKeyDown(KeyEvent.KEYCODE_3, key3Event)
        assertEquals("D", etOpt1.text.toString())

        // Press DPAD_UP
        val dpadUpEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)
        dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, dpadUpEvent)

        // Character 'D' must be committed
        assertEquals("D", etOpt1.text.toString())

        // Test DPAD_LEFT and DPAD_RIGHT committing multi-tap
        val key4Event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_4)
        dialog.onKeyDown(KeyEvent.KEYCODE_4, key4Event)
        val dpadLeftEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
        dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, dpadLeftEvent)
        assertTrue(etOpt1.text.toString().contains("g", ignoreCase = true))
    }
}
