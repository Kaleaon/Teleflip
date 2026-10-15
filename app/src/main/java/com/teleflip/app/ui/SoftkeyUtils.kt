package com.teleflip.app.ui

import android.view.KeyEvent

object SoftkeyUtils {
    val VENDOR_SOFT_LEFT_KEYCODES: Set<Int> = setOf(132, 60, 228)
    val VENDOR_SOFT_RIGHT_KEYCODES: Set<Int> = setOf(229, 230, 61, 158)

    fun isSoftLeftKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_SOFT_LEFT ||
            keyCode == KeyEvent.KEYCODE_MENU ||
            VENDOR_SOFT_LEFT_KEYCODES.contains(keyCode)
    }

    fun isSoftRightKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_SOFT_RIGHT ||
            VENDOR_SOFT_RIGHT_KEYCODES.contains(keyCode)
    }

    fun isBackOrSoftRightKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_BACK || isSoftRightKey(keyCode)
    }

    fun isCenterKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
    }
}
