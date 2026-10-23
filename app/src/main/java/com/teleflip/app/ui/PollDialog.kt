package com.teleflip.app.ui

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.teleflip.app.R
import com.teleflip.app.input.KeypadInputEngine

class PollDialog(
    context: Context,
    private val timeoutMs: Long = 500L,
    private val onCreatePoll: (question: String, options: List<String>, isAnonymous: Boolean) -> Unit,
) : BaseDialog(context) {
    private val engines = mutableMapOf<Int, KeypadInputEngine>()
    private var isAnonymous = true

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

        listOfNotNull(etQuestion, etOpt1, etOpt2, etOpt3).forEach { field ->
            field.addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int,
                    ) {}

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int,
                    ) {}

                    override fun afterTextChanged(s: Editable?) {
                        if (field.error != null) {
                            field.error = null
                        }
                    }
                },
            )

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

            etQuestion?.error = null
            etOpt1?.error = null
            etOpt2?.error = null
            etOpt3?.error = null

            val question = etQuestion?.text?.toString()?.trim().orEmpty()
            val opt1 = etOpt1?.text?.toString()?.trim().orEmpty()
            val opt2 = etOpt2?.text?.toString()?.trim().orEmpty()
            val opt3 = etOpt3?.text?.toString()?.trim().orEmpty()

            val opts = listOf(opt1, opt2, opt3).filter { it.isNotBlank() }

            if (question.isBlank()) {
                val errorMsg = "Question cannot be empty"
                etQuestion?.error = errorMsg
                etQuestion?.requestFocus()
                Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (opts.size < 2) {
                val errorMsg = "At least 2 options required"
                val targetField =
                    when {
                        opt1.isBlank() -> etOpt1
                        opt2.isBlank() -> etOpt2
                        else -> etOpt1
                    }
                targetField?.error = errorMsg
                targetField?.requestFocus()
                Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dismiss()
            onCreatePoll(question, opts, isAnonymous)
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
        if (isBackOrRightSoftKey(keyCode)) {
            engines.values.forEach { it.commitCurrentChar() }
            dismiss()
            return true
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
