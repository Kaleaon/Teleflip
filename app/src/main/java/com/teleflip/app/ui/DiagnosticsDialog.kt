package com.teleflip.app.ui

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import com.teleflip.app.R
import com.teleflip.app.report.AdbReporter

class DiagnosticsDialog(context: Context) : BaseDialog(context) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_diagnostics)
        setupSoftkeys(
            left = "",
            center = "",
            right = "Close",
            onLeft = null,
            onCenter = null,
            onRight = { dismiss() },
        )

        val tvLogs = findViewById<TextView>(R.id.tv_diag_logs)
        val btnClose = findViewById<Button>(R.id.btn_close_diag)

        val logs = AdbReporter.getRecentLogs(30)
        tvLogs?.text = logs.joinToString("\n")

        btnClose?.setOnClickListener {
            dismiss()
        }

        btnClose?.requestFocus()
    }

    override fun onBackPressed() {
        dismiss()
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        return super.onKeyDown(keyCode, event)
    }
}
