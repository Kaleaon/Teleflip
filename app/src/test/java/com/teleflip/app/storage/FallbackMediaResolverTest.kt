package com.teleflip.app.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class FallbackMediaResolverTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        StorageManager.invalidateCache()
        FileTransferLocks.clear()
        FallbackMediaResolver.applicationContext = context
    }

    @Test
    fun testResolvePathWithNullOrBlank() {
        assertNull(FallbackMediaResolver.resolvePath(context, null))
        assertNull(FallbackMediaResolver.resolvePath(context, ""))
        assertNull(FallbackMediaResolver.resolvePath(context, "   "))
    }

    @Test
    fun testResolvePathChecksActiveStorageFirst() {
        val activeDir = StorageManager.getFilesDirectory(context)
        val testRelativePath = "photos/photo_active_test.jpg"
        val activeFile =
            File(activeDir, testRelativePath).apply {
                parentFile?.mkdirs()
                writeText("active media content")
            }

        val resolved = FallbackMediaResolver.resolvePath(context, activeFile.absolutePath)
        assertEquals(activeFile.absolutePath, resolved)

        activeFile.delete()
    }

    @Test
    fun testResolvePathGracefullyFallsBackToPriorStorage() {
        val activeDir = StorageManager.getFilesDirectory(context)
        val tempPriorDir = File(context.cacheDir, "prior_storage/Telegram").apply { mkdirs() }
        val testRelativePath = "audio/voice_note_123.ogg"

        val priorFile =
            File(tempPriorDir, testRelativePath).apply {
                parentFile?.mkdirs()
                writeText("prior storage audio voice note")
            }

        // Active file does not exist yet
        val targetFile = File(activeDir, testRelativePath)
        if (targetFile.exists()) targetFile.delete()

        val resolved = FallbackMediaResolver.resolvePath(context, priorFile.absolutePath)
        assertNotNull(resolved)
        assertEquals(priorFile.absolutePath, resolved)
        assertTrue(File(resolved!!).exists())

        priorFile.delete()
        tempPriorDir.deleteRecursively()
    }

    @Test
    fun testResolveFileReturnsResolvedFileInstance() {
        val activeDir = StorageManager.getFilesDirectory(context)
        val testFile =
            File(activeDir, "documents/report.pdf").apply {
                parentFile?.mkdirs()
                writeText("pdf document data")
            }

        val resolvedFile = FallbackMediaResolver.resolveFile(context, testFile)
        assertNotNull(resolvedFile)
        assertEquals(testFile.absolutePath, resolvedFile!!.absolutePath)
        assertTrue(resolvedFile.exists())

        testFile.delete()
    }

    @Test
    fun testExtractRelativeTelegramPath() {
        val sampleFile = File("/storage/emulated/0/Android/data/com.teleflip.app/files/Telegram/video/clip.mp4")
        val relative = FallbackMediaResolver.extractRelativeTelegramPath(sampleFile, context)
        assertEquals("video/clip.mp4", relative)
    }

    @Test
    fun testThreadSafeFileTransferLocksReturnsSameInstance() {
        val lock1 = FileTransferLocks.getLock("photo_1.jpg")
        val lock2 = FileTransferLocks.getLock("photo_1.jpg")
        val lock3 = FileTransferLocks.getLock("photo_2.jpg")

        assertSame(lock1, lock2)
        assertTrue(lock1 !== lock3)
    }

    @Test
    fun testResolveNonExistentFileReturnsTargetOrOriginalPath() {
        val nonExistentPath = "/storage/emulated/0/Android/data/com.teleflip.app/files/Telegram/photos/missing.jpg"
        val resolved = FallbackMediaResolver.resolvePath(context, nonExistentPath)
        assertNotNull(resolved)
        assertTrue(resolved!!.endsWith("photos/missing.jpg"))
    }
}
