package com.teleflip.app.ui

import android.app.Application
import android.view.KeyEvent
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teleflip.app.R
import com.teleflip.app.storage.ChatExportProgress
import com.teleflip.app.storage.ExportStage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatExportProgressDialogTest {
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.Theme_TeleFlip)
    }

    @Test
    fun chatExportProgressDialog_invokesOnCancelRequestedAndDismissesOnBackKey() {
        var cancelRequested = false
        val dialog =
            ChatExportProgressDialog(context) {
                cancelRequested = true
            }
        dialog.show()

        assertTrue(dialog.isShowing)

        val backEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        val handled = dialog.dispatchKeyEvent(backEvent)

        assertTrue(handled)
        assertTrue(cancelRequested)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun chatExportProgressDialog_invokesOnCancelRequestedAndDismissesOnSoftRightKey() {
        var cancelRequested = false
        val dialog =
            ChatExportProgressDialog(context) {
                cancelRequested = true
            }
        dialog.show()

        assertTrue(dialog.isShowing)

        val softRightEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_RIGHT)
        val handled = dialog.dispatchKeyEvent(softRightEvent)

        assertTrue(handled)
        assertTrue(cancelRequested)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun chatExportProgressDialog_invokesOnCancelRequestedAndDismissesOnCancelButtonClick() {
        var cancelRequested = false
        val dialog =
            ChatExportProgressDialog(context) {
                cancelRequested = true
            }
        dialog.show()

        assertTrue(dialog.isShowing)

        val btnCancel = dialog.findViewById<Button>(R.id.btn_cancel_progress)
        btnCancel?.performClick()

        assertTrue(cancelRequested)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun chatExportProgressDialog_cancelsCoroutineJobOnBackKey() =
        runTest {
            var exportJob: Job? = null
            val dialog =
                ChatExportProgressDialog(context) {
                    exportJob?.cancel()
                }
            dialog.show()

            exportJob =
                launch {
                    kotlinx.coroutines.delay(10000)
                }

            assertFalse(exportJob.isCancelled)

            val backEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
            dialog.dispatchKeyEvent(backEvent)

            assertTrue(exportJob.isCancelled)
            assertFalse(dialog.isShowing)
        }

    @Test
    fun chatExportProgressDialog_updatesProgressUI() {
        val dialog = ChatExportProgressDialog(context) {}
        dialog.show()

        val progress =
            ChatExportProgress(
                stage = ExportStage.FETCHING_CLOUD_HISTORY,
                messagesProcessed = 50,
                totalMessages = 50,
                mediaDownloaded = 5,
                totalMedia = 5,
                statusText = "Retrieved 50 messages...",
            )
        dialog.updateProgress(progress)

        assertTrue(dialog.isShowing)
        dialog.dismiss()
    }
}
