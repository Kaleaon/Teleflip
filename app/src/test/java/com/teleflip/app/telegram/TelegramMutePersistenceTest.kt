package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.ChatType
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class TelegramMutePersistenceTest {
    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var mockClient: Client
    private val prefStorage = mutableMapOf<String, Any>()

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockPrefs = mock(SharedPreferences::class.java)
        mockEditor = mock(SharedPreferences.Editor::class.java)
        mockClient = mock(Client::class.java)
        prefStorage.clear()

        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putInt(anyString(), anyInt())).thenAnswer {
            prefStorage[it.getArgument(0)] = it.getArgument(1)
            mockEditor
        }
        `when`(mockEditor.putString(anyString(), anyString())).thenAnswer {
            prefStorage[it.getArgument(0)] = it.getArgument(1)
            mockEditor
        }
        `when`(mockEditor.putLong(anyString(), anyLong())).thenAnswer {
            prefStorage[it.getArgument(0)] = it.getArgument(1)
            mockEditor
        }
        `when`(mockEditor.putStringSet(anyString(), any())).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            prefStorage[it.getArgument(0)] = (it.getArgument(1) as? Set<String>)?.toSet() ?: emptySet<String>()
            mockEditor
        }
        `when`(mockPrefs.getStringSet(anyString(), any())).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            (prefStorage[it.getArgument(0)] as? Set<String>) ?: (it.getArgument(1) as? Set<String>)
        }
    }

    @Test
    fun testSetChatNotificationMute_persistsMutedStateAndSendsCloudRequest() {
        val manager = TelegramManager(mockContext)
        manager.setTdClientForTesting(mockClient)

        val chat =
            ChatModel(
                id = 1001L,
                title = "Work Group",
                lastMessage = "Hello",
                time = "12:00",
                unreadCount = 0,
                chatType = ChatType.GROUP,
                avatarLetters = "WG",
                isMuted = false,
            )
        manager.setChatsForTesting(listOf(chat))
        assertFalse(manager.isChatMuted(1001L))

        // Mute for 8 hours (28800s)
        manager.setChatNotificationMute(1001L, 28800)

        assertTrue(manager.isChatMuted(1001L))
        assertTrue(manager.chats.value.first().isMuted)
        assertTrue(manager.getPersistedMutedChatIds().contains("1001"))

        val captor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        verify(mockClient, atLeastOnce()).send(captor.capture(), any())

        val setReq = captor.allValues.filterIsInstance<TdApi.SetChatNotificationSettings>().first()
        assertEquals(1001L, setReq.chatId)
        assertFalse(setReq.notificationSettings.useDefaultMuteFor)
        assertEquals(28800, setReq.notificationSettings.muteFor)
    }

    @Test
    fun testSetChatNotificationMute_unmuteClearsPersistedState() {
        val manager = TelegramManager(mockContext)
        manager.setTdClientForTesting(mockClient)

        val chat =
            ChatModel(
                id = 2002L,
                title = "Alice",
                lastMessage = "Hey",
                time = "10:00",
                unreadCount = 0,
                chatType = ChatType.DIRECT,
                avatarLetters = "AL",
                isMuted = true,
            )
        manager.setChatsForTesting(listOf(chat))
        manager.persistChatMuted(2002L, true)
        assertTrue(manager.isChatMuted(2002L))

        // Unmute
        manager.setChatNotificationMute(2002L, 0)

        assertFalse(manager.isChatMuted(2002L))
        assertFalse(manager.chats.value.first().isMuted)
        assertFalse(manager.getPersistedMutedChatIds().contains("2002"))

        val captor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        verify(mockClient, atLeastOnce()).send(captor.capture(), any())

        val setReq = captor.allValues.filterIsInstance<TdApi.SetChatNotificationSettings>().first()
        assertEquals(2002L, setReq.chatId)
        assertFalse(setReq.notificationSettings.useDefaultMuteFor)
        assertEquals(0, setReq.notificationSettings.muteFor)
    }

    @Test
    fun testUpdateChatNotificationSettings_fromAnotherTelegramApp_updatesStateAndPersists() {
        val manager = TelegramManager(mockContext)

        val chat =
            ChatModel(
                id = 3003L,
                title = "News Channel",
                lastMessage = "Breaking",
                time = "11:00",
                unreadCount = 0,
                chatType = ChatType.CHANNEL,
                avatarLetters = "NC",
                isMuted = false,
            )
        manager.setChatsForTesting(listOf(chat))
        assertFalse(manager.isChatMuted(3003L))

        // Simulate incoming update from another Telegram client (e.g. Telegram Desktop muted forever)
        val mutedSettings =
            TdApi.ChatNotificationSettings().apply {
                useDefaultMuteFor = false
                muteFor = 2147483647
            }
        manager.handleChatNotificationSettingsUpdated(3003L, mutedSettings)

        assertTrue("Chat should be muted after cloud update", manager.isChatMuted(3003L))
        assertTrue("Chat in list should reflect muted state", manager.chats.value.first().isMuted)
        assertTrue("Muted status should be persisted in SharedPreferences", manager.getPersistedMutedChatIds().contains("3003"))

        // Simulate unmuting from Telegram Web
        val unmutedSettings =
            TdApi.ChatNotificationSettings().apply {
                useDefaultMuteFor = false
                muteFor = 0
            }
        manager.handleChatNotificationSettingsUpdated(3003L, unmutedSettings)

        assertFalse("Chat should be unmuted after cloud update", manager.isChatMuted(3003L))
        assertFalse("Chat in list should reflect unmuted state", manager.chats.value.first().isMuted)
        assertFalse("Muted ID should be cleared from persistent prefs", manager.getPersistedMutedChatIds().contains("3003"))
    }

    @Test
    fun testUpdateScopeNotificationSettings_appliesToChatsWithDefaultMute() {
        val manager = TelegramManager(mockContext)

        val chat =
            ChatModel(
                id = 4004L,
                title = "Tech Channel",
                lastMessage = "Update",
                time = "15:00",
                unreadCount = 0,
                chatType = ChatType.CHANNEL,
                avatarLetters = "TC",
                isMuted = false,
            )
        manager.setChatsForTesting(listOf(chat))

        // Cache default notification settings for chat
        val defaultSettings =
            TdApi.ChatNotificationSettings().apply {
                useDefaultMuteFor = true
                muteFor = 0
            }
        manager.chatNotificationSettingsCache[4004L] = defaultSettings

        // Globally mute channel chats
        val scopeMuted =
            TdApi.ScopeNotificationSettings().apply {
                muteFor = 31536000
            }
        manager.handleScopeNotificationSettingsUpdated(TdApi.NotificationSettingsScopeChannelChats(), scopeMuted)

        assertTrue(manager.scopeChannelChatsMuted)
        assertTrue("Chat using default scope settings should become muted", manager.chats.value.first().isMuted)
    }

    @Test
    fun testIsChatMuted_resolvesFromPersistenceWhenChatNotInListYet() {
        val manager = TelegramManager(mockContext)
        manager.setChatsForTesting(emptyList())

        // Persist chatId 5005 as muted in prefs
        manager.persistChatMuted(5005L, true)

        assertTrue("isChatMuted should return true from persistent cache even if chat is not in _chats list", manager.isChatMuted(5005L))
        assertFalse(manager.isChatMuted(9999L))
    }
}
