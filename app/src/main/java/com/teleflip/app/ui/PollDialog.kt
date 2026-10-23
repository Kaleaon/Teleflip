package com.teleflip.app.ui

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.teleflip.app.R
import com.teleflip.app.input.KeypadInputEngine

class PollDialog(
    context: Context,
    private val timeoutMs: Long = 500L,
    private val onCreatePoll: (
        question: String,
        options: List<String>,
        isAnonymous: Boolean,
        isQuiz: Boolean,
        correctOptionIndex: Int,
        explanation: String,
        allowsMultipleAnswers: Boolean,
    ) -> Unit,
) : BaseDialog(context) {
    constructor(
        context: Context,
        timeoutMs: Long = 500L,
        onCreatePoll: (question: String, options: List<String>, isAnonymous: Boolean) -> Unit,
    ) : this(
        context = context,
        timeoutMs = timeoutMs,
        onCreatePoll = { question, options, isAnonymous, _, _, _, _ ->
            onCreatePoll(question, options, isAnonymous)
        },
    )

    private val engines = mutableMapOf<Int, KeypadInputEngine>()
    private var isAnonymous = true
    private var isQuiz = false
    private var correctOptionIndex = 0
    private var allowsMultipleAnswers = false
    private var currentStep = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_create_poll)
        setupDismissKeys()

        val tvStepTitle = findViewById<TextView>(R.id.tv_poll_step_title)
        val tvMode = findViewById<TextView>(R.id.tv_poll_input_mode)

        val layoutStep1 = findViewById<LinearLayout>(R.id.layout_step1)
        val layoutStep2 = findViewById<LinearLayout>(R.id.layout_step2)

        val etQuestion = findViewById<EditText>(R.id.et_poll_question)
        val etOpt1 = findViewById<EditText>(R.id.et_poll_opt1)
        val etOpt2 = findViewById<EditText>(R.id.et_poll_opt2)
        val etOpt3 = findViewById<EditText>(R.id.et_poll_opt3)
        val etOpt4 = findViewById<EditText>(R.id.et_poll_opt4)
        val btnNext = findViewById<Button>(R.id.btn_poll_next)
        val btnCancel = findViewById<Button>(R.id.btn_poll_cancel)

        val tvQuestionSummary = findViewById<TextView>(R.id.tv_poll_step2_question_summary)
        val btnAnon = findViewById<Button>(R.id.btn_poll_anonymous_toggle)
        val btnModeToggle = findViewById<Button>(R.id.btn_poll_mode_toggle)

        val layoutQuizSettings = findViewById<LinearLayout>(R.id.layout_quiz_settings)
        val btnCorrectChoice = findViewById<Button>(R.id.btn_poll_correct_choice)
        val etExplanation = findViewById<EditText>(R.id.et_poll_explanation)

        val layoutRegularSettings = findViewById<LinearLayout>(R.id.layout_regular_settings)
        val btnMultipleAnswersToggle = findViewById<Button>(R.id.btn_poll_multiple_answers_toggle)

        val btnSubmit = findViewById<Button>(R.id.btn_poll_submit)
        val btnBack = findViewById<Button>(R.id.btn_poll_back)

        val editFields = listOfNotNull(etQuestion, etOpt1, etOpt2, etOpt3, etOpt4, etExplanation)
        editFields.forEach { field ->
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

        fun getValidOptions(): List<String> {
            return listOfNotNull(
                etOpt1?.text?.toString()?.trim(),
                etOpt2?.text?.toString()?.trim(),
                etOpt3?.text?.toString()?.trim(),
                etOpt4?.text?.toString()?.trim(),
            ).filter { it.isNotBlank() }
        }

        fun updateCorrectChoiceButtonText() {
            val opts = getValidOptions()
            if (opts.isEmpty()) return
            if (correctOptionIndex !in opts.indices) {
                correctOptionIndex = 0
            }
            btnCorrectChoice?.text = "Correct Answer: ${correctOptionIndex + 1}. ${opts[correctOptionIndex]}"
        }

        fun updateStepVisibility() {
            if (currentStep == 1) {
                layoutStep1?.visibility = View.VISIBLE
                layoutStep2?.visibility = View.GONE
                tvStepTitle?.text = "📊 Create Poll (Step 1/2)"
            } else {
                layoutStep1?.visibility = View.GONE
                layoutStep2?.visibility = View.VISIBLE
                tvStepTitle?.text = "⚙️ Poll Settings (Step 2/2)"

                val q = etQuestion?.text?.toString()?.trim().orEmpty()
                tvQuestionSummary?.text = "Q: $q"

                updateCorrectChoiceButtonText()

                if (isQuiz) {
                    layoutQuizSettings?.visibility = View.VISIBLE
                    layoutRegularSettings?.visibility = View.GONE
                } else {
                    layoutQuizSettings?.visibility = View.GONE
                    layoutRegularSettings?.visibility = View.VISIBLE
                }
            }
        }

        btnNext?.setOnClickListener {
            engines.values.forEach { it.commitCurrentChar() }
            val question = etQuestion?.text?.toString()?.trim().orEmpty()
            val opts = getValidOptions()

            if (question.isNotBlank() && opts.size >= 2) {
                currentStep = 2
                updateStepVisibility()
                btnModeToggle?.requestFocus()
            } else {
                if (question.isBlank()) {
                    etQuestion?.requestFocus()
                } else {
                    etOpt2?.requestFocus()
                }
            }
        }

        btnCancel?.setOnClickListener {
            engines.values.forEach { it.commitCurrentChar() }
            dismiss()
        }

        btnAnon?.setOnClickListener {
            isAnonymous = !isAnonymous
            btnAnon.text = if (isAnonymous) "☑ Anonymous Voting: ON" else "☐ Anonymous Voting: OFF"
        }

        btnModeToggle?.setOnClickListener {
            isQuiz = !isQuiz
            btnModeToggle.text = if (isQuiz) "Poll Type: 💡 Trivia Quiz" else "Poll Type: 📊 Regular Survey"
            updateStepVisibility()
        }

        btnCorrectChoice?.setOnClickListener {
            val opts = getValidOptions()
            if (opts.isNotEmpty()) {
                correctOptionIndex = (correctOptionIndex + 1) % opts.size
                updateCorrectChoiceButtonText()
            }
        }

        btnMultipleAnswersToggle?.setOnClickListener {
            allowsMultipleAnswers = !allowsMultipleAnswers
            btnMultipleAnswersToggle.text =
                if (allowsMultipleAnswers) "☑ Multiple Answers: ON" else "☐ Multiple Answers: OFF"
        }

        btnBack?.setOnClickListener {
            engines.values.forEach { it.commitCurrentChar() }
            currentStep = 1
            updateStepVisibility()
            etQuestion?.requestFocus()
        }

        btnSubmit?.setOnClickListener {
            engines.values.forEach { it.commitCurrentChar() }
            val question = etQuestion?.text?.toString()?.trim().orEmpty()
            val opts = getValidOptions()
            val explanation = etExplanation?.text?.toString()?.trim().orEmpty()

            if (question.isNotBlank() && opts.size >= 2) {
                dismiss()
                val safeCorrectIndex =
                    if (isQuiz && opts.isNotEmpty()) {
                        correctOptionIndex.coerceIn(0, opts.size - 1)
                    } else {
                        -1
                    }
                onCreatePoll(
                    question,
                    opts,
                    isAnonymous,
                    isQuiz,
                    safeCorrectIndex,
                    if (isQuiz) explanation else "",
                    if (!isQuiz) allowsMultipleAnswers else false,
                )
            }
        }

        updateStepVisibility()
        etQuestion?.requestFocus()
    }

    override fun onBackPressed() {
        engines.values.forEach { it.commitCurrentChar() }
        if (currentStep == 2) {
            currentStep = 1
            findViewById<LinearLayout>(R.id.layout_step1)?.visibility = View.VISIBLE
            findViewById<LinearLayout>(R.id.layout_step2)?.visibility = View.GONE
            findViewById<TextView>(R.id.tv_poll_step_title)?.text = "📊 Create Poll (Step 1/2)"
            findViewById<EditText>(R.id.et_poll_question)?.requestFocus()
        } else {
            dismiss()
        }
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (isBackOrRightSoftKey(keyCode)) {
            engines.values.forEach { it.commitCurrentChar() }
            if (currentStep == 2) {
                currentStep = 1
                findViewById<LinearLayout>(R.id.layout_step1)?.visibility = View.VISIBLE
                findViewById<LinearLayout>(R.id.layout_step2)?.visibility = View.GONE
                findViewById<TextView>(R.id.tv_poll_step_title)?.text = "📊 Create Poll (Step 1/2)"
                findViewById<EditText>(R.id.et_poll_question)?.requestFocus()
            } else {
                dismiss()
            }
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
