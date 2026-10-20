package com.teleflip.app.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.Toast
import com.google.android.material.snackbar.Snackbar

object StatusMessageDispatcher {

    fun showStatus(activity: Activity, message: String, isError: Boolean = false) {
        val rootView = activity.findViewById<View>(android.R.id.content) ?: activity.window?.decorView
        if (rootView != null) {
            showStatus(rootView, message, isError)
        } else {
            Toast.makeText(activity, message, if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    fun showStatus(context: Context, message: String, isError: Boolean = false) {
        val activity = context.findMainActivity()
        if (activity != null) {
            showStatus(activity, message, isError)
        } else {
            Toast.makeText(context, message, if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    fun showStatus(view: View, message: String, isError: Boolean = false) {
        // Ensure screen readers immediately announce status updates
        view.announceForAccessibility(message)

        try {
            val duration = if (isError) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
            val snackbar = Snackbar.make(view, message, duration)
            if (isError) {
                snackbar.setAction("OK") { snackbar.dismiss() }
            }
            snackbar.show()
        } catch (e: Exception) {
            // Fallback for environment without Material theme or valid decor view in tests
            Toast.makeText(view.context, message, if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }
}
