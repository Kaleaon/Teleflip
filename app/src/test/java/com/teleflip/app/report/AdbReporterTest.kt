package com.teleflip.app.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AdbReporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testLogFile: File

    @Before
    fun setUp() {
        AdbReporter.resetForTest()
        testLogFile = tempFolder.newFile("test_teleflip_reports_${System.currentTimeMillis()}.log")
        AdbReporter.setLogFile(testLogFile)
    }

    private fun setLogFile(file: File) {
        AdbReporter.setLogFile(file)
    }

    @Test
    fun testReportInfoFormatAndFileWriting() {
        AdbReporter.reportInfo("TestComponent", "This is an info log message")

        val logs = AdbReporter.getRecentLogs(10)
        assertEquals(1, logs.size)
        val logLine = logs[0]
        assertTrue(
            "Log line format should match date pattern and header brackets: $logLine",
            logLine.matches(Regex("""\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\] \[INFO\] \[TestComponent\] This is an info log message"""))
        )
    }

    @Test
    fun testReportErrorWithoutThrowableDoesNotAppendExtraNewlines() {
        AdbReporter.reportError("ErrorComponent", "Error occurred without exception", throwable = null)

        val rawContent = testLogFile.readText()
        val lines = rawContent.split("\n")
        // File content for single error without throwable should be "$logLine\n" ending in one newline
        assertEquals("Error without throwable should produce exactly 1 log line", 2, lines.size)
        assertEquals("", lines[1]) // lines[1] after split by \n is empty string trailing newline
        assertTrue(lines[0].contains("[ERROR] [ErrorComponent] Error occurred without exception"))
    }

    @Test
    fun testReportErrorWithThrowable() {
        val exception = RuntimeException("Test exception message")
        AdbReporter.reportError("ErrorComponent", "Error occurred with exception", throwable = exception)

        val rawContent = testLogFile.readText()
        assertTrue(rawContent.contains("[ERROR] [ErrorComponent] Error occurred with exception"))
    }

    @Test
    fun testReportPopupInfoAndError() {
        AdbReporter.reportPopup("SourcePopup", "Popup message info", isError = false)
        AdbReporter.reportPopup("SourcePopup", "Popup message error", isError = true)

        val logs = AdbReporter.getRecentLogs(10)
        assertEquals(2, logs.size)
        assertTrue(logs[0].contains("[INFO_POPUP] [SourcePopup] Popup message info"))
        assertTrue(logs[1].contains("[ERROR_POPUP] [SourcePopup] Popup message error"))
    }

    @Test
    fun testReportCrash() {
        val crashException = IllegalStateException("Fatal crash error")
        AdbReporter.reportCrash("MainThreadContext", crashException)

        val rawContent = testLogFile.readText()
        assertTrue(rawContent.contains("[FATAL CRASH] [MainThreadContext] Fatal crash error"))
    }

    @Test
    fun testGetRecentLogsLimitAndMissingFile() {
        for (i in 1..10) {
            AdbReporter.reportInfo("Component$i", "Message $i")
        }

        val recentThree = AdbReporter.getRecentLogs(3)
        assertEquals(3, recentThree.size)
        assertTrue(recentThree[0].contains("Message 8"))
        assertTrue(recentThree[1].contains("Message 9"))
        assertTrue(recentThree[2].contains("Message 10"))

        setLogFile(File(tempFolder.root, "non_existent_file.log"))
        val missingLogs = AdbReporter.getRecentLogs()
        assertEquals(1, missingLogs.size)
        assertEquals("No errors recorded.", missingLogs[0])
    }

    @Test
    fun testPrivateFormatLogLineHelper() {
        val method = AdbReporter::class.java.getDeclaredMethod(
            "formatLogLine",
            String::class.java,
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        val formatted = method.invoke(AdbReporter, "TEST_LEVEL", "TestComp", "TestMsg") as String
        assertTrue(formatted.matches(Regex("""\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\] \[TEST_LEVEL\] \[TestComp\] TestMsg""")))
    }

    @Test
    fun testAsyncLoggingAndFlush() {
        AdbReporter.reportInfo("TestComponent", "Info message 1")
        AdbReporter.reportWarning("TestComponent", "Warning message 2")
        AdbReporter.reportPopup("TestPopup", "Popup message 3", false)

        // Force flush to ensure worker queue processes items
        AdbReporter.flush()

        val logs = AdbReporter.getRecentLogs(10)
        assertEquals(3, logs.size)
        assertTrue(logs[0].contains("[INFO] [TestComponent] Info message 1"))
        assertTrue(logs[1].contains("[WARN] [TestComponent] Warning message 2"))
        assertTrue(logs[2].contains("[INFO_POPUP] [TestPopup] Popup message 3"))
    }

    @Test
    fun testReportErrorSynchronousFlush() {
        AdbReporter.reportInfo("AsyncComp", "Pending info line")
        
        // reportError must synchronously flush pending lines and the error line
        val exception = RuntimeException("Test Error")
        AdbReporter.reportError("ErrorComponent", "Fatal issue occurred", exception)

        val lines = testLogFile.readLines().filter { it.isNotBlank() }
        assertTrue(lines.any { it.contains("[INFO] [AsyncComp] Pending info line") })
        assertTrue(lines.any { it.contains("[ERROR] [ErrorComponent] Fatal issue occurred") })
        assertTrue(lines.any { it.contains("Test Error") })
    }

    @Test
    fun testReportCrashSynchronousFlush() {
        AdbReporter.reportInfo("CrashContext", "Before crash log")
        
        val crashException = IllegalStateException("Critical crash test")
        AdbReporter.reportCrash("MainThread", crashException)

        val lines = testLogFile.readLines().filter { it.isNotBlank() }
        assertTrue(lines.any { it.contains("[INFO] [CrashContext] Before crash log") })
        assertTrue(lines.any { it.contains("[FATAL CRASH] [MainThread] Critical crash test") })
        assertTrue(lines.any { it.contains("Critical crash test") })
    }

    @Test
    fun testConcurrentLoggingThreadSafety() {
        val threadCount = 10
        val logsPerThread = 50
        val latch = CountDownLatch(threadCount)

        for (i in 0 until threadCount) {
            val threadId = i
            Thread {
                try {
                    for (j in 0 until logsPerThread) {
                        if (j % 10 == 0) {
                            AdbReporter.reportError("Thread-$threadId", "Error at iteration $j")
                        } else {
                            AdbReporter.reportInfo("Thread-$threadId", "Info at iteration $j")
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        assertTrue("Threads timed out", latch.await(5, TimeUnit.SECONDS))
        
        AdbReporter.flush()

        val lines = testLogFile.readLines().filter { it.isNotBlank() }
        // Verify total lines equal expected count (each info/error produces formatted lines)
        assertTrue("Log count (${lines.size}) should be at least total entries", lines.size >= threadCount * logsPerThread)
    }

    @Test
    fun testGetRecentLogsFlushesAndLimitsOutput() {
        for (i in 1..50) {
            AdbReporter.reportInfo("Batch", "Message $i")
        }

        val recent = AdbReporter.getRecentLogs(15)
        assertEquals(15, recent.size)
        assertTrue(recent.first().contains("Message 36"))
        assertTrue(recent.last().contains("Message 50"))
    }
}
