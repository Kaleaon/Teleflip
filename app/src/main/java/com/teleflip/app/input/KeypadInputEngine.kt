package com.teleflip.app.input

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.EditText
import android.widget.TextView

enum class InputMode(val label: String) {
    CAP_FIRST("Abc"),
    LOWER("abc"),
    UPPER("ABC"),
    NUMERIC("123");

    fun next(): InputMode = when (this) {
        CAP_FIRST -> LOWER
        LOWER -> UPPER
        UPPER -> NUMERIC
        NUMERIC -> CAP_FIRST
    }
}

class KeypadInputEngine(
    private val editText: EditText,
    private val modeIndicator: TextView? = null,
    private val onSymbolRequested: (() -> Unit)? = null
) {
    var currentMode = InputMode.CAP_FIRST
        private set

    private var lastKeyCode = -1
    private var charIndex = 0
    private var lastKeyTime = 0L
    private val timeoutMs = 900L
    private val handler = Handler(Looper.getMainLooper())
    private val commitRunnable = Runnable { commitCurrentChar() }

    private val keyMap = mapOf(
        KeyEvent.KEYCODE_1 to listOf(".", ",", "?", "!", "@", "-", "_", "/", ":", ";", "'", "\"", "+", "=", "1"),
        KeyEvent.KEYCODE_2 to listOf("a", "b", "c", "2"),
        KeyEvent.KEYCODE_3 to listOf("d", "e", "f", "3"),
        KeyEvent.KEYCODE_4 to listOf("g", "h", "i", "4"),
        KeyEvent.KEYCODE_5 to listOf("j", "k", "l", "5"),
        KeyEvent.KEYCODE_6 to listOf("m", "n", "o", "6"),
        KeyEvent.KEYCODE_7 to listOf("p", "q", "r", "s", "7"),
        KeyEvent.KEYCODE_8 to listOf("t", "u", "v", "8"),
        KeyEvent.KEYCODE_9 to listOf("w", "x", "y", "z", "9"),
        KeyEvent.KEYCODE_0 to listOf(" ", "0", "+")
    )

    init {
        updateModeIndicator()
    }

    fun setMode(mode: InputMode) {
        commitCurrentChar()
        currentMode = mode
        updateModeIndicator()
    }

    fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        val isHandledKey = keyCode in keyMap.keys ||
                           keyCode == KeyEvent.KEYCODE_STAR ||
                           keyCode == KeyEvent.KEYCODE_POUND ||
                           keyCode == KeyEvent.KEYCODE_DEL

        if (!isHandledKey) return false

        // Always consume ACTION_UP for our handled keys so system IME doesn't process them
        if (event.action == KeyEvent.ACTION_UP) {
            return true
        }

        if (event.action != KeyEvent.ACTION_DOWN) return false

        // Open Symbols Picker with * (STAR)
        if (keyCode == KeyEvent.KEYCODE_STAR) {
            commitCurrentChar()
            onSymbolRequested?.invoke()
            return true
        }

        // Toggle Input Mode with # (POUND)
        if (keyCode == KeyEvent.KEYCODE_POUND) {
            commitCurrentChar()
            currentMode = currentMode.next()
            updateModeIndicator()
            return true
        }

        // Backspace / Delete
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            commitCurrentChar()
            val start = editText.selectionStart
            val end = editText.selectionEnd
            if (start > 0 && start == end) {
                editText.text.delete(start - 1, start)
                editText.setSelection(start - 1)
                return true
            } else if (start != end) {
                editText.text.delete(Math.min(start, end), Math.max(start, end))
                editText.setSelection(Math.min(start, end))
                return true
            }
            return false
        }

        // Multi-tap for 0-9
        if (keyCode in keyMap.keys) {
            if (currentMode == InputMode.NUMERIC) {
                val numChar = when (keyCode) {
                    KeyEvent.KEYCODE_0 -> "0"
                    KeyEvent.KEYCODE_1 -> "1"
                    KeyEvent.KEYCODE_2 -> "2"
                    KeyEvent.KEYCODE_3 -> "3"
                    KeyEvent.KEYCODE_4 -> "4"
                    KeyEvent.KEYCODE_5 -> "5"
                    KeyEvent.KEYCODE_6 -> "6"
                    KeyEvent.KEYCODE_7 -> "7"
                    KeyEvent.KEYCODE_8 -> "8"
                    KeyEvent.KEYCODE_9 -> "9"
                    else -> ""
                }
                insertString(numChar)
                return true
            }

            val chars = keyMap[keyCode] ?: return false
            val now = System.currentTimeMillis()

            handler.removeCallbacks(commitRunnable)

            if (keyCode == lastKeyCode && (now - lastKeyTime) < timeoutMs) {
                // Cycle to next character on same key
                charIndex = (charIndex + 1) % chars.size
                val rawChar = chars[charIndex]
                val formattedChar = formatChar(rawChar, isReplacement = true)
                replaceLastChar(formattedChar)
            } else {
                // New key pressed
                commitCurrentChar()
                lastKeyCode = keyCode
                charIndex = 0
                val rawChar = chars[0]
                val formattedChar = formatChar(rawChar, isReplacement = false)
                insertString(formattedChar)
            }

            lastKeyTime = now
            handler.postDelayed(commitRunnable, timeoutMs)
            return true
        }

        return false
    }

    fun commitCurrentChar() {
        lastKeyCode = -1
        charIndex = 0
        handler.removeCallbacks(commitRunnable)
    }

    private fun formatChar(charStr: String, isReplacement: Boolean): String {
        return when (currentMode) {
            InputMode.UPPER -> charStr.uppercase()
            InputMode.LOWER -> charStr.lowercase()
            InputMode.CAP_FIRST -> {
                val targetIndex = if (isReplacement) editText.selectionStart - 1 else editText.selectionStart
                if (targetIndex <= 0) {
                    charStr.uppercase()
                } else {
                    val prevChar = editText.text.getOrNull(targetIndex - 1)
                    if (prevChar == ' ' || prevChar == '.' || prevChar == '\n' || prevChar == null) {
                        charStr.uppercase()
                    } else {
                        charStr.lowercase()
                    }
                }
            }
            InputMode.NUMERIC -> charStr
        }
    }

    private fun insertString(str: String) {
        val start = Math.max(editText.selectionStart, 0)
        val end = Math.max(editText.selectionEnd, 0)
        editText.text.replace(Math.min(start, end), Math.max(start, end), str, 0, str.length)
        editText.setSelection(Math.min(start, end) + str.length)
    }

    private fun replaceLastChar(newChar: String) {
        val pos = editText.selectionStart
        if (pos > 0) {
            editText.text.replace(pos - 1, pos, newChar)
            editText.setSelection(pos)
        } else {
            insertString(newChar)
        }
    }

    fun insertSymbol(symbol: String) {
        commitCurrentChar()
        insertString(symbol)
    }

    private fun updateModeIndicator() {
        modeIndicator?.text = "[#] ${currentMode.label}"
        modeIndicator?.announceForAccessibility("Input mode ${currentMode.label}")
    }
}
