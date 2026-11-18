package com.teleflip.app.ui

import android.app.Dialog
import android.view.KeyEvent

/**
 * Attaches an OnKeyListener to this Dialog that executes the [onDismiss] callback
 * (which defaults to dismissing the dialog) when KEYCODE_BACK or KEYCODE_SOFT_RIGHT is pressed.
 */
fun Dialog.setupDismissKeys(onDismiss: () -> Unit = { dismiss() }) {
    setOnKeyListener { _, keyCode, event ->
        if (SoftkeyUtils.isBackOrSoftRightKey(keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                onDismiss()
            }
            true
        } else {
            false
        }
    }
}
