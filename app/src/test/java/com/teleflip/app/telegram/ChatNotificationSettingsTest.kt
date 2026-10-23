package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.ChatType
import com.teleflip.app.ui.ChatNotificationSettingsDialog
import com.teleflip.app.ui.CustomTimeUnit
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

class ChatNotificationSettingsTest {
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
        `when`(mockPrefs.getString(anyString(), any())).thenAnswer {
            (prefStorage[it.getArgument(0)] as? String) ?: (it.getArgument(1) as? String)
        }
        `when`(mockEditor.remove(anyString())).thenAnswer {
            prefStorage.remove(it.getArgument(0))
            mockEditor
        }
    }

    @Test
    fun testCustomDurationCalculation_minutesHoursDaysAndOverflow() {
        assertEquals(2700, ChatNotificationSettingsDialog.calculateCustomMuteDurationSeconds("45", CustomTimeUnit.MINUTES))
        assertEquals(7200, ChatNotificationSettingsDialog.calculateCustomMuteDurationSeconds("2", CustomTimeUnit.HOURS))
        assertEquals(259200, ChatNotificationSettingsDialog.calculateCustomMuteDurationSeconds("3", CustomTimeUnit.DAYS))

        // Negative or non-numeric inputs
        assertEquals(0, ChatNotificationSettingsDialog.calculateCustomMuteDurationSeconds("-10", CustomTimeUnit.MINUTES))
        assertEquals(0, ChatNotificationSettingsDialog.calculateCustomMuteDurationSeconds("", CustomTimeUnit.MINUTES))
        assertEquals(0, ChatNotificationSettingsDialog.calculateCustomMuteDurationSeconds("abc", CustomTimeUnit.HOURS))

        // Overflow integer protection
        assertEquals(Int.MAX_VALUE, ChatNotificationSettingsDialog.calculateCustomMuteDurationSeconds("999999999999", CustomTimeUnit.DAYS))
    }

    @Test
    fun testSetChatNotificationSettings_soundMutingSetsSoundIdZeroAndPersists() {
        val manager = TelegramManager(mockContext)
        manager.setTdClientForTesting(mockClient)

        val chat = ChatModel(
            id = 101L,
            title = "Busy Group",
            lastMessage = "Hi",
            time = "12:00",
            unreadCount = 0,
            chatType = ChatType.GROUP,
            avatarLetters = "BG",
            isMuted = false,
        )
        manager.setChatsForTesting(listOf(chat))

        // Mute sound while keeping muteFor = 2700s (45 minutes)
        manager.setChatNotificationSettings(
            chatId = 101L,
            muteForSeconds = 2700,
            isSoundMuted = true,
            customSoundUri = null,
        )

        assertTrue(manager.isChatMuted(101L))
        assertTrue(manager.isChatSoundMuted(101L))

        val captor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        verify(mockClient, atLeastOnce()).send(captor.capture(), any())

        val setReq = captor.allValues.filterIsInstance<TdApi.SetChatNotificationSettings>().first()
        assertEquals(101L, setReq.chatId)
        assertEquals(2700, setReq.notificationSettings.muteFor)
        assertFalse(setReq.notificationSettings.useDefaultSound)
        assertEquals(0L, setReq.notificationSettings.soundId)
    }

    @Test
    fun testSetChatNotificationSettings_customRingtoneUriPersisted() {
        val manager = TelegramManager(mockContext)
        manager.setTdClientForTesting(mockClient)

        val toneUri = "content://media/internal/audio/media/chime"
        manager.setChatNotificationSettings(
            chatId = 202L,
            muteForSeconds = 0,
            isSoundMuted = false,
            customSoundUri = toneUri,
        )

        assertFalse(manager.isChatMuted(202L))
        assertFalse(manager.isChatSoundMuted(202L))
        assertEquals(toneUri, manager.getChatSoundUri(202L))
    }

    @Test
    fun testCloudUpdate_soundMutedFromTDLib_updatesSoundMutePersistence() {
        val manager = TelegramManager(mockContext)
        val chat = ChatModel(
            id = 303L,
            title = "Work Chat",
            lastMessage = "Ping",
            time = "13:00",
            unreadCount = 0,
            chatType = ChatType.DIRECT,
            avatarLetters = "WC",
            isMuted = false,
        )
        manager.setChatsForTesting(listOf(chat))

        // TDLib sends notification settings with soundId = 0
        val mutedSoundSettings = TdApi.ChatNotificationSettings().apply {
            useDefaultMuteFor = false
            muteFor = 0
            useDefaultSound = false
            soundId = 0L
        }

        manager.handleChatNotificationSettingsUpdated(303L, mutedSoundSettings)

        assertFalse("Chat is unmuted for visual banners", manager.isChatMuted(303L))
        assertTrue("Sound is muted according to cloud settings", manager.isChatSoundMuted(303L))
    }
}
