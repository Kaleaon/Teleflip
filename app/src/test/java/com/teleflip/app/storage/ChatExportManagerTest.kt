package com.teleflip.app.storage

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import com.teleflip.app.model.MessageStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

class ChatExportManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var internalStorageDir: File

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockPrefs = mock(SharedPreferences::class.java)
        internalStorageDir = tempFolder.newFolder("InternalTeleFlip")

        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockContext.getExternalFilesDir(null)).thenReturn(internalStorageDir)
        `when`(mockContext.filesDir).thenReturn(internalStorageDir)
        `when`(mockPrefs.getString(anyString(), anyString())).thenReturn("internal")
    }

    @Test
    fun testSanitizeFilename() {
        assertEquals("My_Chat_History", ChatExportManager.sanitizeFilename("My:Chat/History?"))
        assertEquals("Family_Group_1", ChatExportManager.sanitizeFilename("Family*Group<1>"))
        assertEquals("Chat", ChatExportManager.sanitizeFilename("   "))
    }

    @Test
    fun testExportChat_createsHtmlTxtAndJsonFiles() =
        runBlocking {
            val sampleImage = tempFolder.newFile("sample_img.jpg")
            sampleImage.writeText("fake image bytes")

            val messages =
                listOf(
                    MessageModel(
                        id = 101L,
                        chatId = 1L,
                        senderName = "Alice",
                        text = "Hello there!",
                        time = "10:30",
                        isOutbound = false,
                        status = MessageStatus.READ,
                        mediaType = MediaType.NONE,
                    ),
                    MessageModel(
                        id = 102L,
                        chatId = 1L,
                        senderName = "You",
                        text = "Look at this snapshot",
                        time = "10:31",
                        isOutbound = true,
                        status = MessageStatus.READ,
                        mediaType = MediaType.PHOTO,
                        mediaFilePath = sampleImage.absolutePath,
                        mediaFileId = 999,
                    ),
                )

            val options =
                ChatExportOptions(
                    includeText = true,
                    includePhotos = true,
                    includeGifs = false,
                    includeVideos = false,
                    includeVoice = false,
                    includeDocuments = false,
                    targetStorageType = StorageLocationType.INTERNAL,
                )

            val result =
                ChatExportManager.exportChat(
                    context = mockContext,
                    chatId = 1L,
                    chatTitle = "Alice & Bob",
                    messages = messages,
                    options = options,
                )

            assertTrue(result.isSuccess)
            val exportDir = result.getOrNull()
            assertNotNull(exportDir)
            assertTrue(exportDir!!.exists() && exportDir.isDirectory)

            // Verify HTML file
            val htmlFile = File(exportDir, "messages.html")
            assertTrue("messages.html should exist", htmlFile.exists())
            val htmlContent = htmlFile.readText()
            assertTrue(htmlContent.contains("Alice &amp; Bob"))
            assertTrue(htmlContent.contains("Hello there!"))
            assertTrue(htmlContent.contains("Look at this snapshot"))
            assertTrue(htmlContent.contains("images/"))

            // Verify TXT file
            val txtFile = File(exportDir, "messages.txt")
            assertTrue("messages.txt should exist", txtFile.exists())
            val txtContent = txtFile.readText()
            assertTrue(txtContent.contains("Alice & Bob"))
            assertTrue(txtContent.contains("Alice:"))
            assertTrue(txtContent.contains("Hello there!"))

            // Verify JSON file
            val jsonFile = File(exportDir, "messages.json")
            assertTrue("messages.json should exist", jsonFile.exists())
            val jsonText = jsonFile.readText()
            assertTrue(jsonText.contains("\"chatId\": 1"))
            assertTrue(jsonText.contains("\"title\": \"Alice & Bob\""))
            assertTrue(jsonText.contains("\"messageCount\": 2"))
            assertTrue(jsonText.contains("Hello there!"))
            assertTrue(jsonText.contains("Look at this snapshot"))

            // Verify image copied to images/
            val imagesDir = File(exportDir, "images")
            assertTrue("images/ dir should exist", imagesDir.exists())
            val imageFiles = imagesDir.listFiles()
            assertNotNull(imageFiles)
            assertEquals(1, imageFiles!!.size)
            assertTrue(imageFiles.first().name.contains("msg_102"))
        }

    @Test
    fun testExportChat_selectiveMediaInclusion() =
        runBlocking {
            val sampleImage = tempFolder.newFile("photo.jpg").apply { writeText("photo") }
            val sampleVideo = tempFolder.newFile("clip.mp4").apply { writeText("video") }

            val messages =
                listOf(
                    MessageModel(
                        id = 201L,
                        chatId = 2L,
                        senderName = "Charlie",
                        text = "Photo sent",
                        time = "11:00",
                        isOutbound = false,
                        status = MessageStatus.READ,
                        mediaType = MediaType.PHOTO,
                        mediaFilePath = sampleImage.absolutePath,
                    ),
                    MessageModel(
                        id = 202L,
                        chatId = 2L,
                        senderName = "Charlie",
                        text = "Video sent",
                        time = "11:01",
                        isOutbound = false,
                        status = MessageStatus.READ,
                        mediaType = MediaType.VIDEO,
                        mediaFilePath = sampleVideo.absolutePath,
                    ),
                )

            // Include ONLY photos, exclude videos
            val options =
                ChatExportOptions(
                    includeText = true,
                    includePhotos = true,
                    includeVideos = false,
                    includeGifs = false,
                    includeVoice = false,
                    includeDocuments = false,
                    targetStorageType = StorageLocationType.INTERNAL,
                )

            val result =
                ChatExportManager.exportChat(
                    context = mockContext,
                    chatId = 2L,
                    chatTitle = "Media Test",
                    messages = messages,
                    options = options,
                )

            assertTrue(result.isSuccess)
            val exportDir = result.getOrNull()!!

            val imagesDir = File(exportDir, "images")
            val videosDir = File(exportDir, "videos")

            assertTrue("images dir should exist", imagesDir.exists())
            assertEquals(1, imagesDir.listFiles()?.size ?: 0)
            assertFalse("videos dir should NOT exist when videos excluded", videosDir.exists())
        }

    @Test
    fun testExportChat_withSuspendedFileDownloader() =
        runBlocking {
            val downloadedFile = tempFolder.newFile("downloaded_photo.jpg").apply { writeText("streamed") }

            val messages =
                listOf(
                    MessageModel(
                        id = 301L,
                        chatId = 3L,
                        senderName = "Dan",
                        text = "Cloud photo",
                        time = "12:00",
                        isOutbound = false,
                        status = MessageStatus.READ,
                        mediaType = MediaType.PHOTO,
                        mediaFilePath = null,
                        mediaFileId = 8888,
                    ),
                )

            val options =
                ChatExportOptions(
                    includeText = true,
                    includePhotos = true,
                    targetStorageType = StorageLocationType.INTERNAL,
                )

            var downloaderCalled = false
            val result =
                ChatExportManager.exportChat(
                    context = mockContext,
                    chatId = 3L,
                    chatTitle = "Cloud Sync Test",
                    messages = messages,
                    options = options,
                    fileDownloader = { fileId ->
                        if (fileId == 8888) {
                            downloaderCalled = true
                            downloadedFile.absolutePath
                        } else {
                            null
                        }
                    },
                )

            assertTrue(result.isSuccess)
            assertTrue("File downloader should have been triggered for cloud media", downloaderCalled)
            val exportDir = result.getOrNull()!!
            val imagesDir = File(exportDir, "images")
            assertEquals(1, imagesDir.listFiles()?.size ?: 0)
        }

    @Test
    fun testExportChat_largeStreamingExportEquivalence() =
        runBlocking {
            val messages =
                (1..500).map { i ->
                    MessageModel(
                        id = i.toLong(),
                        chatId = 100L,
                        senderName = "User \"$i\"",
                        text = "Line $i\nWith \"quotes\" & special chars \\ path/test",
                        time = "12:${i % 60}",
                        isOutbound = i % 2 == 0,
                        status = MessageStatus.READ,
                        mediaType = MediaType.NONE,
                    )
                }

            val options =
                ChatExportOptions(
                    includeText = true,
                    includePhotos = false,
                    targetStorageType = StorageLocationType.INTERNAL,
                )

            val result =
                ChatExportManager.exportChat(
                    context = mockContext,
                    chatId = 100L,
                    chatTitle = "Streaming \"Test\" Chat",
                    messages = messages,
                    options = options,
                )

            assertTrue(result.isSuccess)
            val exportDir = result.getOrNull()!!
            val jsonFile = File(exportDir, "messages.json")
            assertTrue("messages.json should exist", jsonFile.exists())

            val lines = jsonFile.readLines()
            assertTrue(lines.size > 500 * 8)
            val fullContent = jsonFile.readText()
            assertTrue(fullContent.contains("\"chatId\": 100"))
            assertTrue(fullContent.contains("\"title\": \"Streaming \\\"Test\\\" Chat\""))
            assertTrue(fullContent.contains("\"messageCount\": 500"))
            assertTrue(fullContent.contains("User \\\"1\\\""))
            assertTrue(fullContent.contains("Line 1\\nWith \\\"quotes\\\" & special chars \\\\ path/test"))
        }
}
