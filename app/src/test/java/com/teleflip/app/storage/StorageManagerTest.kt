package com.teleflip.app.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class StorageManagerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        StorageManager.invalidateCache()
        StorageManager.timeProvider = System::currentTimeMillis
    }

    @Test
    fun testConsecutiveInvocations() {
        val sdCardAvailable = StorageManager.isSdCardAvailable(context)
        assertEquals(sdCardAvailable, StorageManager.isSdCardAvailable(context))

        val selectedStorage = StorageManager.getSelectedStorageType(context)
        assertNotNull(selectedStorage)

        val filesDir = StorageManager.getFilesDirectory(context)
        assertNotNull(filesDir)

        val downloadDir = StorageManager.getDownloadDirectory(context)
        assertNotNull(downloadDir)
    }

    @Test
    fun testSetSelectedStorageType() {
        val result = StorageManager.setSelectedStorageType(context, StorageLocationType.INTERNAL)
        assertTrue(result)
        assertEquals(StorageLocationType.INTERNAL, StorageManager.getSelectedStorageType(context))
    }

    @Test
    fun testCacheExpirationAfterTtl() {
        var currentTime = 1_000_000L
        StorageManager.timeProvider = { currentTime }

        val initialAvailable = StorageManager.isSdCardAvailable(context)

        // Advance time by 5 seconds (within 15s TTL)
        currentTime += 5_000L
        assertEquals(initialAvailable, StorageManager.isSdCardAvailable(context))

        // Advance time past 15s TTL (20 seconds total)
        currentTime += 15_000L
        assertEquals(initialAvailable, StorageManager.isSdCardAvailable(context))
    }

    @Test
    fun testExplicitInvalidateCache() {
        val initialAvailable = StorageManager.isSdCardAvailable(context)
        StorageManager.invalidateCache()
        assertEquals(initialAvailable, StorageManager.isSdCardAvailable(context))
    }

    @Test
    fun testForceRefresh() {
        val dir = StorageManager.detectSdCardDirectory(context, forceRefresh = true)
        val available = StorageManager.isSdCardAvailable(context, forceRefresh = true)
        if (dir != null) {
            assertTrue(available)
        }
    }

    @Test
    fun testFormatStorageSize() {
        assertEquals("1.5 GB", StorageManager.formatStorageSize(1610612736L))
        assertEquals("500.0 MB", StorageManager.formatStorageSize(524288000L))
        assertEquals("100 KB", StorageManager.formatStorageSize(102400L))
    }

    @Test
    fun testGetStorageOptionsAlwaysIncludesInternal() {
        val options = StorageManager.getStorageOptions(context)
        assertTrue(options.isNotEmpty())
        val internalOpt = options.find { it.type == StorageLocationType.INTERNAL }
        assertNotNull(internalOpt)
        assertTrue(internalOpt!!.isAvailable)
        assertFalse(internalOpt.isRemovable)
    }

    @Test
    fun testDefaultStorageLocationIsInternal() {
        val selected = StorageManager.getSelectedStorageType(context)
        assertEquals(StorageLocationType.INTERNAL, selected)
        val filesDir = StorageManager.getFilesDirectory(context)
        assertNotNull(filesDir)
        assertTrue(filesDir.exists())
        assertTrue(filesDir.name == "Telegram")
    }

    @Test
    fun testAutoDownloadModeGetAndSet() {
        assertEquals(AutoDownloadMode.WIFI_ONLY, StorageManager.getAutoDownloadMode(context))

        StorageManager.setAutoDownloadMode(context, AutoDownloadMode.ALWAYS)
        assertEquals(AutoDownloadMode.ALWAYS, StorageManager.getAutoDownloadMode(context))
        assertTrue(StorageManager.shouldAutoDownloadMedia(context))

        StorageManager.setAutoDownloadMode(context, AutoDownloadMode.NEVER)
        assertEquals(AutoDownloadMode.NEVER, StorageManager.getAutoDownloadMode(context))
        assertFalse(StorageManager.shouldAutoDownloadMedia(context))
    }

    @Test
    fun testKeepMediaDurationGetAndSet() {
        assertEquals(KeepMediaDuration.FOREVER, StorageManager.getKeepMediaDuration(context))

        StorageManager.setKeepMediaDuration(context, KeepMediaDuration.WEEK_1)
        assertEquals(KeepMediaDuration.WEEK_1, StorageManager.getKeepMediaDuration(context))
        assertEquals(7, StorageManager.getKeepMediaDuration(context).days)

        StorageManager.setKeepMediaDuration(context, KeepMediaDuration.DAYS_3)
        assertEquals(KeepMediaDuration.DAYS_3, StorageManager.getKeepMediaDuration(context))
        assertEquals(3, StorageManager.getKeepMediaDuration(context).days)
    }

    @Test
    fun testSaveToDownloadsGetAndSet() {
        assertFalse(StorageManager.isSaveToDownloadsEnabled(context))

        StorageManager.setSaveToDownloadsEnabled(context, true)
        assertTrue(StorageManager.isSaveToDownloadsEnabled(context))

        val downloadDir = StorageManager.getDownloadDirectory(context)
        assertNotNull(downloadDir)
        assertTrue(downloadDir.exists())
    }

    @Test
    fun testCopyMediaFiles() {
        val srcDir = File(context.cacheDir, "test_src_${System.currentTimeMillis()}")
        val dstDir = File(context.cacheDir, "test_dst_${System.currentTimeMillis()}")
        srcDir.mkdirs()
        dstDir.mkdirs()

        val sampleFile = File(srcDir, "sample.txt")
        sampleFile.writeText("TeleFlip media copy test")

        val copiedCount = StorageManager.copyMediaFiles(srcDir, dstDir)
        assertEquals(1, copiedCount)
        val destFile = File(dstDir, "sample.txt")
        assertTrue(destFile.exists())
        assertEquals("TeleFlip media copy test", destFile.readText())

        srcDir.deleteRecursively()
        dstDir.deleteRecursively()
    }
}
