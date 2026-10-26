package com.teleflip.app.ui

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.teleflip.app.R
import com.teleflip.app.input.KeypadInputEngine

class PollDialog(
    context: Context,
    private val timeoutMs: Long = 500L,
    private val onCreatePoll: (question: String, options: List<String>, isAnonymous: Boolean) -> Unit,
) : BaseDialog(context) {
    private val engines = mutableMapOf<Int, KeypadInputEngine>()
    private var isAnonymous = true
    private lateinit var softkeyManager: SoftkeyManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_create_poll)
        setupDismissKeys()

        val tvMode = findViewById<TextView>(R.id.tv_poll_input_mode)
        val etQuestion = findViewById<EditText>(R.id.et_poll_question)
        val etOpt1 = findViewById<EditText>(R.id.et_poll_opt1)
        val etOpt2 = findViewById<EditText>(R.id.et_poll_opt2)
        val etOpt3 = findViewById<EditText>(R.id.et_poll_opt3)
        val btnAnon = findViewById<Button>(R.id.btn_poll_anonymous_toggle)
        val btnSubmit = findViewById<Button>(R.id.btn_poll_submit)
        val btnCancel = findViewById<Button>(R.id.btn_poll_cancel)

        val tvSoftLeft = findViewById<TextView>(R.id.tv_softkey_left)
        val tvSoftCenter = findViewById<TextView>(R.id.tv_softkey_center)
        val tvSoftRight = findViewById<TextView>(R.id.tv_softkey_right)
        val softkeyBar = findViewById<View>(R.id.softkey_bar)

        if (tvSoftLeft != null && tvSoftCenter != null && tvSoftRight != null) {
            softkeyManager = SoftkeyManager(softkeyBar, tvSoftLeft, tvSoftCenter, tvSoftRight)
            softkeyManager.update(
                left = "Clear",
                center = "Submit",
                right = "Cancel",
                onLeft = {
                    val focusedEdit = currentFocus as? EditText
                    if (focusedEdit != null) {
                        engines[focusedEdit.id]?.commitCurrentChar()
                        focusedEdit.setText("")
                    }
                },
                onCenter = {
                    engines.values.forEach { it.commitCurrentChar() }
                    val current = currentFocus
                    if (current is Button) {
                        current.performClick()
                    } else {
                        btnSubmit?.performClick()
                    }
                },
                onRight = {
                    engines.values.forEach { it.commitCurrentChar() }
                    dismiss()
                },
            )
        }

        listOfNotNull(etQuestion, etOpt1, etOpt2, etOpt3).forEach { field ->
            engines[field.id] =
                KeypadInputEngine(
                    editText = field,
                    modeIndicator = tvMode,
                    timeoutMs = timeoutMs,
                    onSymbolRequested = {
                        SymbolsDialog(context) { sym -> engines[field.id]?.insertSymbol(sym) }.show()
                    },
                )
        }

        btnAnon?.setOnClickListener {
            isAnonymous = !isAnonymous
            btnAnon.text = if (isAnonymous) "☑ Anonymous Voting: ON" else "☐ Anonymous Voting: OFF"
        }

        btnSubmit?.setOnClickListener {
            engines.values.forEach { it.commitCurrentChar() }
            val question = etQuestion?.text?.toString()?.trim().orEmpty()
            val opts =
                listOfNotNull(
                    etOpt1?.text?.toString()?.trim(),
                    etOpt2?.text?.toString()?.trim(),
                    etOpt3?.text?.toString()?.trim(),
                ).filter { it.isNotBlank() }

            if (question.isNotBlank() && opts.size >= 2) {
                dismiss()
                onCreatePoll(question, opts, isAnonymous)
            }
        }

        btnCancel?.setOnClickListener {
            engines.values.forEach { it.commitCurrentChar() }
            dismiss()
        }

        etQuestion?.requestFocus()
    }

    override fun onBackPressed() {
        engines.values.forEach { it.commitCurrentChar() }
        dismiss()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (::softkeyManager.isInitialized && softkeyManager.handleKeyEvent(keyCode, event)) {
            return true
        }

        if (isBackOrRightSoftKey(keyCode)) {
            engines.values.forEach { it.commitCurrentChar() }
            dismiss()
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        ) {
            engines.values.forEach { it.commitCurrentChar() }
        }

        val focusedEdit = currentFocus as? EditText
        if (focusedEdit != null) {
            if (SoftkeyUtils.isSoftLeftKey(keyCode)) {
                engines[focusedEdit.id]?.commitCurrentChar()
                focusedEdit.setText("")
                return true
            }
            val isInputKey =
                (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) ||
                    keyCode == KeyEvent.KEYCODE_STAR ||
                    keyCode == KeyEvent.KEYCODE_POUND ||
                    keyCode == KeyEvent.KEYCODE_DEL
            if (isInputKey && engines[focusedEdit.id]?.handleKeyEvent(keyCode, event) == true) {
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }
}
