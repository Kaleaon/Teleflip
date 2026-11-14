package com.teleflip.app.ui

import android.widget.EditText
import android.widget.TextView
import com.teleflip.app.MainActivity
import com.teleflip.app.R
import com.teleflip.app.model.ChatModel
import com.teleflip.app.model.ChatType
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityCaptionEditTest {
    private lateinit var activity: MainActivity

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
    }

    @Test
    fun testIsCaptionableMediaTypes() {
        val photoMsg = MessageModel(1L, 10L, "You", "", "10:00", true, MessageStatus.SENT, MediaType.PHOTO)
        val videoMsg = MessageModel(2L, 10L, "You", "", "10:00", true, MessageStatus.SENT, MediaType.VIDEO)
        val docMsg = MessageModel(3L, 10L, "You", "", "10:00", true, MessageStatus.SENT, MediaType.DOCUMENT)
        val animMsg = MessageModel(4L, 10L, "You", "", "10:00", true, MessageStatus.SENT, MediaType.ANIMATION)
        val audioMsg = MessageModel(5L, 10L, "You", "", "10:00", true, MessageStatus.SENT, MediaType.AUDIO)

        val stickerMsg = MessageModel(6L, 10L, "You", "", "10:00", true, MessageStatus.SENT, MediaType.STICKER)
        val voiceMsg = MessageModel(7L, 10L, "You", "", "10:00", true, MessageStatus.SENT, MediaType.VOICE)
        val locMsg = MessageModel(8L, 10L, "You", "", "10:00", true, MessageStatus.SENT, MediaType.LOCATION)
        val pollMsg = MessageModel(9L, 10L, "You", "", "10:00", true, MessageStatus.SENT, MediaType.POLL)
        val textMsg = MessageModel(10L, 10L, "You", "Hi", "10:00", true, MessageStatus.SENT, MediaType.NONE)

        val captionableTypes = listOf(photoMsg, videoMsg, docMsg, animMsg, audioMsg)
        val nonCaptionableTypes = listOf(stickerMsg, voiceMsg, locMsg, pollMsg, textMsg)

        captionableTypes.forEach { msg ->
            val isCaptionable =
                msg.mediaType in
                    listOf(
                        MediaType.PHOTO, MediaType.VIDEO, MediaType.DOCUMENT, MediaType.ANIMATION, MediaType.AUDIO,
                    )
            assertTrue("Message of type ${msg.mediaType} should be captionable", isCaptionable)
        }

        nonCaptionableTypes.forEach { msg ->
            assertFalse(
                "Message of type ${msg.mediaType} should not be captionable",
                msg.mediaType in listOf(MediaType.PHOTO, MediaType.VIDEO, MediaType.DOCUMENT, MediaType.ANIMATION, MediaType.AUDIO),
            )
        }
    }

    @Test
    fun testStartEditingMessageMediaCaptionPrefill() {
        val chat =
            ChatModel(
                id = 1L,
                title = "Test Chat",
                lastMessage = "Hi",
                time = "12:00",
                unreadCount = 0,
                chatType = ChatType.DIRECT,
                avatarLetters = "T",
            )
        activity.openConversation(chat)

        val input = activity.findViewById<EditText>(R.id.et_message_input)
        val preview = activity.findViewById<TextView>(R.id.tv_reply_edit_preview)

        val photoMsg =
            MessageModel(
                id = 101L,
                chatId = 1L,
                senderName = "You",
                text = "📷 [Photo] Initial photo caption",
                time = "12:00",
                isOutbound = true,
                status = MessageStatus.SENT,
                mediaType = MediaType.PHOTO,
                mediaCaption = "Initial photo caption",
            )

        activity.startEditingMessage(photoMsg)

        assertEquals("Initial photo caption", input.text.toString())
        assertTrue(preview.text.toString().contains("Editing Caption: Initial photo caption"))
    }

    @Test
    fun testStartEditingTextMessagePrefillsText() {
        val chat =
            ChatModel(
                id = 1L,
                title = "Test Chat",
                lastMessage = "Hi",
                time = "12:00",
                unreadCount = 0,
                chatType = ChatType.DIRECT,
                avatarLetters = "T",
            )
        activity.openConversation(chat)

        val input = activity.findViewById<EditText>(R.id.et_message_input)
        val preview = activity.findViewById<TextView>(R.id.tv_reply_edit_preview)

        val textMsg =
            MessageModel(
                id = 102L,
                chatId = 1L,
                senderName = "You",
                text = "Plain text message",
                time = "12:01",
                isOutbound = true,
                status = MessageStatus.SENT,
                mediaType = MediaType.NONE,
            )

        activity.startEditingMessage(textMsg)

        assertEquals("Plain text message", input.text.toString())
        assertTrue(preview.text.toString().contains("Editing: Plain text message"))
    }
}
