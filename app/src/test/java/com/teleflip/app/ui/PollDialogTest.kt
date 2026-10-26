package com.teleflip.app.ui

import android.app.Application
import android.view.KeyEvent
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
    fun testSoftkeyBarBindingAndLabels() {
        val dialog = PollDialog(context, onCreatePoll = { _, _, _ -> })
        dialog.show()

        val tvLeft = dialog.findViewById<TextView>(R.id.tv_softkey_left)
        val tvCenter = dialog.findViewById<TextView>(R.id.tv_softkey_center)
        val tvRight = dialog.findViewById<TextView>(R.id.tv_softkey_right)

        assertNotNull("LSK view should be bound", tvLeft)
        assertNotNull("Center softkey view should be bound", tvCenter)
        assertNotNull("RSK view should be bound", tvRight)

        assertEquals("Clear", tvLeft.text.toString())
        assertEquals("Submit", tvCenter.text.toString())
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
        val btnAnon = dialog.findViewById<Button>(R.id.btn_poll_anonymous_toggle)

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

        // Test Center key on btnAnon toggles anonymous state
        btnAnon.requestFocus()
        val centerEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        val handledCenterBtn = dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, centerEvent)
        assertTrue("Center key on Button should be handled", handledCenterBtn)
        assertEquals("☐ Anonymous Voting: OFF", btnAnon.text.toString())

        // Test Center key when not on button submits the poll
        etQuestion.requestFocus()
        val handledSubmit = dialog.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, centerEvent)
        assertTrue("Center key should trigger submit", handledSubmit)
        assertTrue("Poll should be created", pollCreated)
        assertEquals("Sample Question", capturedQuestion)
        assertEquals(2, capturedOpts.size)
        assertFalse("Anonymous voting was toggled OFF", capturedAnon)
        assertFalse("Dialog should be dismissed", dialog.isShowing)

        // Test RSK dismisses dialog
        val dialog2 = PollDialog(context, onCreatePoll = { _, _, _ -> })
        dialog2.show()
        val softRightEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_RIGHT)
        val handledRight = dialog2.onKeyDown(KeyEvent.KEYCODE_SOFT_RIGHT, softRightEvent)
        assertTrue("RSK should be handled", handledRight)
        assertFalse("Dialog should be dismissed by RSK", dialog2.isShowing)
    }

    @Test
    fun testVerticalDpadFocusMovementSequence() {
        val dialog = PollDialog(context, onCreatePoll = { _, _, _ -> })
        dialog.show()

        val etQuestion = dialog.findViewById<EditText>(R.id.et_poll_question)
        val etOpt1 = dialog.findViewById<EditText>(R.id.et_poll_opt1)
        val etOpt2 = dialog.findViewById<EditText>(R.id.et_poll_opt2)
        val etOpt3 = dialog.findViewById<EditText>(R.id.et_poll_opt3)
        val btnAnon = dialog.findViewById<Button>(R.id.btn_poll_anonymous_toggle)
        val btnSubmit = dialog.findViewById<Button>(R.id.btn_poll_submit)
        val btnCancel = dialog.findViewById<Button>(R.id.btn_poll_cancel)

        assertEquals(R.id.et_poll_opt1, etQuestion.nextFocusDownId)
        assertEquals(R.id.btn_poll_cancel, etQuestion.nextFocusUpId)

        assertEquals(R.id.et_poll_opt2, etOpt1.nextFocusDownId)
        assertEquals(R.id.et_poll_question, etOpt1.nextFocusUpId)

        assertEquals(R.id.et_poll_opt3, etOpt2.nextFocusDownId)
        assertEquals(R.id.et_poll_opt1, etOpt2.nextFocusUpId)

        assertEquals(R.id.btn_poll_anonymous_toggle, etOpt3.nextFocusDownId)
        assertEquals(R.id.et_poll_opt2, etOpt3.nextFocusUpId)

        assertEquals(R.id.btn_poll_submit, btnAnon.nextFocusDownId)
        assertEquals(R.id.et_poll_opt3, btnAnon.nextFocusUpId)

        assertEquals(R.id.btn_poll_cancel, btnSubmit.nextFocusDownId)
        assertEquals(R.id.btn_poll_anonymous_toggle, btnSubmit.nextFocusUpId)

        assertEquals(R.id.et_poll_question, btnCancel.nextFocusDownId)
        assertEquals(R.id.btn_poll_submit, btnCancel.nextFocusUpId)
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
