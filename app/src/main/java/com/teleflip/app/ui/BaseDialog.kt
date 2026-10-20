package com.teleflip.app.ui

import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import com.teleflip.app.MainActivity
import com.teleflip.app.R

fun Context.findMainActivity(): MainActivity? =
    when (this) {
        is MainActivity -> this
        is ContextWrapper -> baseContext.findMainActivity()
        else -> null
    }

abstract class BaseDialog(
    context: Context,
    themeResId: Int = R.style.Theme_TeleFlip,
) : Dialog(context, themeResId) {
    override fun show() {
        context.findMainActivity()?.captureFocusForDialog()
        super.show()
    }

    override fun onStart() {
        super.onStart()
        context.findMainActivity()?.onDialogShown()
    }

    override fun onStop() {
        super.onStop()
        context.findMainActivity()?.onDialogDismissed()
    }

    protected fun isBackOrRightSoftKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_SOFT_RIGHT ||
            keyCode == 229 || keyCode == 230 ||
            keyCode == 61 || keyCode == 158
    }
}
