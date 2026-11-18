package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.StickerItem
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class TelegramMediaDownloadTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var manager: TelegramManager
    private lateinit var client: Client

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
        `when`(prefs.getString(anyString(), any())).thenReturn("NEVER")
        manager = TelegramManager(context)
        client = mock(Client::class.java)
        manager.setTdClientForTesting(client)
        TelegramManager.clearCustomEmojiCache()
    }

    private fun file(
        id: Int,
        path: String = "",
        complete: Boolean = false,
    ) = TdApi.File().apply {
        this.id = id
        local =
            TdApi.LocalFile().apply {
                this.path = path
                isDownloadingCompleted = complete
                canBeDownloaded = true
            }
    }

    private fun photo(
        file: TdApi.File,
        id: Long = 1L,
    ) = TdApi.Message().apply {
        this.id = id
        chatId = 10L
        isOutgoing = true
        content =
            TdApi.MessagePhoto().apply {
                this.photo = TdApi.Photo().apply { sizes = arrayOf(TdApi.PhotoSize("x", file, 800, 600, intArrayOf())) }
                caption = TdApi.FormattedText("Photo", emptyArray())
            }
    }

    @Test
    fun replacingAttachmentClearsPreviousDownloadedImage() {
        val oldFile = folder.newFile("old-photo.jpg")
        manager.onResult(TdApi.UpdateNewMessage(photo(file(7, oldFile.absolutePath, true))))
        manager.onResult(TdApi.UpdateMessageContent(10L, 1L, photo(file(8)).content))
        val updated = manager.messages.value[10L]!!.single()
        assertEquals(8, updated.mediaFileId)
        assertNull(updated.mediaFilePath)
    }

    @Test
    fun manualDownloadWorksWithAutomaticDownloadsDisabled() {
        manager.onResult(TdApi.UpdateNewMessage(photo(file(7))))
        verifyNoInteractions(client)
        manager.downloadFilePriority(7)
        val captor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        verify(client).send(captor.capture(), any())
        assertEquals(7, (captor.value as TdApi.DownloadFile).fileId)
    }

    @Test
    fun completedUpdateRefreshesEveryMessageAndCustomEmojiUsingFile() {
        val actual = folder.newFile("sticker.webp")
        val first = MessageModel(1L, 10L, "You", "", "", true, mediaFileId = 7, mediaType = MediaType.STICKER)
        manager.setMessagesForTesting(mapOf(10L to listOf(first), 20L to listOf(first.copy(id = 2L, chatId = 20L))))
        TelegramManager.registerCustomEmojiSticker("custom_emoji_9", StickerItem(7))
        manager.onResult(TdApi.UpdateFile(file(7, actual.path, true)))
        assertTrue(manager.messages.value.values.flatten().all { it.mediaFilePath == actual.path })
        assertEquals(actual.path, TelegramManager.getCustomEmojiFilePath("custom_emoji_9"))
    }

    @Test
    fun completionBeforeMessageInsertionIsNotLost() {
        val actual = folder.newFile("photo.jpg")
        manager.onResult(TdApi.UpdateFile(file(7, actual.path, true)))
        manager.onResult(TdApi.UpdateNewMessage(photo(file(7))))
        assertEquals(actual.path, manager.messages.value[10L]?.single()?.mediaFilePath)
    }

    @Test
    fun downloadResponseUpdatesMessageWithoutRequiringAnotherUpdateFile() {
        val actual = folder.newFile("photo.jpg")
        manager.onResult(TdApi.UpdateNewMessage(photo(file(7))))
        doAnswer { invocation ->
            invocation.getArgument<Client.ResultHandler>(1).onResult(file(7, actual.path, true))
            null
        }.`when`(client).send(any<TdApi.Function<*>>(), any<Client.ResultHandler>())
        manager.downloadFilePriority(7)
        assertEquals(actual.path, manager.messages.value[10L]?.single()?.mediaFilePath)
    }

    @Test
    fun downloadFailureReleasesWaiterImmediately() =
        runTest {
            doAnswer { invocation ->
                val result = if (invocation.getArgument<TdApi.Function<*>>(0) is TdApi.GetFile) file(7) else TdApi.Error(400, "FILE_REFERENCE_EXPIRED")
                invocation.getArgument<Client.ResultHandler>(1).onResult(result)
                null
            }.`when`(client).send(any<TdApi.Function<*>>(), any<Client.ResultHandler>())
            assertNull(manager.downloadFileSuspending(7))
            assertTrue(manager.fileDownloadWaiters.isEmpty())
            assertTrue(manager.operationError.value!!.contains("FILE_REFERENCE_EXPIRED"))
        }

    @Test
    fun evictedOrMissingFilesDoNotKeepStalePaths() {
        val missing = folder.root.resolve("deleted.jpg")
        manager.onResult(TdApi.UpdateNewMessage(photo(file(7, missing.path, true))))
        assertNull(manager.messages.value[10L]?.single()?.mediaFilePath)
        val actual = folder.newFile("existing.jpg")
        manager.onResult(TdApi.UpdateFile(file(7, actual.path, true)))
        actual.delete()
        manager.onResult(TdApi.UpdateFile(file(7, actual.path, true)))
        assertNull(manager.messages.value[10L]?.single()?.mediaFilePath)
    }

    @Test
    fun largestPhotoSizeIsSelectedEvenWhenSizesAreNotSorted() {
        val message = photo(file(7))
        (message.content as TdApi.MessagePhoto).photo.sizes =
            arrayOf(
                TdApi.PhotoSize("x", file(7), 800, 600, intArrayOf()),
                TdApi.PhotoSize("s", file(8), 90, 90, intArrayOf()),
            )
        manager.onResult(TdApi.UpdateNewMessage(message))
        assertEquals(7, manager.messages.value[10L]?.single()?.mediaFileId)
    }

    @Test
    fun audioAttachmentsKeepPlaybackMetadataAndDownloadIdentity() {
        val message =
            TdApi.Message().apply {
                id = 9L
                chatId = 10L
                isOutgoing = true
                content =
                    TdApi.MessageAudio().apply {
                        audio =
                            TdApi.Audio().apply {
                                audio = file(17)
                                duration = 45
                                mimeType = "audio/mpeg"
                            }
                        caption = TdApi.FormattedText("Music", emptyArray())
                    }
            }
        manager.onResult(TdApi.UpdateNewMessage(message))
        val model = manager.messages.value[10L]!!.single()
        assertEquals(MediaType.AUDIO, model.mediaType)
        assertEquals(45, model.durationSeconds)
        assertEquals(17, model.mediaFileId)
        assertEquals("audio/mpeg", model.mediaMimeType)
    }
}
