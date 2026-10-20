package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class TelegramPaidMediaTest {
    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockPrefs = mock(SharedPreferences::class.java)
        mockEditor = mock(SharedPreferences.Editor::class.java)

        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putInt(anyString(), anyInt())).thenReturn(mockEditor)
        `when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        `when`(mockEditor.putLong(anyString(), anyLong())).thenReturn(mockEditor)
    }

    @Test
    fun testPaidMediaModelCreationAndLockState() {
        val lockedMsg =
            MessageModel(
                id = 100L,
                chatId = 1000L,
                senderName = "Creator",
                text = "⭐ 5 Stars • 🔒 Locked • Exclusive Photo",
                time = "12:00",
                isOutbound = false,
                mediaType = MediaType.PAID_MEDIA,
                mediaCaption = "Exclusive Photo",
                starCount = 5L,
                isPaidMediaLocked = true,
            )

        assertEquals(MediaType.PAID_MEDIA, lockedMsg.mediaType)
        assertEquals(5L, lockedMsg.starCount)
        assertTrue(lockedMsg.isPaidMediaLocked)

        val unlockedMsg = lockedMsg.copy(isPaidMediaLocked = false)
        assertFalse(unlockedMsg.isPaidMediaLocked)
        assertEquals(5L, unlockedMsg.starCount)
    }

    @Test
    fun testTdApiMessagePaidMediaParsing() {
        val manager = TelegramManager(mockContext)

        val preview = TdApi.PaidMediaPreview(100, 100, 0, null)
        val paidContentLocked =
            TdApi.MessagePaidMedia(
                5L,
                arrayOf(preview),
                TdApi.FormattedText("Locked Photo", emptyArray()),
                false,
            )

        val msgLocked =
            TdApi.Message().apply {
                id = 200L
                chatId = 2000L
                date = 1700000000
                isOutgoing = false
                content = paidContentLocked
            }

        manager.handleMessagesListForTesting(arrayOf(msgLocked))
        val messages = manager.messages.value[2000L]
        assertTrue("Messages list for chat should not be empty", !messages.isNullOrEmpty())
        val model = messages!!.first()

        assertEquals(MediaType.PAID_MEDIA, model.mediaType)
        assertEquals(5L, model.starCount)
        assertTrue(model.isPaidMediaLocked)
        assertEquals("Locked Photo", model.mediaCaption)
    }

    @Test
    fun testTdApiUnlockedPaidMediaParsing() {
        val manager = TelegramManager(mockContext)

        val localFile =
            TdApi.LocalFile().apply {
                path = "/path/photo.jpg"
                isDownloadingCompleted = true
            }
        val fileObj =
            TdApi.File().apply {
                id = 1
                local = localFile
            }
        val photo =
            TdApi.Photo().apply {
                sizes = arrayOf(TdApi.PhotoSize("m", fileObj, 100, 100, intArrayOf()))
            }
        val paidPhoto = TdApi.PaidMediaPhoto(photo, null)
        val paidContentUnlocked =
            TdApi.MessagePaidMedia(
                10L,
                arrayOf(paidPhoto),
                TdApi.FormattedText("Unlocked Photo", emptyArray()),
                false,
            )

        val msgUnlocked =
            TdApi.Message().apply {
                id = 201L
                chatId = 2000L
                date = 1700000000
                isOutgoing = false
                content = paidContentUnlocked
            }

        manager.handleMessagesListForTesting(arrayOf(msgUnlocked))
        val messages = manager.messages.value[2000L]
        assertTrue("Messages list for chat should not be empty", !messages.isNullOrEmpty())
        val model = messages!!.first()

        assertEquals(MediaType.PAID_MEDIA, model.mediaType)
        assertEquals(10L, model.starCount)
        assertFalse(model.isPaidMediaLocked)
        assertEquals("Unlocked Photo", model.mediaCaption)
        assertEquals("/path/photo.jpg", model.mediaFilePath)
    }

    @Test
    fun testUnlockPaidMediaCallWhenClientNull() {
        val manager = TelegramManager(mockContext)

        var resultSuccess: Boolean? = null
        manager.unlockPaidMedia(100L, 200L) { success ->
            resultSuccess = success
        }

        // When tdClient is null/uninitialized, callback returns false safely
        assertEquals(false, resultSuccess)
    }
}
