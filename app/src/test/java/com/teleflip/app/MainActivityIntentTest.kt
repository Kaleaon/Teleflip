package com.teleflip.app

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.teleflip.app.notifications.NotificationHelper
import com.teleflip.app.security.PasscodeManager
import com.teleflip.app.telegram.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityIntentTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PasscodeManager.disablePasscode(context)
    }

    @Test
    fun testPasscodeUnlockOpensTargetChatWhenPendingTargetChatIdExists() {
        PasscodeManager.setPasscode(context, "1234")
        PasscodeManager.lock()

        val intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(NotificationHelper.EXTRA_OPEN_CHAT_ID, 8888L)
            }

        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        val activity = controller.get()
        activity.telegramManager.setAuthStateForTesting(AuthState.Ready)

        assertEquals(Screen.PASSCODE_LOCK, activity.currentScreen)
        assertEquals(8888L, activity.pendingTargetChatId)

        activity.passcodeEntry = "1234"
        activity.attemptPasscodeUnlock()

        assertEquals(Screen.CONVERSATION, activity.currentScreen)
        assertNotNull(activity.activeChat)
        assertEquals(8888L, activity.activeChat?.id)
        assertEquals(0L, activity.pendingTargetChatId)
    }

    @Test
    fun testPasscodeUnlockOpensChatListWhenPendingTargetChatIdIsZero() {
        PasscodeManager.setPasscode(context, "1234")
        PasscodeManager.lock()

        val intent = Intent(context, MainActivity::class.java)

        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        val activity = controller.get()
        activity.telegramManager.setAuthStateForTesting(AuthState.Ready)

        assertEquals(Screen.PASSCODE_LOCK, activity.currentScreen)
        assertEquals(0L, activity.pendingTargetChatId)

        activity.passcodeEntry = "1234"
        activity.attemptPasscodeUnlock()

        assertEquals(Screen.CHAT_LIST, activity.currentScreen)
        assertEquals(0L, activity.pendingTargetChatId)
    }

    @Test
    fun testNewIntentUpdatesPendingTargetChatIdWhenLocked() {
        PasscodeManager.setPasscode(context, "1234")
        PasscodeManager.lock()

        val initialIntent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(NotificationHelper.EXTRA_OPEN_CHAT_ID, 1111L)
            }

        val controller = Robolectric.buildActivity(MainActivity::class.java, initialIntent).setup()
        val activity = controller.get()
        activity.telegramManager.setAuthStateForTesting(AuthState.Ready)

        assertEquals(1111L, activity.pendingTargetChatId)

        val newIntent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(NotificationHelper.EXTRA_OPEN_CHAT_ID, 2222L)
            }

        controller.newIntent(newIntent)

        assertEquals(Screen.PASSCODE_LOCK, activity.currentScreen)
        assertEquals(2222L, activity.pendingTargetChatId)

        activity.passcodeEntry = "1234"
        activity.attemptPasscodeUnlock()

        assertEquals(Screen.CONVERSATION, activity.currentScreen)
        assertNotNull(activity.activeChat)
        assertEquals(2222L, activity.activeChat?.id)
        assertEquals(0L, activity.pendingTargetChatId)
    }

    @Test
    fun testPasscodeUnlockCreatesStubWhenChatNotInList() {
        PasscodeManager.setPasscode(context, "1234")
        PasscodeManager.lock()

        val intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(NotificationHelper.EXTRA_OPEN_CHAT_ID, 9999L)
            }

        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        val activity = controller.get()
        activity.telegramManager.setAuthStateForTesting(AuthState.Ready)

        activity.passcodeEntry = "1234"
        activity.attemptPasscodeUnlock()

        assertEquals(Screen.CONVERSATION, activity.currentScreen)
        assertNotNull(activity.activeChat)
        assertEquals(9999L, activity.activeChat?.id)
        assertEquals("Chat #9999", activity.activeChat?.title)
    }
}
