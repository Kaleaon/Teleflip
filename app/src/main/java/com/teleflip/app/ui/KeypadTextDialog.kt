package com.teleflip.app.ui

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.teleflip.app.input.KeypadInputEngine

/** A reusable small-screen form: T9 input, LSK submit, OK, and RSK cancel. */
class KeypadTextDialog(
    context: Context,
    private val title: String,
    private val initial: String = "",
    private val secret: Boolean = false,
    private val timeoutMs: Long = 500L,
    private val submitLabel: String = "OK",
    private val numeric: Boolean = false,
    private val onSubmit: (String) -> Unit,
) : BaseDialog(context) {
    private lateinit var input: EditText
    private var engine: KeypadInputEngine? = null
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (8 * context.resources.displayMetrics.density).toInt()
        val layout =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, padding)
            }
        layout.addView(
            TextView(context).apply {
                text = title
                textSize = 17f
            },
        )
        val mode = TextView(context)
        input =
            EditText(context).apply {
                contentDescription = title
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setText(initial)
                setSelection(text.length)
                maxLines = 5
            }
        layout.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        layout.addView(mode)
        layout.addView(
            Button(context).apply {
                text = submitLabel
                setOnClickListener { submit() }
            },
        )
        layout.addView(
            Button(context).apply {
                text = "Cancel"
                setOnClickListener { dismiss() }
            },
        )
        layout.addView(
            TextView(context).apply {
                text = "LSK: $submitLabel • RSK: Cancel • #: Case • *: Symbols"
                textSize = 12f
            },
        )
        setContentView(ScrollView(context).apply { addView(layout) })
        engine = KeypadInputEngine(input, mode, timeoutMs, onSymbolRequested = { SymbolsDialog(context) { engine?.insertSymbol(it) }.show() })
        if (numeric) {
            engine?.let {
                it.setMode(com.teleflip.app.input.InputMode.NUMERIC)
                it.isModeSwitchingEnabled = false
            }
        }
        input.requestFocus()
    }

    private fun submit() {
        if (completed) return
        completed = true
        engine?.commitCurrentChar()
        val value = input.text.toString()
        dismiss()
        onSubmit(value)
    }

    override fun dismiss() {
        engine?.commitCurrentChar()
        super.dismiss()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (isBackOrRightSoftKey(code) || SoftkeyUtils.isSoftLeftKey(code) || (SoftkeyUtils.isCenterKey(code) && ::input.isInitialized && input.hasFocus())) {
            if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(code, event)
            return true
        }
        if (::input.isInitialized && input.hasFocus() && engine?.handleKeyEvent(code, event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (isBackOrRightSoftKey(keyCode)) {
            dismiss()
            return true
        }
        if (SoftkeyUtils.isSoftLeftKey(keyCode)) {
            if (event.repeatCount == 0) submit()
            return true
        }
        if (input.hasFocus() && engine?.handleKeyEvent(keyCode, event) == true) return true
        if (SoftkeyUtils.isCenterKey(keyCode) && input.hasFocus()) {
            if (event.repeatCount == 0) submit()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
