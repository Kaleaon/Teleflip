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
    protected var softkeyManager: SoftkeyManager? = null
        private set

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

    override fun setContentView(view: View, params: ViewGroup.LayoutParams?) {
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
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (softkeyManager?.handleKeyEvent(keyCode, event) == true) {
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    protected fun isBackOrRightSoftKey(keyCode: Int): Boolean {
        return SoftkeyUtils.isBackOrSoftRightKey(keyCode)
    }
}
