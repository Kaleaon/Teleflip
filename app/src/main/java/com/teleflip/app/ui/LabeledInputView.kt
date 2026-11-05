package com.teleflip.app.ui

import android.content.Context
import android.graphics.Rect
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.teleflip.app.R

class LabeledInputView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : LinearLayout(context, attrs, defStyleAttr) {
        val labelView: TextView
        val modeView: TextView
        val editText: EditText
        val errorView: TextView

        val inputView: EditText
            get() = editText

        val modeIndicatorView: TextView
            get() = modeView

        var text: String
            get() = editText.text?.toString() ?: ""
            set(value) {
                editText.setText(value)
            }

        var labelText: CharSequence?
            get() = labelView.text
            set(value) {
                labelView.text = value
                labelView.visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
            }

        var errorText: CharSequence?
            get() = errorView.text
            set(value) {
                errorView.text = value
                errorView.visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
            }

        var inputModeText: CharSequence?
            get() = modeView.text
            set(value) {
                modeView.text = value
                modeView.visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
            }

        init {
            orientation = VERTICAL
            LayoutInflater.from(context).inflate(R.layout.view_labeled_input, this, true)

            labelView = findViewById(R.id.tv_labeled_input_label)
            modeView = findViewById(R.id.tv_labeled_input_mode)
            editText = findViewById(R.id.et_labeled_input_field)
            errorView = findViewById(R.id.tv_labeled_input_error)

            if (labelView.id == View.NO_ID || labelView.id == R.id.tv_labeled_input_label) {
                labelView.id = View.generateViewId()
            }
            if (editText.id == View.NO_ID || editText.id == R.id.et_labeled_input_field) {
                editText.id = View.generateViewId()
            }

            labelView.labelFor = editText.id

            if (attrs != null) {
                val a = context.theme.obtainStyledAttributes(attrs, R.styleable.LabeledInputView, defStyleAttr, 0)
                try {
                    labelText = a.getString(R.styleable.LabeledInputView_inputLabel)
                    val hint = a.getString(R.styleable.LabeledInputView_inputHint)
                    if (!hint.isNullOrEmpty()) {
                        editText.hint = hint
                    }
                    val type = a.getInt(R.styleable.LabeledInputView_inputType, -1)
                    if (type != -1) {
                        editText.inputType = type
                    }
                    if (a.hasValue(R.styleable.LabeledInputView_inputSingleLine)) {
                        editText.isSingleLine = a.getBoolean(R.styleable.LabeledInputView_inputSingleLine, false)
                    }
                    val maxLines = a.getInt(R.styleable.LabeledInputView_inputMaxLines, -1)
                    if (maxLines != -1) {
                        editText.maxLines = maxLines
                    }
                } finally {
                    a.recycle()
                }
            }
        }

        override fun requestFocus(
            direction: Int,
            previouslyFocusedRect: Rect?,
        ): Boolean {
            return editText.requestFocus(direction, previouslyFocusedRect)
        }

        override fun hasFocus(): Boolean {
            return editText.hasFocus() || super.hasFocus()
        }

        fun addTextChangedListener(watcher: TextWatcher) {
            editText.addTextChangedListener(watcher)
        }

        fun setSelection(index: Int) {
            editText.setSelection(index)
        }

        fun setError(error: CharSequence?) {
            errorText = error
        }
    }
