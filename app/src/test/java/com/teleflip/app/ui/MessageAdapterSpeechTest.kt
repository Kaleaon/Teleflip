package com.teleflip.app.ui

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teleflip.app.R
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageAdapterSpeechTest {
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.Theme_TeleFlip)
    }

    @Test
    fun testVoiceMessageViewHolderBindsTranscribeButtonAndTranscript() {
        var transcribeClickedMessage: MessageModel? = null
        val adapter = MessageAdapter(
            onTranscribeClick = { msg -> transcribeClickedMessage = msg }
        )

        val voiceMsgWithoutTranscript = MessageModel(
            id = 101L,
            chatId = 1L,
            senderName = "Alice",
            text = "🎤 [Voice Note]",
            time = "10:00",
            isOutbound = false,
            mediaType = MediaType.VOICE,
            durationSeconds = 8,
            transcript = "",
        )

        val parent = FrameLayout(context)
        val viewHolder = adapter.onCreateViewHolder(parent, 0)

        // Bind item directly to ViewHolder
        viewHolder.bind(voiceMsgWithoutTranscript)

        val btnTranscribe = viewHolder.itemView.findViewById<TextView>(R.id.btn_transcribe)
        val tvTranscript = viewHolder.itemView.findViewById<TextView>(R.id.tv_transcript)

        assertNotNull(btnTranscribe)
        assertNotNull(tvTranscript)

        assertEquals("Transcribe button should be VISIBLE for voice messages", View.VISIBLE, btnTranscribe.visibility)
        assertEquals("Transcript view should be GONE when transcript is empty", View.GONE, tvTranscript.visibility)

        // Test transcribe button click
        btnTranscribe.performClick()
        assertEquals(voiceMsgWithoutTranscript, transcribeClickedMessage)

        // Now test voice message WITH transcript
        val voiceMsgWithTranscript = voiceMsgWithoutTranscript.copy(
            transcript = "This is the transcribed speech text."
        )

        viewHolder.bind(voiceMsgWithTranscript)

        assertEquals("Transcribe button should remain VISIBLE", View.VISIBLE, btnTranscribe.visibility)
        assertEquals("Transcript view should be VISIBLE when transcript is present", View.VISIBLE, tvTranscript.visibility)
        assertTrue("Transcript view should display the transcript text", tvTranscript.text.contains("This is the transcribed speech text."))
    }

    @Test
    fun testNonVoiceMessageHidesTranscribeButtonAndTranscript() {
        val adapter = MessageAdapter()

        val textMsg = MessageModel(
            id = 202L,
            chatId = 1L,
            senderName = "Bob",
            text = "Hello world",
            time = "10:05",
            isOutbound = false,
            mediaType = MediaType.NONE,
        )

        val parent = FrameLayout(context)
        val viewHolder = adapter.onCreateViewHolder(parent, 0)
        viewHolder.bind(textMsg)

        val btnTranscribe = viewHolder.itemView.findViewById<TextView>(R.id.btn_transcribe)
        val tvTranscript = viewHolder.itemView.findViewById<TextView>(R.id.tv_transcript)

        assertEquals("Transcribe button should be GONE for text messages", View.GONE, btnTranscribe.visibility)
        assertEquals("Transcript view should be GONE for text messages", View.GONE, tvTranscript.visibility)
    }
}
