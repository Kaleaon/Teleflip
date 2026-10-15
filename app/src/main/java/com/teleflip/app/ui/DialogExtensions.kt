package com.teleflip.app.ui

import android.app.Dialog
import android.view.KeyEvent

/**
 * Attaches an OnKeyListener to this Dialog that dismisses the dialog
 * when KEYCODE_BACK or KEYCODE_SOFT_RIGHT is pressed.
 */
fun Dialog.setupDismissKeys() {
    setOnKeyListener { _, keyCode, event ->
        if (SoftkeyUtils.isBackOrSoftRightKey(keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                dismiss()
            }
            true
        } else {
            false
        }
    }
}
