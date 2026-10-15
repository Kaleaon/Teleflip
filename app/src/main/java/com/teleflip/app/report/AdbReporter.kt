package com.teleflip.app.report

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AdbReporter {
    const val TAG = "TELEFLIP_REPORT"
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            logFile = File(dir, "teleflip_reports.log")

            // Install global uncaught exception handler
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                reportCrash("Thread: ${thread.name}", throwable)
                defaultHandler?.uncaughtException(thread, throwable)
            }

            reportInfo("AdbReporter", "Error reporting initialized. Log file: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AdbReporter: ${e.message}")
        }
    }

    fun reportPopup(source: String, message: String, isError: Boolean = false) {
        val level = if (isError) "ERROR_POPUP" else "INFO_POPUP"
        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] [$level] [$source] $message"
        if (isError) {
            Log.e(TAG, "--------------------------------------------------")
            Log.e(TAG, logLine)
            Log.e(TAG, "--------------------------------------------------")
        } else {
            Log.i(TAG, logLine)
        }
        writeToFile("$logLine\n")
    }

    fun reportError(component: String, message: String, throwable: Throwable? = null) {
        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] [ERROR] [$component] $message"
        Log.e(TAG, "==================================================")
        Log.e(TAG, logLine)
        throwable?.let {
            Log.e(TAG, Log.getStackTraceString(it))
        }
        Log.e(TAG, "==================================================")

        writeToFile("$logLine\n${throwable?.let { Log.getStackTraceString(it) } ?: ""}\n")
    }

    fun reportWarning(component: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] [WARN] [$component] $message"
        Log.w(TAG, logLine)
        writeToFile("$logLine\n")
    }

    fun reportInfo(component: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] [INFO] [$component] $message"
        Log.i(TAG, logLine)
        writeToFile("$logLine\n")
    }

    fun reportCrash(contextInfo: String, throwable: Throwable) {
        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] [FATAL CRASH] [$contextInfo] ${throwable.message}"
        Log.e(TAG, "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
        Log.e(TAG, logLine)
        Log.e(TAG, Log.getStackTraceString(throwable))
        Log.e(TAG, "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")

        writeToFile("$logLine\n${Log.getStackTraceString(throwable)}\n")
    }

    fun getRecentLogs(maxLines: Int = 30): List<String> {
        return try {
            val file = logFile
            if (file != null && file.exists()) {
                file.readLines().takeLast(maxLines)
            } else {
                listOf("No errors recorded.")
            }
        } catch (e: Exception) {
            listOf("Error reading log: ${e.message}")
        }
    }

    @Synchronized
    private fun writeToFile(text: String) {
        try {
            val file = logFile ?: return
            FileWriter(file, true).use { writer ->
                writer.append(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write report to file: ${e.message}")
        }
    }
}
