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

    protected fun setupSoftkeys(
        left: String = "",
        center: String = "",
        right: String = "",
        onLeft: (() -> Unit)? = null,
        onCenter: (() -> Unit)? = null,
        onRight: (() -> Unit)? = null,
    ) {
        val bar: View? = findViewById(R.id.softkey_bar)
        val tvLeft: TextView? = findViewById(R.id.tv_softkey_left)
        val tvCenter: TextView? = findViewById(R.id.tv_softkey_center)
        val tvRight: TextView? = findViewById(R.id.tv_softkey_right)

        if (tvLeft != null && tvCenter != null && tvRight != null) {
            if (softkeyManager == null) {
                softkeyManager = SoftkeyManager(bar, tvLeft, tvCenter, tvRight)
            }
            softkeyManager?.update(left, center, right, onLeft, onCenter, onRight)
        }
    }

    protected fun isSoftLeftKey(keyCode: Int): Boolean = SoftkeyUtils.isSoftLeftKey(keyCode)

    protected fun isSoftRightKey(keyCode: Int): Boolean = SoftkeyUtils.isSoftRightKey(keyCode)

    protected fun isBackOrRightSoftKey(keyCode: Int): Boolean = SoftkeyUtils.isBackOrSoftRightKey(keyCode)

    protected fun isCenterKey(keyCode: Int): Boolean = SoftkeyUtils.isCenterKey(keyCode)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (softkeyManager?.handleKeyEvent(keyCode, event) == true) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

