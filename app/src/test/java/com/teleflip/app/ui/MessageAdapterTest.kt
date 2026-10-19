package com.teleflip.app.ui

import android.content.Context
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.teleflip.app.R
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageAdapterTest {
    private lateinit var context: Context
    private lateinit var adapter: MessageAdapter
    private lateinit var mockObserver: RecyclerView.AdapterDataObserver

    private val msg1Text = MessageModel(
        id = 101L,
        chatId = 1L,
        senderName = "Alice",
        text = "Hello world",
        time = "10:00 AM",
        isOutbound = false,
        status = MessageStatus.READ,
        mediaType = MediaType.NONE,
    )

    private val msg2Voice = MessageModel(
        id = 102L,
        chatId = 1L,
        senderName = "Bob",
        text = "",
        time = "10:01 AM",
        isOutbound = false,
        status = MessageStatus.READ,
        mediaType = MediaType.VOICE,
        durationSeconds = 15,
    )

    private val msg3Photo = MessageModel(
        id = 103L,
        chatId = 1L,
        senderName = "Alice",
        text = "Photo caption",
        time = "10:02 AM",
        isOutbound = false,
        status = MessageStatus.READ,
        mediaType = MediaType.PHOTO,
    )

    private val msg4Voice = MessageModel(
        id = 104L,
        chatId = 1L,
        senderName = "Bob",
        text = "",
        time = "10:03 AM",
        isOutbound = false,
        status = MessageStatus.READ,
        mediaType = MediaType.VOICE,
        durationSeconds = 30,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        adapter = MessageAdapter()
        mockObserver = mock(RecyclerView.AdapterDataObserver::class.java)
        adapter.registerAdapterDataObserver(mockObserver)
        adapter.submitList(listOf(msg1Text, msg2Voice, msg3Photo, msg4Voice))
        clearInvocations(mockObserver)
    }

    @Test
    fun testPayloadPlaybackStateConstant() {
        assertEquals("PAYLOAD_PLAYBACK_STATE", MessageAdapter.PAYLOAD_PLAYBACK_STATE)
    }

    @Test
    fun testSetPlayingMessageIdNotifiesOnlyAffectedPosition() {
        // Start playing msg2Voice (position 1)
        adapter.setPlayingMessageId(102L)

        verify(mockObserver).onItemRangeChanged(eq(1), eq(1), eq(MessageAdapter.PAYLOAD_PLAYBACK_STATE))
        verifyNoMoreInteractions(mockObserver)

        clearInvocations(mockObserver)

        // Switch playback from msg2Voice (position 1) to msg4Voice (position 3)
        adapter.setPlayingMessageId(104L)

        verify(mockObserver).onItemRangeChanged(eq(1), eq(1), eq(MessageAdapter.PAYLOAD_PLAYBACK_STATE))
        verify(mockObserver).onItemRangeChanged(eq(3), eq(1), eq(MessageAdapter.PAYLOAD_PLAYBACK_STATE))
        verifyNoMoreInteractions(mockObserver)

        clearInvocations(mockObserver)

        // Stop playback
        adapter.setPlayingMessageId(null)

        verify(mockObserver).onItemRangeChanged(eq(3), eq(1), eq(MessageAdapter.PAYLOAD_PLAYBACK_STATE))
        verifyNoMoreInteractions(mockObserver)
    }

    @Test
    fun testSetPlaybackSpeedNotifiesOnlyActivePlayingPosition() {
        // When no message is playing, speed change should not notify any item
        adapter.setPlaybackSpeed(1.5f)
        verifyNoInteractions(mockObserver)

        // Start playing msg2Voice (position 1)
        adapter.setPlayingMessageId(102L, 1.0f)
        clearInvocations(mockObserver)

        // Change speed while msg2Voice is active
        adapter.setPlaybackSpeed(1.5f)

        verify(mockObserver).onItemRangeChanged(eq(1), eq(1), eq(MessageAdapter.PAYLOAD_PLAYBACK_STATE))
        verifyNoMoreInteractions(mockObserver)
    }

    @Test
    fun testOnBindViewHolderPartialRebindUpdatesOnlyMediaIndicator() {
        val parent = FrameLayout(context)
        val viewHolder = adapter.onCreateViewHolder(parent, 0)

        // Initial full bind for msg2Voice (position 1)
        adapter.onBindViewHolder(viewHolder, 1)

        val tvMedia = viewHolder.itemView.findViewById<TextView>(R.id.tv_media_indicator)
        assertNotNull(tvMedia)
        assertEquals("🎤 [Voice Message] ▶ Play (15s)", tvMedia.text.toString())

        // Set playing message ID and run partial rebind via onBindViewHolder with payload
        adapter.setPlayingMessageId(102L, 1.5f)
        adapter.onBindViewHolder(viewHolder, 1, mutableListOf(MessageAdapter.PAYLOAD_PLAYBACK_STATE))

        assertEquals("🎤 [Voice Message] ⏸ Playing (1.5x)", tvMedia.text.toString())
    }

    @Test
    fun testNonVoiceMessagePartialRebindIsIgnored() {
        val parent = FrameLayout(context)
        val viewHolder = adapter.onCreateViewHolder(parent, 0)

        // Full bind for msg1Text (position 0)
        adapter.onBindViewHolder(viewHolder, 0)

        val tvText = viewHolder.itemView.findViewById<TextView>(R.id.tv_message_text)
        assertEquals("Hello world", tvText.text.toString())

        // Partial rebind on non-voice message
        adapter.onBindViewHolder(viewHolder, 0, mutableListOf(MessageAdapter.PAYLOAD_PLAYBACK_STATE))

        // Text remains unaffected
        assertEquals("Hello world", tvText.text.toString())
    }
}
