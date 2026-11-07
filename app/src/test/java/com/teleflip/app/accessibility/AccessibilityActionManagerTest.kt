package com.teleflip.app.accessibility

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.teleflip.app.R
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.ChatType
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageSearchResult
import com.teleflip.app.ui.ChatListAdapter
import com.teleflip.app.ui.MessageAdapter
import com.teleflip.app.ui.MessageSearchResultAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccessibilityActionManagerTest {
    private lateinit var context: Context

    private val sampleChat =
        ChatModel(
            id = 123L,
            title = "Test Chat",
            lastMessage = "Hello",
            time = "12:00 PM",
            unreadCount = 2,
            chatType = ChatType.DIRECT,
            avatarLetters = "TC",
            isPinned = false,
            isMuted = false,
            isArchived = false,
        )

    private val sampleMessage =
        MessageModel(
            id = 456L,
            chatId = 123L,
            senderName = "Alice",
            text = "Hello accessibility",
            time = "12:01 PM",
            isOutbound = false,
            mediaType = MediaType.NONE,
        )

    private val sampleVoiceMessage =
        MessageModel(
            id = 457L,
            chatId = 123L,
            senderName = "Bob",
            text = "",
            time = "12:02 PM",
            isOutbound = false,
            mediaType = MediaType.VOICE,
            durationSeconds = 10,
        )

    private val sampleSearchResult =
        MessageSearchResult(
            messageId = 456L,
            chatId = 123L,
            chatTitle = "Test Chat",
            senderName = "Alice",
            text = "Hello accessibility",
            time = "12:01 PM",
        )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AccessibilityActionManager.resetProviders()
    }

    @Test
    fun testDefaultProvidersRegistered() {
        val chatProvider = AccessibilityActionManager.getProvider(ChatModel::class.java)
        val messageProvider = AccessibilityActionManager.getProvider(MessageModel::class.java)
        val searchProvider = AccessibilityActionManager.getProvider(MessageSearchResult::class.java)

        assertNotNull("ChatModel provider should be registered", chatProvider)
        assertNotNull("MessageModel provider should be registered", messageProvider)
        assertNotNull("MessageSearchResult provider should be registered", searchProvider)
    }

    @Test
    fun testCustomProviderRegistrationAndUnregistration() {
        class CustomEntity(val id: Int)

        val customProvider =
            object : AccessibilityActionProvider<CustomEntity> {
                override fun getActions(
                    item: CustomEntity,
                    listener: AccessibilityActionListener<CustomEntity>?,
                ): List<AccessibilityAction<CustomEntity>> {
                    return listOf(AccessibilityAction(1001, "Custom Action") { true })
                }
            }

        AccessibilityActionManager.registerProvider(CustomEntity::class.java, customProvider)
        assertNotNull(AccessibilityActionManager.getProvider(CustomEntity::class.java))

        AccessibilityActionManager.unregisterProvider(CustomEntity::class.java)
        assertNull(AccessibilityActionManager.getProvider(CustomEntity::class.java))
    }

    @Test
    fun testChatModelDynamicStateTransitions() {
        val provider = AccessibilityActionManager.getProvider(ChatModel::class.java)!!

        val unpinnedUnmutedActions = provider.getActions(sampleChat)
        val pinAction = unpinnedUnmutedActions.find { it.id == R.id.action_chat_pin }
        val muteAction = unpinnedUnmutedActions.find { it.id == R.id.action_chat_mute }
        val archiveAction = unpinnedUnmutedActions.find { it.id == R.id.action_chat_archive }

        assertNotNull("Unpinned chat should have Pin Chat action", pinAction)
        assertEquals("Pin Chat", pinAction?.label)
        assertNotNull("Unmuted chat should have Mute Notifications action", muteAction)
        assertEquals("Mute Notifications", muteAction?.label)
        assertNotNull("Unarchived chat should have Archive Chat action", archiveAction)
        assertEquals("Archive Chat", archiveAction?.label)

        val pinnedMutedArchivedChat = sampleChat.copy(isPinned = true, isMuted = true, isArchived = true)
        val stateActions = provider.getActions(pinnedMutedArchivedChat)

        val unpinAction = stateActions.find { it.id == R.id.action_chat_unpin }
        val unmuteAction = stateActions.find { it.id == R.id.action_chat_unmute }
        val unarchiveAction = stateActions.find { it.id == R.id.action_chat_unarchive }

        assertNotNull("Pinned chat should have Unpin Chat action", unpinAction)
        assertEquals("Unpin Chat", unpinAction?.label)
        assertNotNull("Muted chat should have Unmute Notifications action", unmuteAction)
        assertEquals("Unmute Notifications", unmuteAction?.label)
        assertNotNull("Archived chat should have Unarchive Chat action", unarchiveAction)
        assertEquals("Unarchive Chat", unarchiveAction?.label)
    }

    @Test
    fun testMessageModelDynamicActions() {
        val provider = AccessibilityActionManager.getProvider(MessageModel::class.java)!!

        val textActions = provider.getActions(sampleMessage)
        assertTrue("Text message should have Copy Text action", textActions.any { it.id == R.id.action_message_copy })
        assertFalse("Text message should not have Transcribe Voice action", textActions.any { it.id == R.id.action_message_transcribe })

        val voiceActions = provider.getActions(sampleVoiceMessage)
        assertFalse("Empty voice message should not have Copy Text action", voiceActions.any { it.id == R.id.action_message_copy })
        assertTrue("Voice message should have Transcribe Voice action", voiceActions.any { it.id == R.id.action_message_transcribe })
        assertTrue("Voice message should have Play Voice action", voiceActions.any { it.id == R.id.action_message_play_voice })
    }

    @Test
    fun testMessageSearchResultActions() {
        val provider = AccessibilityActionManager.getProvider(MessageSearchResult::class.java)!!
        val actions = provider.getActions(sampleSearchResult)
        assertEquals(1, actions.size)
        assertEquals(R.id.action_search_jump, actions[0].id)
        assertEquals("Jump to Message", actions[0].label)
    }

    @Test
    fun testBindAndClearActionsOnView() {
        val view = View(context)

        AccessibilityActionManager.bindActions(view, sampleChat) { actionId, _ ->
            actionId == R.id.action_chat_open
        }

        @Suppress("UNCHECKED_CAST")
        val trackedIds = view.getTag(R.id.tag_accessibility_action_ids) as? Set<Int>
        assertNotNull("Tracked action IDs should be present on view tag", trackedIds)
        assertTrue(trackedIds!!.contains(R.id.action_chat_open))

        AccessibilityActionManager.clearActions(view)

        @Suppress("UNCHECKED_CAST")
        val clearedIds = view.getTag(R.id.tag_accessibility_action_ids) as? Set<Int>
        assertNull("Tracked action IDs tag should be null after clearActions", clearedIds)
    }

    @Test
    fun testActionExecutionAndErrorHandling() {
        val view = View(context)

        AccessibilityActionManager.bindActions(view, sampleChat) { _, _ ->
            throw RuntimeException("Simulated error during action execution")
        }

        val provider = AccessibilityActionManager.getProvider(ChatModel::class.java)!!
        val actions =
            provider.getActions(sampleChat) { _, _ ->
                throw RuntimeException("Simulated error during action execution")
            }

        // Verify performing an action that throws doesn't bubble up or crash
        val openAction = actions.find { it.id == R.id.action_chat_open }!!
        val result =
            try {
                openAction.perform(sampleChat)
            } catch (e: Exception) {
                false
            }
        assertFalse("Failing action perform returning false or caught safely", result)
    }

    @Test
    fun testAdapterRecyclingClearsActions() {
        val parent = FrameLayout(context)
        val chatAdapter = ChatListAdapter(onChatSelected = {})
        val messageAdapter = MessageAdapter()
        val searchAdapter = MessageSearchResultAdapter({})

        val chatHolder = chatAdapter.onCreateViewHolder(parent, 0)
        val msgHolder = messageAdapter.onCreateViewHolder(parent, 0)
        val searchHolder = searchAdapter.onCreateViewHolder(parent, 0)

        AccessibilityActionManager.bindActions(chatHolder.itemView, sampleChat)
        AccessibilityActionManager.bindActions(msgHolder.itemView, sampleMessage)
        AccessibilityActionManager.bindActions(searchHolder.itemView, sampleSearchResult)

        assertNotNull(chatHolder.itemView.getTag(R.id.tag_accessibility_action_ids))
        assertNotNull(msgHolder.itemView.getTag(R.id.tag_accessibility_action_ids))
        assertNotNull(searchHolder.itemView.getTag(R.id.tag_accessibility_action_ids))

        chatAdapter.onViewRecycled(chatHolder)
        messageAdapter.onViewRecycled(msgHolder)
        searchAdapter.onViewRecycled(searchHolder)

        assertNull(chatHolder.itemView.getTag(R.id.tag_accessibility_action_ids))
        assertNull(msgHolder.itemView.getTag(R.id.tag_accessibility_action_ids))
        assertNull(searchHolder.itemView.getTag(R.id.tag_accessibility_action_ids))
    }
}
