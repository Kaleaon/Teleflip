package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.ChatType
import com.teleflip.app.model.MessageModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.Mockito.mock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramParityFeaturesTest {
    private lateinit var telegramManager: TelegramManager

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        Mockito.`when`(context.getSharedPreferences("teleflip_prefs", Context.MODE_PRIVATE)).thenReturn(prefs)
        Mockito.`when`(prefs.edit()).thenReturn(editor)
        Mockito.`when`(editor.putStringSet(Mockito.anyString(), Mockito.any())).thenReturn(editor)
        telegramManager = TelegramManager(context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun clientWithResponse(response: TdApi.Object): Client {
        val client = mock(Client::class.java)
        Mockito.doAnswer { invocation ->
            invocation.getArgument<Client.ResultHandler>(1).onResult(response)
            null
        }.`when`(client).send(Mockito.any<TdApi.Function<*>>(), Mockito.any<Client.ResultHandler>())
        telegramManager.setTdClientForTesting(client)
        return client
    }

    @Test
    fun sendsUseTdlibReplyAndTopicWithoutSyntheticBubbles() {
        val client = mock(Client::class.java)
        telegramManager.setTdClientForTesting(client)
        telegramManager.setChatsForTesting(listOf(ChatModel(10L, "Forum", "", "", 0, ChatType.GROUP, "F", activeTopicId = 42L)))
        telegramManager.sendMessage(10L, "Reply", replyToMessageId = 501L)
        val captor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        Mockito.verify(client, Mockito.atLeastOnce()).send(captor.capture(), Mockito.any())
        val request = captor.allValues.filterIsInstance<TdApi.SendMessage>().single()
        assertEquals(501L, (request.replyTo as TdApi.InputMessageReplyToMessage).messageId)
        assertEquals(42, (request.topicId as TdApi.MessageTopicForum).forumTopicId)
        assertTrue(telegramManager.messages.value.isEmpty())
    }

    @Test
    fun everyMediaSendUsesTdlibWithoutSyntheticBubbles() {
        val client = mock(Client::class.java)
        telegramManager.setTdClientForTesting(client)
        telegramManager.sendPhoto(10L, "/photo.jpg")
        telegramManager.sendDocument(10L, "/document.pdf")
        telegramManager.sendSticker(10L, 12)
        telegramManager.sendStickerEmoji(10L, "🚀")
        telegramManager.sendSavedAnimation(10L, 13)
        telegramManager.sendVideo(10L, "/video.mp4", 5)
        telegramManager.sendAnimation(10L, "/animation.gif")
        telegramManager.sendVoiceNote(10L, "/voice.m4a", 5)
        telegramManager.sendPoll(10L, "Question", listOf("One", "Two"))
        telegramManager.sendLocation(10L, 1.0, 2.0, 3f)
        val captor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        Mockito.verify(client, Mockito.times(10)).send(captor.capture(), Mockito.any())
        assertEquals(9, captor.allValues.filterIsInstance<TdApi.SendMessage>().size)
        assertEquals(1, captor.allValues.filterIsInstance<TdApi.GetStickers>().size)
        assertTrue(telegramManager.messages.value.isEmpty())
    }

    @Test
    fun noClientDoesNotFabricateMessagesChatsMembersTopicsOrEmoji() {
        telegramManager.saveChatDraft(10L, "Keep this draft")
        telegramManager.sendMessage(10L, "Keep this draft")
        assertEquals("Keep this draft", telegramManager.getChatDraft(10L))
        assertTrue(telegramManager.messages.value.isEmpty())
        assertNotNull(telegramManager.operationError.value)
        telegramManager.resolveAndCreateChat("@example") { chat, error ->
            assertNull(chat)
            assertNotNull(error)
        }
        telegramManager.createGroupOrChannel("Group", false) { chat, error ->
            assertNull(chat)
            assertNotNull(error)
        }
        telegramManager.startSecretChat("Name", 12L) { chat, error ->
            assertNull(chat)
            assertNotNull(error)
        }
        telegramManager.loadForumTopics(10L) { assertTrue(it.isEmpty()) }
        telegramManager.fetchCustomEmojiStickers { assertTrue(it.isEmpty()) }
        assertTrue(telegramManager.getGroupMembers(10L).isEmpty())
        assertTrue(telegramManager.chats.value.isEmpty())
    }

    @Test
    fun failedRemoteEditDoesNotChangeCachedMessage() {
        clientWithResponse(TdApi.Error(400, "MESSAGE_EDIT_TIME_EXPIRED"))
        val message = MessageModel(11L, 10L, "You", "Original", "", true)
        telegramManager.setMessagesForTesting(mapOf(10L to listOf(message)))
        var success = true
        telegramManager.editMessageText(10L, 11L, "Edited") { success = it }
        assertFalse(success)
        assertEquals(listOf(message), telegramManager.messages.value[10L])
    }

    @Test
    fun failedPinAndArchiveRequestsRestoreCachedState() {
        clientWithResponse(TdApi.Error(400, "CHAT_ADMIN_REQUIRED"))
        val chat = ChatModel(10L, "Group", "", "", 0, ChatType.GROUP, "G")
        telegramManager.setChatsForTesting(listOf(chat))
        telegramManager.toggleChatPin(10L, false)
        assertEquals(chat, telegramManager.chats.value.single())
        telegramManager.toggleChatArchived(10L, false)
        assertEquals(chat, telegramManager.chats.value.single())
    }

    @Test
    fun savedMessagesErrorDoesNotCreateTelegramServiceChat() {
        clientWithResponse(TdApi.Error(500, "Unavailable"))
        val finished = CountDownLatch(1)
        telegramManager.openSavedMessages { chat, error ->
            assertNull(chat)
            assertNotNull(error)
            finished.countDown()
        }
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        assertTrue(telegramManager.chats.value.isEmpty())
    }

    @Test
    fun topicCreationUsesServerIdentity() {
        val info =
            TdApi.ForumTopicInfo().apply {
                forumTopicId = 921
                name = "Hardware"
            }
        val client = clientWithResponse(info)
        val finished = CountDownLatch(1)
        telegramManager.createForumTopic(10L, "Hardware") { topic, error ->
            assertNull(error)
            assertEquals(921L, topic?.topicId)
            finished.countDown()
        }
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        val captor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        Mockito.verify(client).send(captor.capture(), Mockito.any())
        assertTrue(captor.value is TdApi.CreateForumTopic)
    }

    @Test
    fun testSaveAndClearChatDraftTriggersTdLibSync() {
        val mockClient = mock(Client::class.java)
        telegramManager.setTdClientForTesting(mockClient)
        val chat = ChatModel(5L, "Charlie", "Hello", "09:00", 2, ChatType.DIRECT, "CH")
        telegramManager.setChatsForTesting(listOf(chat))

        telegramManager.saveChatDraft(5L, "Working on reply...")
        assertEquals("Working on reply...", telegramManager.getChatDraft(5L))

        val captor = ArgumentCaptor.forClass(TdApi.SetChatDraftMessage::class.java)
        Mockito.verify(mockClient).send(captor.capture(), Mockito.any())
        assertEquals(5L, captor.value.chatId)
        assertNotNull(captor.value.draftMessage)
        val content = captor.value.draftMessage.content as TdApi.DraftMessageContentText
        assertEquals("Working on reply...", content.text.text)

        Mockito.reset(mockClient)
        telegramManager.saveChatDraft(5L, "")
        assertEquals("", telegramManager.getChatDraft(5L))
        Mockito.verify(mockClient).send(captor.capture(), Mockito.any())
        assertEquals(5L, captor.value.chatId)
        org.junit.Assert.assertNull(captor.value.draftMessage)
    }

    @Test
    fun testUpdateChatDraftMessageSyncsCloudDraftToLocal() {
        val chat = ChatModel(5L, "Charlie", "Hello", "09:00", 2, ChatType.DIRECT, "CH")
        telegramManager.setChatsForTesting(listOf(chat))

        val content = TdApi.DraftMessageContentText(TdApi.FormattedText("Synced cloud draft", emptyArray()), null)
        val draft = TdApi.DraftMessage(null, 12345, content, 0L, null)
        val update = TdApi.UpdateChatDraftMessage(5L, draft, emptyArray())

        telegramManager.onResult(update)

        assertEquals("Synced cloud draft", telegramManager.getChatDraft(5L))
        assertEquals("Synced cloud draft", telegramManager.chats.value.first().draftText)
    }
}
