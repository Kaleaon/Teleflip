package com.teleflip.app

import android.content.Context
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import com.teleflip.app.model.SessionModel
import com.teleflip.app.telegram.TelegramManager
import com.teleflip.app.ui.OptionsMenuDialog
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
class MainActivitySessionsTest {
    private lateinit var activity: MainActivity
    private lateinit var telegramManager: TelegramManager
    private lateinit var mockClient: Client

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        mockClient = mock(Client::class.java)

        val tgField = MainActivity::class.java.getDeclaredField("telegramManager")
        tgField.isAccessible = true
        telegramManager = tgField.get(activity) as TelegramManager
        telegramManager.setTdClientForTesting(mockClient)
    }

    @Test
    fun testActiveSessionsMenuCurrentSessionNotice() {
        val method = MainActivity::class.java.getDeclaredMethod("showActiveSessionsMenu")
        method.isAccessible = true
        method.invoke(activity)

        val functionCaptor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        val handlerCaptor = ArgumentCaptor.forClass(Client.ResultHandler::class.java)
        verify(mockClient, timeout(1000)).send(functionCaptor.capture(), handlerCaptor.capture())

        val session1 = TdApi.Session().apply {
            id = 101L
            isCurrent = true
            deviceModel = "Linux"
            platform = "Android"
            systemVersion = "14.0"
            applicationName = "Teleflip"
            applicationVersion = "1.0"
            lastActiveDate = 1700000
            ipAddress = "127.0.0.1"
            location = "US"
        }
        val session2 = TdApi.Session().apply {
            id = 102L
            isCurrent = false
            deviceModel = "MacBook Pro"
            platform = "macOS"
            systemVersion = "14.2"
            applicationName = "Teleflip Mac"
            applicationVersion = "2.0"
            lastActiveDate = 1690000
            ipAddress = "192.168.1.50"
            location = "US"
        }

        handlerCaptor.value.onResult(TdApi.Sessions().apply {
            sessions = arrayOf(session1, session2)
        })
        ShadowLooper.idleMainLooper()

        val latestDialog = ShadowAlertDialog.getLatestDialog()
        assertNotNull(latestDialog)
        assertTrue(latestDialog is OptionsMenuDialog)

        val optionsDialog = latestDialog as OptionsMenuDialog
        val optionsField = OptionsMenuDialog::class.java.getDeclaredField("options")
        optionsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val options = optionsField.get(optionsDialog) as List<com.teleflip.app.ui.MenuOption>

        assertEquals(3, options.size)

        // Click Option 2 (Current session)
        options[1].action.invoke()
        ShadowLooper.idleMainLooper()

        assertEquals("Current device session cannot be removed", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun testActiveSessionsMenuNonCurrentSessionTerminationConfirmation() {
        val method = MainActivity::class.java.getDeclaredMethod("showActiveSessionsMenu")
        method.isAccessible = true
        method.invoke(activity)

        val functionCaptor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        val handlerCaptor = ArgumentCaptor.forClass(Client.ResultHandler::class.java)
        verify(mockClient, timeout(1000)).send(functionCaptor.capture(), handlerCaptor.capture())

        val session1 = TdApi.Session().apply {
            id = 101L
            isCurrent = true
            deviceModel = "Linux"
            platform = "Android"
            systemVersion = "14.0"
            applicationName = "Teleflip"
            applicationVersion = "1.0"
            lastActiveDate = 1700000
            ipAddress = "127.0.0.1"
            location = "US"
        }
        val session2 = TdApi.Session().apply {
            id = 102L
            isCurrent = false
            deviceModel = "MacBook Pro"
            platform = "macOS"
            systemVersion = "14.2"
            applicationName = "Teleflip Mac"
            applicationVersion = "2.0"
            lastActiveDate = 1690000
            ipAddress = "192.168.1.50"
            location = "US"
        }

        handlerCaptor.value.onResult(TdApi.Sessions().apply {
            sessions = arrayOf(session1, session2)
        })
        ShadowLooper.idleMainLooper()

        val optionsDialog = ShadowAlertDialog.getLatestDialog() as OptionsMenuDialog
        val optionsField = OptionsMenuDialog::class.java.getDeclaredField("options")
        optionsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val options = optionsField.get(optionsDialog) as List<com.teleflip.app.ui.MenuOption>

        // Click Option 3 (Non-current session)
        options[2].action.invoke()
        ShadowLooper.idleMainLooper()

        val confirmDialog = ShadowAlertDialog.getLatestDialog() as AlertDialog
        assertNotNull(confirmDialog)
        assertTrue(confirmDialog.isShowing)

        // Click "Stop Session" positive button
        val stopButton = confirmDialog.getButton(AlertDialog.BUTTON_POSITIVE)
        assertNotNull(stopButton)
        assertEquals("Stop Session", stopButton.text)
        stopButton.performClick()
        ShadowLooper.idleMainLooper()

        // Verify terminateSession query sent
        val termFunctionCaptor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        val termHandlerCaptor = ArgumentCaptor.forClass(Client.ResultHandler::class.java)
        verify(mockClient, timeout(1000).times(2)).send(termFunctionCaptor.capture(), termHandlerCaptor.capture())

        val sentTermQuery = termFunctionCaptor.allValues.last()
        assertTrue(sentTermQuery is TdApi.TerminateSession)
        assertEquals(102L, (sentTermQuery as TdApi.TerminateSession).sessionId)

        // Simulate OK response
        termHandlerCaptor.allValues.last().onResult(TdApi.Ok())
        ShadowLooper.idleMainLooper()

        assertEquals("Session terminated", ShadowToast.getTextOfLatestToast())
    }
}
