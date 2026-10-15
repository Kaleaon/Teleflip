package com.teleflip.app.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AdbReporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testLogFile: File

    @Before
    fun setUp() {
        testLogFile = tempFolder.newFile("test_teleflip_reports.log")
        setLogFile(testLogFile)
    }

    private fun setLogFile(file: File?) {
        val field = AdbReporter::class.java.getDeclaredField("logFile")
        field.isAccessible = true
        field.set(AdbReporter, file)
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
    fun testReportWarning() {
        AdbReporter.reportWarning("WarnComponent", "Warning detected")

        val logs = AdbReporter.getRecentLogs(10)
        assertEquals(1, logs.size)
        assertTrue(
            logs[0].contains("[WARN] [WarnComponent] Warning detected")
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
}
