package com.teleflip.app.ui

import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
    var softkeyManager: SoftkeyManager? = null
        protected set

    override fun show() {
        context.findMainActivity()?.captureFocusForDialog()
        super.show()
    }

    protected open fun getErrorTextView(): TextView? = null

    fun showDialogError(
        message: String,
        targetView: View? = null,
    ) {
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

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        initSoftkeyManager()
    }

    override fun setContentView(view: View) {
        super.setContentView(view)
        initSoftkeyManager()
    }

    override fun setContentView(
        view: View,
        params: ViewGroup.LayoutParams?,
    ) {
        super.setContentView(view, params)
        initSoftkeyManager()
    }

    protected fun initSoftkeyManager() {
        val bar = findViewById<View>(R.id.softkey_bar)
        val tvLeft = findViewById<TextView>(R.id.tv_softkey_left)
        val tvCenter = findViewById<TextView>(R.id.tv_softkey_center)
        val tvRight = findViewById<TextView>(R.id.tv_softkey_right)
        if (tvLeft != null && tvCenter != null && tvRight != null) {
            softkeyManager = SoftkeyManager(bar, tvLeft, tvCenter, tvRight)
        }
    }

    fun setupSoftkeys(
        left: String = "",
        center: String = "",
        right: String = "",
        onLeft: (() -> Unit)? = null,
        onCenter: (() -> Unit)? = null,
        onRight: (() -> Unit)? = null,
    ) {
        if (softkeyManager == null) {
            initSoftkeyManager()
        }
        softkeyManager?.update(left, center, right, onLeft, onCenter, onRight)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (softkeyManager?.handleKeyEvent(event.keyCode, event) == true) {
            return true
        }
        val isBack = event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK
        if (isBack) {
            onBackPressed()
        }
        return isBack || super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (softkeyManager?.handleKeyEvent(keyCode, event) == true) {
            return true
        }
        val isBack = keyCode == KeyEvent.KEYCODE_BACK
        if (isBack) {
            onBackPressed()
        }
        return isBack || super.onKeyDown(keyCode, event)
    }

    protected fun isSoftLeftKey(keyCode: Int): Boolean = SoftkeyUtils.isSoftLeftKey(keyCode)

    protected fun isSoftRightKey(keyCode: Int): Boolean = SoftkeyUtils.isSoftRightKey(keyCode)

    protected fun isBackOrRightSoftKey(keyCode: Int): Boolean = SoftkeyUtils.isBackOrSoftRightKey(keyCode)

    protected fun isCenterKey(keyCode: Int): Boolean = SoftkeyUtils.isCenterKey(keyCode)
}
