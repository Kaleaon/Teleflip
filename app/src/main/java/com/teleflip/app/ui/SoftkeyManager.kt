package com.teleflip.app.ui

import android.view.KeyEvent
import android.widget.TextView

class SoftkeyManager(
    private val tvLeft: TextView,
    private val tvCenter: TextView,
    private val tvRight: TextView
) {
    private var onLeftClick: (() -> Unit)? = null
    private var onCenterClick: (() -> Unit)? = null
    private var onRightClick: (() -> Unit)? = null

    init {
        tvLeft.setOnClickListener { onLeftClick?.invoke() }
        tvCenter.setOnClickListener { onCenterClick?.invoke() }
        tvRight.setOnClickListener { onRightClick?.invoke() }
    }

    fun update(
        left: String = "",
        center: String = "",
        right: String = "",
        onLeft: (() -> Unit)? = null,
        onCenter: (() -> Unit)? = null,
        onRight: (() -> Unit)? = null
    ) {
        tvLeft.text = left
        tvCenter.text = center
        tvRight.text = right

        tvLeft.contentDescription = if (left.isNotBlank()) "$left softkey" else ""
        tvCenter.contentDescription = if (center.isNotBlank()) "$center softkey" else ""
        tvRight.contentDescription = if (right.isNotBlank()) "$right softkey" else ""

        this.onLeftClick = onLeft
        this.onCenterClick = onCenter
        this.onRightClick = onRight
    }

    fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        // Top Left Button (Soft Left)
        if (keyCode == KeyEvent.KEYCODE_SOFT_LEFT || keyCode == KeyEvent.KEYCODE_MENU ||
            keyCode == 132 /* KEYCODE_F2 */ || keyCode == 60 || keyCode == 228) {
            onLeftClick?.let {
                it.invoke()
                return true
            }
        }

        // Top Right Button (Soft Right)
        if (keyCode == KeyEvent.KEYCODE_SOFT_RIGHT || keyCode == 229 || keyCode == 230 ||
            keyCode == 61 || keyCode == 158) {
            onRightClick?.let {
                it.invoke()
                return true
            }
        }

        // Center / Enter Key
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            onCenterClick?.let {
                it.invoke()
                return true
            }
        }

        // Call button
        if (keyCode == KeyEvent.KEYCODE_CALL) {
            onCenterClick?.let {
                it.invoke()
                return true
            }
        }

        return false
    }

    fun handleBack(): Boolean {
        if (onRightClick != null) {
            onRightClick?.invoke()
            return true
        }
        return false
    }
}
