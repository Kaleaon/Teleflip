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

    private fun formatLogLine(level: String, component: String, message: String): String {
        val timestamp = synchronized(dateFormat) {
            dateFormat.format(Date())
        }
        return "[$timestamp] [$level] [$component] $message"
    }

    private fun writeLog(
        level: String,
        component: String,
        message: String,
        throwable: Throwable? = null,
        bannerChar: Char? = null
    ) {
        val logLine = formatLogLine(level, component, message)

        if (bannerChar != null) {
            val banner = bannerChar.toString().repeat(50)
            Log.e(TAG, banner)
            Log.e(TAG, logLine)
            throwable?.let {
                Log.e(TAG, Log.getStackTraceString(it))
            }
            Log.e(TAG, banner)
        } else {
            when (level) {
                "WARN" -> Log.w(TAG, logLine)
                "INFO", "INFO_POPUP" -> Log.i(TAG, logLine)
                else -> Log.e(TAG, logLine)
            }
        }

        val fileContent = if (throwable != null) {
            "$logLine\n${Log.getStackTraceString(throwable)}\n"
        } else {
            "$logLine\n"
        }
        writeToFile(fileContent)
    }

    fun reportPopup(source: String, message: String, isError: Boolean = false) {
        val level = if (isError) "ERROR_POPUP" else "INFO_POPUP"
        val bannerChar = if (isError) '-' else null
        writeLog(level, source, message, bannerChar = bannerChar)
    }

    fun reportError(component: String, message: String, throwable: Throwable? = null) {
        writeLog("ERROR", component, message, throwable = throwable, bannerChar = '=')
    }

    fun reportInfo(component: String, message: String) {
        writeLog("INFO", component, message)
    }

    fun reportCrash(contextInfo: String, throwable: Throwable) {
        writeLog("FATAL CRASH", contextInfo, throwable.message ?: "null", throwable = throwable, bannerChar = '!')
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
