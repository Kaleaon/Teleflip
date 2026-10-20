package com.teleflip.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.teleflip.app.MainActivity
import com.teleflip.app.Screen
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.ChatType
import com.teleflip.app.security.PasscodeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationInterceptorTest {
    private lateinit var context: Context

    private val testChatA =
        ChatModel(
            id = 101L,
            title = "Alice",
            lastMessage = "Hello",
            time = "10:00",
            unreadCount = 0,
            chatType = ChatType.DIRECT,
            avatarLetters = "AL",
        )

    private val testChatB =
        ChatModel(
            id = 102L,
            title = "Bob",
            lastMessage = "Hey there",
            time = "10:05",
            unreadCount = 1,
            chatType = ChatType.DIRECT,
            avatarLetters = "BO",
        )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PasscodeManager.disablePasscode(context)
    }

    @Test
    fun testNavigationWhenUnlockedExecutesImmediately() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        assertFalse(PasscodeManager.isLocked(activity))

        val openConversationMethod = MainActivity::class.java.getDeclaredMethod("openConversation", ChatModel::class.java, java.lang.Long::class.java)
        openConversationMethod.isAccessible = true
        openConversationMethod.invoke(activity, testChatA, null)

        val currentScreenField = MainActivity::class.java.getDeclaredField("currentScreen")
        currentScreenField.isAccessible = true
        assertEquals(Screen.CONVERSATION, currentScreenField.get(activity))

        val activeChatField = MainActivity::class.java.getDeclaredField("activeChat")
        activeChatField.isAccessible = true
        val activeChat = activeChatField.get(activity) as? ChatModel
        assertEquals(testChatA.id, activeChat?.id)
    }

    @Test
    fun testNavigationWhenLockedQueuesActionAndShowsPasscodeUnlockScreen() {
        PasscodeManager.setPasscode(context, "1234")
        PasscodeManager.lock()
        assertTrue(PasscodeManager.isLocked(context))

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        val openConversationMethod = MainActivity::class.java.getDeclaredMethod("openConversation", ChatModel::class.java, java.lang.Long::class.java)
        openConversationMethod.isAccessible = true
        openConversationMethod.invoke(activity, testChatA, null)

        val currentScreenField = MainActivity::class.java.getDeclaredField("currentScreen")
        currentScreenField.isAccessible = true
        assertEquals(Screen.PASSCODE_LOCK, currentScreenField.get(activity))

        val attemptUnlockMethod = MainActivity::class.java.getDeclaredMethod("attemptPasscodeUnlock")
        attemptUnlockMethod.isAccessible = true

        val passcodeEntryField = MainActivity::class.java.getDeclaredField("passcodeEntry")
        passcodeEntryField.isAccessible = true
        passcodeEntryField.set(activity, "1234")

        attemptUnlockMethod.invoke(activity)

        assertFalse(PasscodeManager.isLocked(activity))
        assertEquals(Screen.CONVERSATION, currentScreenField.get(activity))

        val activeChatField = MainActivity::class.java.getDeclaredField("activeChat")
        activeChatField.isAccessible = true
        val activeChat = activeChatField.get(activity) as? ChatModel
        assertEquals(testChatA.id, activeChat?.id)
    }

    @Test
    fun testCallCompletionWhenLockedQueuesTargetChatActionAndShowsPasscode() {
        PasscodeManager.setPasscode(context, "5555")

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        val currentScreenField = MainActivity::class.java.getDeclaredField("currentScreen")
        currentScreenField.isAccessible = true
        currentScreenField.set(activity, Screen.IN_CALL)

        val activeChatField = MainActivity::class.java.getDeclaredField("activeChat")
        activeChatField.isAccessible = true
        activeChatField.set(activity, testChatA)

        PasscodeManager.lock()
        assertTrue(PasscodeManager.isLocked(activity))

        val openConversationMethod = MainActivity::class.java.getDeclaredMethod("openConversation", ChatModel::class.java, java.lang.Long::class.java)
        openConversationMethod.isAccessible = true
        openConversationMethod.invoke(activity, testChatA, null)

        assertEquals(Screen.PASSCODE_LOCK, currentScreenField.get(activity))

        val passcodeEntryField = MainActivity::class.java.getDeclaredField("passcodeEntry")
        passcodeEntryField.isAccessible = true
        passcodeEntryField.set(activity, "5555")

        val attemptUnlockMethod = MainActivity::class.java.getDeclaredMethod("attemptPasscodeUnlock")
        attemptUnlockMethod.isAccessible = true
        attemptUnlockMethod.invoke(activity)

        assertEquals(Screen.CONVERSATION, currentScreenField.get(activity))
        val restoredChat = activeChatField.get(activity) as? ChatModel
        assertEquals(testChatA.id, restoredChat?.id)
    }

    @Test
    fun testLifecycleForegroundingWhileLockedQueuesCurrentScreenRestorationAction() {
        PasscodeManager.setPasscode(context, "9999")

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        val openConversationMethod = MainActivity::class.java.getDeclaredMethod("openConversation", ChatModel::class.java, java.lang.Long::class.java)
        openConversationMethod.isAccessible = true
        openConversationMethod.invoke(activity, testChatA, null)

        val currentScreenField = MainActivity::class.java.getDeclaredField("currentScreen")
        currentScreenField.isAccessible = true
        assertEquals(Screen.CONVERSATION, currentScreenField.get(activity))

        PasscodeManager.onAppBackgrounded()
        PasscodeManager.lock()
        assertTrue(PasscodeManager.isLocked(activity))

        controller.start()

        assertEquals(Screen.PASSCODE_LOCK, currentScreenField.get(activity))

        val passcodeEntryField = MainActivity::class.java.getDeclaredField("passcodeEntry")
        passcodeEntryField.isAccessible = true
        passcodeEntryField.set(activity, "9999")

        val attemptUnlockMethod = MainActivity::class.java.getDeclaredMethod("attemptPasscodeUnlock")
        attemptUnlockMethod.isAccessible = true
        attemptUnlockMethod.invoke(activity)

        assertEquals(Screen.CONVERSATION, currentScreenField.get(activity))
        val activeChatField = MainActivity::class.java.getDeclaredField("activeChat")
        activeChatField.isAccessible = true
        val restoredChat = activeChatField.get(activity) as? ChatModel
        assertEquals(testChatA.id, restoredChat?.id)
    }

    @Test
    fun testNewNavigationWhileLockedSupersedesExistingQueuedAction() {
        PasscodeManager.setPasscode(context, "7777")
        PasscodeManager.lock()
        assertTrue(PasscodeManager.isLocked(context))

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        val openConversationMethod = MainActivity::class.java.getDeclaredMethod("openConversation", ChatModel::class.java, java.lang.Long::class.java)
        openConversationMethod.isAccessible = true

        openConversationMethod.invoke(activity, testChatA, null)
        openConversationMethod.invoke(activity, testChatB, null)

        val passcodeEntryField = MainActivity::class.java.getDeclaredField("passcodeEntry")
        passcodeEntryField.isAccessible = true
        passcodeEntryField.set(activity, "7777")

        val attemptUnlockMethod = MainActivity::class.java.getDeclaredMethod("attemptPasscodeUnlock")
        attemptUnlockMethod.isAccessible = true
        attemptUnlockMethod.invoke(activity)

        val currentScreenField = MainActivity::class.java.getDeclaredField("currentScreen")
        currentScreenField.isAccessible = true
        assertEquals(Screen.CONVERSATION, currentScreenField.get(activity))

        val activeChatField = MainActivity::class.java.getDeclaredField("activeChat")
        activeChatField.isAccessible = true
        val restoredChat = activeChatField.get(activity) as? ChatModel
        assertEquals(testChatB.id, restoredChat?.id)
    }
}
