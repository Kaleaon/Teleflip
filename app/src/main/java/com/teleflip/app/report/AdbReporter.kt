package com.teleflip.app.report

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

object AdbReporter {
    const val TAG = "TELEFLIP_REPORT"
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val logQueue = ConcurrentLinkedQueue<String>()
    private val signalChannel = Channel<Unit>(Channel.UNLIMITED)

    private val fileLock = Any()
    private var writer: BufferedWriter? = null

    private val workerExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "AdbReporter-Worker").apply { isDaemon = true }
        }
    private val workerScope = CoroutineScope(workerExecutor.asCoroutineDispatcher() + SupervisorJob())

    init {
        workerScope.launch {
            for (signal in signalChannel) {
                drainQueue()
            }
        }
    }

    @JvmStatic
    fun init(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            setLogFile(File(dir, "teleflip_reports.log"))

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

    @JvmStatic
    fun setLogFile(file: File) {
        synchronized(fileLock) {
            closeWriterQuietly()
            logFile = file
        }
    }

    private fun formatLogLine(
        level: String,
        component: String,
        message: String,
    ): String {
        val timestamp =
            synchronized(dateFormat) {
                dateFormat.format(Date())
            }
        return "[$timestamp] [$level] [$component] $message"
    }

    private fun writeLog(
        level: String,
        component: String,
        message: String,
        throwable: Throwable? = null,
        bannerChar: Char? = null,
        syncFlush: Boolean = false,
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

        val fileContent =
            if (throwable != null) {
                val stackTrace = getStackTraceString(throwable)
                if (stackTrace.isNotEmpty()) "$logLine\n$stackTrace\n" else "$logLine\n"
            } else {
                "$logLine\n"
            }
        writeToFile(fileContent, syncFlush = syncFlush)
    }

    @JvmStatic
    @JvmOverloads
    fun reportPopup(
        source: String,
        message: String,
        isError: Boolean = false,
    ) {
        val level = if (isError) "ERROR_POPUP" else "INFO_POPUP"
        val bannerChar = if (isError) '-' else null
        writeLog(level, source, message, bannerChar = bannerChar, syncFlush = isError)
    }

    @JvmStatic
    @JvmOverloads
    fun reportError(
        component: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        writeLog("ERROR", component, message, throwable = throwable, bannerChar = '=', syncFlush = true)
    }

    @JvmStatic
    fun reportWarning(
        component: String,
        message: String,
    ) {
        writeLog("WARN", component, message)
    }

    @JvmStatic
    fun reportInfo(
        component: String,
        message: String,
    ) {
        writeLog("INFO", component, message)
    }

    @JvmStatic
    fun reportCrash(
        contextInfo: String,
        throwable: Throwable,
    ) {
        writeLog("FATAL CRASH", contextInfo, throwable.message ?: "null", throwable = throwable, bannerChar = '!', syncFlush = true)
    }

    private const val BLOCK_SIZE = 4096

    @JvmStatic
    @JvmOverloads
    fun getRecentLogs(maxLines: Int = 30): List<String> {
        if (maxLines <= 0) return emptyList()
        return try {
            flushSync()
            synchronized(fileLock) {
                val file = logFile
                if (file != null && file.exists() && file.length() > 0L) {
                    val lines = readTailLines(file, maxLines)
                    if (lines.isNotEmpty()) lines else listOf("No errors recorded.")
                } else {
                    listOf("No errors recorded.")
                }
            }
        } catch (e: Exception) {
            listOf("Error reading log: ${e.message}")
        }
    }

    private fun readTailLines(
        file: File,
        maxLines: Int,
    ): List<String> {
        RandomAccessFile(file, "r").use { raf ->
            val fileLength = raf.length()
            if (fileLength == 0L) {
                return emptyList()
            }

            raf.seek(fileLength - 1)
            val lastByte = raf.readByte()
            val targetNewlines = if (lastByte == '\n'.code.toByte()) maxLines + 1 else maxLines

            var newlinesFound = 0
            var targetNewlinePos = -1L
            var currentPos = fileLength
            val buffer = ByteArray(BLOCK_SIZE)

            while (currentPos > 0 && newlinesFound < targetNewlines) {
                val readSize = minOf(currentPos, BLOCK_SIZE.toLong()).toInt()
                val blockStart = currentPos - readSize
                raf.seek(blockStart)
                var bytesRead = 0
                while (bytesRead < readSize) {
                    val r = raf.read(buffer, bytesRead, readSize - bytesRead)
                    if (r <= 0) break
                    bytesRead += r
                }

                for (i in bytesRead - 1 downTo 0) {
                    if (buffer[i] == '\n'.code.toByte()) {
                        newlinesFound++
                        if (newlinesFound == targetNewlines) {
                            targetNewlinePos = blockStart + i
                            break
                        }
                    }
                }

                currentPos = blockStart
            }

            val startOffset = if (targetNewlinePos >= 0) targetNewlinePos + 1 else 0L
            val bytesToRead = (fileLength - startOffset).toInt()
            if (bytesToRead <= 0) {
                return emptyList()
            }

            val tailBytes = ByteArray(bytesToRead)
            raf.seek(startOffset)
            var readTotal = 0
            while (readTotal < bytesToRead) {
                val r = raf.read(tailBytes, readTotal, bytesToRead - readTotal)
                if (r <= 0) break
                readTotal += r
            }

            val text = String(tailBytes, 0, readTotal, Charsets.UTF_8)
            val lines = text.reader().readLines()
            return if (lines.size > maxLines) lines.takeLast(maxLines) else lines
        }
    }

    @JvmStatic
    fun flush() {
        flushSync()
    }

    @JvmStatic
    fun resetForTest() {
        synchronized(fileLock) {
            closeWriterQuietly()
            logQueue.clear()
            while (signalChannel.tryReceive().isSuccess) { }
            logFile = null
        }
    }

    private fun writeToFile(
        text: String,
        syncFlush: Boolean = false,
    ) {
        if (syncFlush) {
            flushSync(text)
        } else {
            logQueue.add(text)
            signalChannel.trySend(Unit)
        }
    }

    fun flushSync(extraText: String? = null) {
        synchronized(fileLock) {
            val file = logFile ?: return
            if (writer == null && logQueue.isEmpty() && extraText == null) {
                return
            }
            try {
                if (writer == null) {
                    writer = BufferedWriter(FileWriter(file, true))
                }
                val activeWriter = writer!!
                while (true) {
                    val item = logQueue.poll() ?: break
                    activeWriter.write(item)
                }
                if (extraText != null) {
                    activeWriter.write(extraText)
                }
                activeWriter.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write report to file in flushSync: ${e.message}")
                closeWriterQuietly()
            }
        }
    }

    private fun drainQueue() {
        synchronized(fileLock) {
            val file = logFile ?: return
            if (writer == null && logQueue.isEmpty()) {
                return
            }
            try {
                if (writer == null) {
                    writer = BufferedWriter(FileWriter(file, true))
                }
                val activeWriter = writer!!
                var writtenAny = false
                while (true) {
                    val item = logQueue.poll() ?: break
                    activeWriter.write(item)
                    writtenAny = true
                }
                if (writtenAny) {
                    activeWriter.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write report to file: ${e.message}")
                closeWriterQuietly()
            }
        }
    }

    private fun closeWriterQuietly() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
    }

    private fun getStackTraceString(throwable: Throwable): String {
        val str: String? =
            try {
                Log.getStackTraceString(throwable)
            } catch (_: Throwable) {
                null
            }
        return if (!str.isNullOrEmpty()) str else throwable.stackTraceToString()
    }
}
