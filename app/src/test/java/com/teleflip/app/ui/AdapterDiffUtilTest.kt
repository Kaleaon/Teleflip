package com.teleflip.app.ui

import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.ChatType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterDiffUtilTest {
    @Test
    fun testChatDiffCallback() {
        val callback = ChatListAdapter.ChatDiffCallback()

        val chat1 =
            ChatModel(
                id = 100L,
                title = "Test Chat",
                lastMessage = "Hello",
                time = "10:00",
                unreadCount = 2,
                chatType = ChatType.DIRECT,
                avatarLetters = "TC",
            )

        val chat1Same = chat1.copy()

        val chat1Modified = chat1.copy(unreadCount = 0, lastMessage = "Hello World")

        val chat2 =
            ChatModel(
                id = 200L,
                title = "Other Chat",
                lastMessage = "Hi",
                time = "10:05",
                unreadCount = 0,
                chatType = ChatType.GROUP,
                avatarLetters = "OC",
            )

        assertTrue("Same ID items should return true for areItemsTheSame", callback.areItemsTheSame(chat1, chat1Modified))
        assertFalse("Different ID items should return false for areItemsTheSame", callback.areItemsTheSame(chat1, chat2))

        assertTrue("Identical content should return true for areContentsTheSame", callback.areContentsTheSame(chat1, chat1Same))
        assertFalse("Modified content should return false for areContentsTheSame", callback.areContentsTheSame(chat1, chat1Modified))
    }

    @Test
    fun testMessageDiffCallback() {
        val callback = MessageAdapter.MessageDiffCallback()

        val msg1 =
            MessageModel(
                id = 1L,
                chatId = 100L,
                senderName = "Alice",
                text = "Hey there",
                time = "10:00",
                isOutbound = false,
                status = MessageStatus.READ,
            )

        val msg1Same = msg1.copy()

        val msg1Modified = msg1.copy(status = MessageStatus.READ, text = "Hey there (edited)")

        val msg2 =
            MessageModel(
                id = 2L,
                chatId = 100L,
                senderName = "You",
                text = "General Kenobi",
                time = "10:01",
                isOutbound = true,
                status = MessageStatus.SENT,
            )

        assertTrue("Same ID items should return true for areItemsTheSame", callback.areItemsTheSame(msg1, msg1Modified))
        assertFalse("Different ID items should return false for areItemsTheSame", callback.areItemsTheSame(msg1, msg2))

        assertTrue("Identical content should return true for areContentsTheSame", callback.areContentsTheSame(msg1, msg1Same))
        assertFalse("Modified content should return false for areContentsTheSame", callback.areContentsTheSame(msg1, msg1Modified))
    }
}
