package com.teleflip.app.report

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AdbReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            "com.teleflip.app.REPORT_STATUS" -> {
                val sb = StringBuilder()
                sb.appendLine("==================================================")
                sb.appendLine("           TELEFLIP LIVE DIAGNOSTIC REPORT        ")
                sb.appendLine("==================================================")
                sb.appendLine("Timestamp: ${System.currentTimeMillis()}")
                val recent = AdbReporter.getRecentLogs(30)
                sb.appendLine("Recent recorded logs (${recent.size} entries):")
                recent.forEach { line -> sb.appendLine("  $line") }
                sb.appendLine("==================================================")
                Log.i(AdbReporter.TAG, sb.toString())
            }
            "com.teleflip.app.DUMP_LOGS" -> {
                Log.i(AdbReporter.TAG, ">>> BEGIN TELEFLIP FULL LOG DUMP >>>")
                AdbReporter.getRecentLogs(100).forEach { line ->
                    Log.i(AdbReporter.TAG, line)
                }
                Log.i(AdbReporter.TAG, "<<< END TELEFLIP FULL LOG DUMP <<<")
            }
            "com.teleflip.app.TEST_LOG" -> {
                val testMsg = intent.getStringExtra("message") ?: "Manual test popup trigger via ADB"
                AdbReporter.reportPopup("ADB_BROADCAST", testMsg, isError = true)
            }
        }
    }
}
