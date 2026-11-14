package com.teleflip.app.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class MediaMigrationWorkerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        MediaMigrationWorker.resetStateForTests()
        val config =
            androidx.work.Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.DEBUG)
                .setExecutor(androidx.work.testing.SynchronousExecutor())
                .build()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun testMigrationCopiesFilesAndCleansUpSource() {
        runBlocking {
            val srcDir = File(context.cacheDir, "test_migration_src_${System.currentTimeMillis()}").apply { mkdirs() }
            val targetDir = File(context.cacheDir, "test_migration_dst_${System.currentTimeMillis()}").apply { mkdirs() }

            val subDir = File(srcDir, "photos").apply { mkdirs() }
            val file1 = File(subDir, "photo1.jpg").apply { writeText("photo-data-1") }
            val file2 = File(srcDir, "doc.txt").apply { writeText("document-data-2") }

            val inputData =
                workDataOf(
                    MediaMigrationWorker.KEY_SOURCE_DIR to srcDir.absolutePath,
                    MediaMigrationWorker.KEY_TARGET_DIR to targetDir.absolutePath,
                    MediaMigrationWorker.KEY_TARGET_TYPE to StorageLocationType.INTERNAL.name,
                )

            val worker =
                TestListenableWorkerBuilder<MediaMigrationWorker>(context)
                    .setInputData(inputData)
                    .build()

            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.success(), result)

            val migratedFile1 = File(targetDir, "photos/photo1.jpg")
            val migratedFile2 = File(targetDir, "doc.txt")

            assertTrue(migratedFile1.exists())
            assertEquals("photo-data-1", migratedFile1.readText())

            assertTrue(migratedFile2.exists())
            assertEquals("document-data-2", migratedFile2.readText())

            // Source files must be cleaned up
            assertFalse(file1.exists())
            assertFalse(file2.exists())

            srcDir.deleteRecursively()
            targetDir.deleteRecursively()
        }
    }

    @Test
    fun testNonExistentSourceDirectoryReturnsSuccessGracefully() {
        runBlocking {
            val nonExistentSrc = File(context.cacheDir, "non_existent_dir_${System.currentTimeMillis()}")
            val targetDir = File(context.cacheDir, "target_${System.currentTimeMillis()}").apply { mkdirs() }

            val inputData =
                workDataOf(
                    MediaMigrationWorker.KEY_SOURCE_DIR to nonExistentSrc.absolutePath,
                    MediaMigrationWorker.KEY_TARGET_DIR to targetDir.absolutePath,
                    MediaMigrationWorker.KEY_TARGET_TYPE to StorageLocationType.INTERNAL.name,
                )

            val worker =
                TestListenableWorkerBuilder<MediaMigrationWorker>(context)
                    .setInputData(inputData)
                    .build()

            val result = worker.doWork()
            assertEquals(ListenableWorker.Result.success(), result)

            targetDir.deleteRecursively()
        }
    }

    @Test
    fun testMigrationPreventsConcurrentRequests() {
        val srcDir = File(context.cacheDir, "src_test").apply { mkdirs() }
        val targetDir = File(context.cacheDir, "target_test").apply { mkdirs() }

        assertFalse(MediaMigrationWorker.isMigrationRunning)
        val enqueued =
            MediaMigrationWorker.enqueue(
                context = context,
                sourceDir = srcDir,
                targetDir = targetDir,
                targetType = StorageLocationType.INTERNAL,
            )
        assertTrue(enqueued)

        // When migration is running, concurrent requests are rejected
        MediaMigrationWorker.setMigrationRunningForTests(true)

        assertTrue(MediaMigrationWorker.isMigrationRunning)
        val duplicateEnqueued =
            MediaMigrationWorker.enqueue(
                context = context,
                sourceDir = srcDir,
                targetDir = targetDir,
                targetType = StorageLocationType.INTERNAL,
            )
        assertFalse(duplicateEnqueued)

        srcDir.deleteRecursively()
        targetDir.deleteRecursively()
    }
}
