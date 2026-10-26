package com.teleflip.app.ui

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.teleflip.app.R
import com.teleflip.app.input.KeypadInputEngine

class NewChatDialog(
    context: Context,
    private val timeoutMs: Long = 500L,
    private val onStartChatByQuery: (String) -> Unit,
    private val onOpenSavedMessages: () -> Unit,
    private val onCreateGroup: (String) -> Unit,
    private val onCreateChannel: (String) -> Unit,
    private val onStartSecretChat: (String) -> Unit = {},
) : BaseDialog(context) {
    private var etInput: EditText? = null
    private var keypadEngine: KeypadInputEngine? = null
    private var softkeyManager: SoftkeyManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_new_chat)
        setupDismissKeys()

        val input = findViewById<EditText>(R.id.et_new_chat_input)
        val tvMode = findViewById<TextView>(R.id.tv_new_chat_input_mode)
        val btnDirect = findViewById<Button>(R.id.btn_start_direct_chat)
        val btnSaved = findViewById<Button>(R.id.btn_open_saved_messages)
        val btnGroup = findViewById<Button>(R.id.btn_create_group)
        val btnChannel = findViewById<Button>(R.id.btn_create_channel)
        val btnSecret = findViewById<Button>(R.id.btn_start_secret_chat)
        val btnCancel = findViewById<Button>(R.id.btn_cancel_new_chat)

        val softkeyBar = findViewById<View>(R.id.softkey_bar)
        val tvLeft = findViewById<TextView>(R.id.tv_softkey_left)
        val tvCenter = findViewById<TextView>(R.id.tv_softkey_center)
        val tvRight = findViewById<TextView>(R.id.tv_softkey_right)

        if (tvLeft != null && tvCenter != null && tvRight != null) {
            softkeyManager = SoftkeyManager(softkeyBar, tvLeft, tvCenter, tvRight)
        }

        etInput = input

        if (input != null) {
            keypadEngine =
                KeypadInputEngine(
                    editText = input,
                    modeIndicator = tvMode,
                    timeoutMs = timeoutMs,
                    onSymbolRequested = {
                        SymbolsDialog(context) { sym -> keypadEngine?.insertSymbol(sym) }.show()
                    },
                )
        }

        btnDirect?.setOnClickListener {
            keypadEngine?.commitCurrentChar()
            val query = input?.text?.toString()?.trim().orEmpty()
            dismiss()
            onStartChatByQuery(query)
        }

        btnSaved?.setOnClickListener {
            keypadEngine?.commitCurrentChar()
            dismiss()
            onOpenSavedMessages()
        }

        btnGroup?.setOnClickListener {
            keypadEngine?.commitCurrentChar()
            val title = input?.text?.toString()?.trim().orEmpty()
            dismiss()
            onCreateGroup(title)
        }

        btnChannel?.setOnClickListener {
            keypadEngine?.commitCurrentChar()
            val title = input?.text?.toString()?.trim().orEmpty()
            dismiss()
            onCreateChannel(title)
        }

        btnSecret?.setOnClickListener {
            keypadEngine?.commitCurrentChar()
            val query = input?.text?.toString()?.trim().orEmpty()
            dismiss()
            onStartSecretChat(query)
        }

        btnCancel?.setOnClickListener {
            keypadEngine?.commitCurrentChar()
            dismiss()
        }

        val focusListener = View.OnFocusChangeListener { _, _ -> updateSoftkeys() }
        input?.onFocusChangeListener = focusListener
        btnDirect?.onFocusChangeListener = focusListener
        btnSaved?.onFocusChangeListener = focusListener
        btnGroup?.onFocusChangeListener = focusListener
        btnChannel?.onFocusChangeListener = focusListener
        btnSecret?.onFocusChangeListener = focusListener
        btnCancel?.onFocusChangeListener = focusListener

        input?.addTextChangedListener(
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
                ) {
                    updateSoftkeys()
                }

                override fun afterTextChanged(s: Editable?) {}
            },
        )

        btnDirect?.requestFocus()
        updateSoftkeys()
    }

    private fun updateSoftkeys() {
        val input = etInput
        if (input?.hasFocus() == true) {
            softkeyManager?.update(
                left = "Clear",
                center = "Start",
                right = "Back",
                onLeft = {
                    keypadEngine?.commitCurrentChar()
                    input.setText("")
                },
                onCenter = {
                    findViewById<Button>(R.id.btn_start_direct_chat)?.performClick()
                },
                onRight = {
                    keypadEngine?.commitCurrentChar()
                    dismiss()
                },
            )
        } else {
            val hasText = !input?.text.isNullOrEmpty()
            softkeyManager?.update(
                left = if (hasText) "Clear Input" else "",
                center = "Select",
                right = "Back",
                onLeft = {
                    if (hasText) {
                        keypadEngine?.commitCurrentChar()
                        input?.setText("")
                    }
                },
                onCenter = {
                    val focused = currentFocus
                    if (focused != null && focused.isClickable) {
                        focused.performClick()
                    } else {
                        findViewById<Button>(R.id.btn_start_direct_chat)?.performClick()
                    }
                },
                onRight = {
                    keypadEngine?.commitCurrentChar()
                    dismiss()
                },
            )
        }
    }

    override fun onBackPressed() {
        keypadEngine?.commitCurrentChar()
        dismiss()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (softkeyManager?.handleKeyEvent(keyCode, event) == true) {
            return true
        }

        if (isBackOrRightSoftKey(keyCode)) {
            keypadEngine?.commitCurrentChar()
            dismiss()
            return true
        }

        if (SoftkeyUtils.isSoftLeftKey(keyCode)) {
            keypadEngine?.commitCurrentChar()
            etInput?.setText("")
            return true
        }

        val input = etInput
        val isInputKey =
            (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) ||
                keyCode == KeyEvent.KEYCODE_STAR ||
                keyCode == KeyEvent.KEYCODE_POUND ||
                keyCode == KeyEvent.KEYCODE_DEL

        if (input?.hasFocus() == true && isInputKey) {
            if (keypadEngine?.handleKeyEvent(keyCode, event) == true) {
                return true
            }
        } else if (input?.hasFocus() != true && keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_5) {
            val query = input?.text?.toString()?.trim().orEmpty()
            dismiss()
            when (keyCode) {
                KeyEvent.KEYCODE_1 -> onStartChatByQuery(query)
                KeyEvent.KEYCODE_2 -> onOpenSavedMessages()
                KeyEvent.KEYCODE_3 -> onCreateGroup(query)
                KeyEvent.KEYCODE_4 -> onCreateChannel(query)
                KeyEvent.KEYCODE_5 -> onStartSecretChat(query)
            }
            return true
        }

        return super.onKeyDown(keyCode, event)
    }
}
