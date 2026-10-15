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

        this.onLeftClick = onLeft
        this.onCenterClick = onCenter
        this.onRightClick = onRight
    }

    fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        // Top Left Button (Soft Left)
        if (SoftkeyUtils.isSoftLeftKey(keyCode)) {
            onLeftClick?.let {
                it.invoke()
                return true
            }
        }

        // Top Right Button (Soft Right)
        if (SoftkeyUtils.isSoftRightKey(keyCode)) {
            onRightClick?.let {
                it.invoke()
                return true
            }
        }

        // Center / Enter Key
        if (SoftkeyUtils.isCenterKey(keyCode)) {
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
