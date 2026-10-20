package com.teleflip.app.ui

import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
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

    protected open fun getErrorTextView(): TextView? = null

    fun showDialogError(message: String, targetView: View? = null) {
        val errorTv = getErrorTextView()
        if (errorTv != null) {
            errorTv.text = message
            errorTv.visibility = View.VISIBLE
            errorTv.announceForAccessibility(message)
        } else {
            val root = window?.decorView ?: context.findMainActivity()?.window?.decorView
            if (root != null) {
                StatusMessageDispatcher.showStatus(root, message, isError = true)
            }
        }
        targetView?.requestFocus()
    }

    fun clearDialogError() {
        val errorTv = getErrorTextView()
        if (errorTv != null) {
            errorTv.text = ""
            errorTv.visibility = View.GONE
        }
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
