package com.teleflip.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageStatus
import com.teleflip.app.telegram.TelegramManager
import com.teleflip.app.ui.MenuOption
import com.teleflip.app.ui.OptionsMenuDialog
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
class MainActivityPinMessageTest {
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

    private fun getDialogOptions(dialog: OptionsMenuDialog): List<MenuOption> {
        val optionsField = OptionsMenuDialog::class.java.getDeclaredField("options")
        optionsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return optionsField.get(dialog) as List<MenuOption>
    }

    @Test
    fun testPinMessageOptionShowsSubmenuWithNotifyAndSilentChoices() {
        val msg =
            MessageModel(
                id = 201L,
                chatId = 1001L,
                senderName = "Alice",
                text = "Important note",
                time = "10:30",
                isOutbound = false,
                status = MessageStatus.SENT,
                mediaType = MediaType.NONE,
                isPinned = false,
            )

        val showOptionsMenuMethod =
            MainActivity::class.java.getDeclaredMethod("showMessageOptionsMenu", MessageModel::class.java)
        showOptionsMenuMethod.isAccessible = true
        showOptionsMenuMethod.invoke(activity, msg)
        ShadowLooper.idleMainLooper()

        val rootDialog = ShadowAlertDialog.getLatestDialog() as OptionsMenuDialog
        val rootOptions = getDialogOptions(rootDialog)
        val pinOption = rootOptions.find { it.title == "📌 Pin Message" }
        assertNotNull("📌 Pin Message option should exist", pinOption)

        // Click "📌 Pin Message"
        pinOption!!.action.invoke()
        ShadowLooper.idleMainLooper()

        val subDialog = ShadowAlertDialog.getLatestDialog() as OptionsMenuDialog
        val subOptions = getDialogOptions(subDialog)
        assertEquals(2, subOptions.size)

        val notifyOption = subOptions.find { it.title == "🔔 Pin and Notify Members" }
        val silentOption = subOptions.find { it.title == "🔕 Pin Silently" }
        assertNotNull("Pin and Notify option must exist", notifyOption)
        assertNotNull("Pin Silently option must exist", silentOption)

        // Select Notify option
        notifyOption!!.action.invoke()
        ShadowLooper.idleMainLooper()

        val captorNotify = ArgumentCaptor.forClass(TdApi.Function::class.java)
        verify(mockClient).send(captorNotify.capture(), Mockito.any())
        val pinNotifyRequest = captorNotify.value as TdApi.PinChatMessage
        assertEquals(1001L, pinNotifyRequest.chatId)
        assertEquals(201L, pinNotifyRequest.messageId)
        assertFalse("Notification should not be disabled", pinNotifyRequest.disableNotification)

        // Now test Silent option
        Mockito.reset(mockClient)
        silentOption!!.action.invoke()
        ShadowLooper.idleMainLooper()

        val captorSilent = ArgumentCaptor.forClass(TdApi.Function::class.java)
        verify(mockClient).send(captorSilent.capture(), Mockito.any())
        val pinSilentRequest = captorSilent.value as TdApi.PinChatMessage
        assertEquals(1001L, pinSilentRequest.chatId)
        assertEquals(201L, pinSilentRequest.messageId)
        assertTrue("Notification should be disabled", pinSilentRequest.disableNotification)
    }

    @Test
    fun testUnpinMessageOptionDirectlyUnpinsWithoutSubmenu() {
        val msg =
            MessageModel(
                id = 305L,
                chatId = 1001L,
                senderName = "Bob",
                text = "Pinned note",
                time = "10:35",
                isOutbound = false,
                status = MessageStatus.SENT,
                mediaType = MediaType.NONE,
                isPinned = true,
            )

        val showOptionsMenuMethod =
            MainActivity::class.java.getDeclaredMethod("showMessageOptionsMenu", MessageModel::class.java)
        showOptionsMenuMethod.isAccessible = true
        showOptionsMenuMethod.invoke(activity, msg)
        ShadowLooper.idleMainLooper()

        val rootDialog = ShadowAlertDialog.getLatestDialog() as OptionsMenuDialog
        val rootOptions = getDialogOptions(rootDialog)
        val unpinOption = rootOptions.find { it.title == "📌 Unpin Message" }
        assertNotNull("📌 Unpin Message option should exist", unpinOption)

        unpinOption!!.action.invoke()
        ShadowLooper.idleMainLooper()

        val captor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        verify(mockClient).send(captor.capture(), Mockito.any())
        val unpinRequest = captor.value as TdApi.UnpinChatMessage
        assertEquals(1001L, unpinRequest.chatId)
        assertEquals(305L, unpinRequest.messageId)
    }

    @Test
    fun testCapturePinOptionsDialogScreenshot() {
        val msg =
            MessageModel(
                id = 201L,
                chatId = 1001L,
                senderName = "Alice",
                text = "Important note",
                time = "10:30",
                isOutbound = false,
                status = MessageStatus.SENT,
                mediaType = MediaType.NONE,
                isPinned = false,
            )

        val showOptionsMenuMethod =
            MainActivity::class.java.getDeclaredMethod("showMessageOptionsMenu", MessageModel::class.java)
        showOptionsMenuMethod.isAccessible = true
        showOptionsMenuMethod.invoke(activity, msg)
        ShadowLooper.idleMainLooper()

        val rootDialog = ShadowAlertDialog.getLatestDialog() as OptionsMenuDialog
        val rootOptions = getDialogOptions(rootDialog)
        val pinOption = rootOptions.find { it.title == "📌 Pin Message" }
        pinOption!!.action.invoke()
        ShadowLooper.idleMainLooper()

        val subDialog = ShadowAlertDialog.getLatestDialog() as OptionsMenuDialog
        val decorView = subDialog.window?.decorView
        assertNotNull(decorView)

        val width = 240
        val height = 320
        decorView!!.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        decorView.layout(0, 0, width, height)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        decorView.draw(canvas)

        val file = File("/tmp/pin_options_dialog.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        assertTrue("Screenshot file must exist", file.exists())
        assertTrue("Screenshot file must not be empty", file.length() > 0)
    }
}
