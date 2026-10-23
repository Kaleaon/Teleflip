package com.teleflip.app.ui

import android.app.Application
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teleflip.app.R
import com.teleflip.app.telegram.TelegramManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock

@RunWith(AndroidJUnit4::class)
class ProxySettingsDialogTest {
    private lateinit var context: Application
    private lateinit var telegramManager: TelegramManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.Theme_TeleFlip)
        telegramManager = mock(TelegramManager::class.java)
    }

    @Test
    fun testEmptyServerSubmission_showsInlineErrorAndFocusesServerField() {
        val dialog = ProxySettingsDialog(context, telegramManager)
        dialog.show()

        val btnSaveEnable = dialog.findViewById<Button>(R.id.btn_proxy_save_enable)
        val tvError = dialog.findViewById<TextView>(R.id.tv_proxy_error)
        val etServer = dialog.findViewById<EditText>(R.id.et_proxy_server)

        btnSaveEnable.performClick()

        assertEquals(View.VISIBLE, tvError.visibility)
        assertEquals("Please enter a valid server address", tvError.text.toString())
        assertTrue("etServer should have focus when server is empty", etServer.hasFocus())
    }
}
