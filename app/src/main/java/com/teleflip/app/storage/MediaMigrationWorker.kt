package com.teleflip.app.storage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.teleflip.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android WorkManager CoroutineWorker that copies existing media files asynchronously
 * from old storage to new storage, holding a partial WakeLock and handling SD card state changes.
 */
class MediaMigrationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val sourceDirPath = inputData.getString(KEY_SOURCE_DIR) ?: return@withContext Result.failure()
            val targetDirPath = inputData.getString(KEY_TARGET_DIR) ?: return@withContext Result.failure()
            val targetTypeName = inputData.getString(KEY_TARGET_TYPE) ?: StorageLocationType.INTERNAL.name

            val sourceDir = File(sourceDirPath)
            val targetDir = File(targetDirPath)
            val targetType =
                try {
                    StorageLocationType.valueOf(targetTypeName)
                } catch (_: Exception) {
                    StorageLocationType.INTERNAL
                }

            if (!sourceDir.exists() || !sourceDir.isDirectory) {
                notifyCompletion(applicationContext, 0, success = true)
                return@withContext Result.success()
            }

            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wakeLock =
                powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)?.apply {
                    setReferenceCounted(false)
                    acquire(WAKELOCK_TIMEOUT_MS)
                }

            _isMigrationRunning.set(true)
            _migrationStatusFlow.value = true

            try {
                var migratedCount = 0
                val allFiles = sourceDir.walkTopDown().filter { it.isFile }.toList()

                for (file in allFiles) {
                    if (isStopped) {
                        Log.w(TAG, "Migration stopped or cancelled by WorkManager")
                        return@withContext Result.retry()
                    }

                    // Check SD card availability if moving to or from SD card
                    if (targetType == StorageLocationType.SD_CARD || isPathOnSdCard(sourceDir)) {
                        var retries = 0
                        while (!StorageManager.isSdCardAvailable(applicationContext, forceRefresh = true) && retries < MAX_SD_RETRY_COUNT) {
                            if (isStopped) return@withContext Result.retry()
                            Log.i(TAG, "SD Card is currently unmounted. Pausing migration (retry $retries)...")
                            delay(SD_RETRY_DELAY_MS)
                            retries++
                        }
                        if (!StorageManager.isSdCardAvailable(applicationContext, forceRefresh = true)) {
                            Log.e(TAG, "SD Card unavailable during migration after timeout")
                            notifyCompletion(applicationContext, migratedCount, success = false)
                            return@withContext Result.retry()
                        }
                    }

                    val relPath = file.relativeTo(sourceDir).path
                    val destFile = File(targetDir, relPath)
                    val lock = FileTransferLocks.getLock(file.name)

                    val success =
                        synchronized(lock) {
                            try {
                                destFile.parentFile?.mkdirs()
                                if (!destFile.exists() || destFile.length() != file.length()) {
                                    val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")
                                    file.copyTo(tempFile, overwrite = true)
                                    tempFile.renameTo(destFile)
                                }
                                // Verify transfer before deleting source file
                                if (destFile.exists() && destFile.length() == file.length()) {
                                    file.delete()
                                    true
                                } else {
                                    false
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed migrating file ${file.name}: ${e.message}")
                                false
                            }
                        }

                    if (success) {
                        migratedCount++
                    }
                }

                cleanEmptyDirectories(sourceDir)
                notifyCompletion(applicationContext, migratedCount, success = true)
                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Migration failed with exception: ${e.message}", e)
                notifyCompletion(applicationContext, 0, success = false)
                Result.failure()
            } finally {
                _isMigrationRunning.set(false)
                _migrationStatusFlow.value = false
                try {
                    if (wakeLock?.isHeld == true) {
                        wakeLock.release()
                    }
                } catch (_: Exception) {
                }
            }
        }

    private fun isPathOnSdCard(dir: File): Boolean {
        val sdDir = StorageManager.detectSdCardDirectory(applicationContext) ?: return false
        return try {
            dir.canonicalPath.startsWith(sdDir.canonicalPath)
        } catch (_: Exception) {
            dir.absolutePath.startsWith(sdDir.absolutePath)
        }
    }

    private fun cleanEmptyDirectories(dir: File) {
        try {
            dir.walkBottomUp().forEach { f ->
                if (f.isDirectory && f.absolutePath != dir.absolutePath && f.listFiles()?.isEmpty() == true) {
                    f.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun notifyCompletion(
        context: Context,
        migratedCount: Int,
        success: Boolean,
    ) {
        val title = if (success) "Media Migration Complete" else "Media Migration Incomplete"
        val message =
            if (success) {
                "Successfully migrated $migratedCount media file(s)."
            } else {
                "Storage migration encountered an error or SD card was unmounted."
            }

        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }

        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel =
                    NotificationChannel(
                        CHANNEL_ID,
                        "Media Migration",
                        NotificationManager.IMPORTANCE_LOW,
                    )
                nm.createNotificationChannel(channel)
            }

            val notif =
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()

            nm.notify(NOTIFICATION_ID, notif)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "com.teleflip.app.media_migration_work"
        const val KEY_SOURCE_DIR = "key_source_dir"
        const val KEY_TARGET_DIR = "key_target_dir"
        const val KEY_TARGET_TYPE = "key_target_type"

        private const val TAG = "MediaMigrationWorker"
        private const val WAKELOCK_TAG = "teleflip:MediaMigrationWakeLock"
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val NOTIFICATION_ID = 4001
        private const val CHANNEL_ID = "teleflip_media_migration"
        private const val SD_RETRY_DELAY_MS = 2000L
        private const val MAX_SD_RETRY_COUNT = 5

        private val _isMigrationRunning = AtomicBoolean(false)
        val isMigrationRunning: Boolean
            get() = _isMigrationRunning.get()

        private val _migrationStatusFlow = MutableStateFlow(false)
        val migrationStatusFlow: StateFlow<Boolean> = _migrationStatusFlow.asStateFlow()

        /**
         * Enqueues an asynchronous media migration worker task.
         * Ensures no concurrent migrations run by using ExistingWorkPolicy.KEEP.
         */
        fun enqueue(
            context: Context,
            sourceDir: File,
            targetDir: File,
            targetType: StorageLocationType,
        ): Boolean {
            if (_isMigrationRunning.get()) {
                Log.w(TAG, "Migration is already running; skipping duplicate enqueue request.")
                return false
            }

            val data =
                workDataOf(
                    KEY_SOURCE_DIR to sourceDir.absolutePath,
                    KEY_TARGET_DIR to targetDir.absolutePath,
                    KEY_TARGET_TYPE to targetType.name,
                )

            val request =
                OneTimeWorkRequestBuilder<MediaMigrationWorker>()
                    .setInputData(data)
                    .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
            return true
        }

        fun resetStateForTests() {
            _isMigrationRunning.set(false)
            _migrationStatusFlow.value = false
        }

        fun setMigrationRunningForTests(running: Boolean) {
            _isMigrationRunning.set(running)
            _migrationStatusFlow.value = running
        }
    }
}
