package com.teleflip.app.storage

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe file transfer locks to synchronize background migration and lazy copying.
 */
object FileTransferLocks {
    private val locks = ConcurrentHashMap<String, Any>()

    fun getLock(fileName: String): Any = locks.computeIfAbsent(fileName) { Any() }

    fun clear() {
        locks.clear()
    }
}

/**
 * Resolves media file paths using a dual-path fallback strategy.
 * Checks the active storage directory first; if the file is not yet migrated,
 * gracefully falls back to the prior/internal storage directory and lazily migrates the file.
 */
object FallbackMediaResolver {
    private const val TAG = "FallbackMediaResolver"
    private const val TELEGRAM_DIR_NAME = "Telegram"

    private val resolverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var applicationContext: Context? = null

    /**
     * Resolves the given file path to an accessible local file path, checking the active
     * storage directory first and falling back to alternative storage locations if needed.
     */
    fun resolvePath(
        context: Context?,
        path: String?,
    ): String? {
        if (path.isNullOrBlank()) return null
        val ctx = context?.applicationContext ?: applicationContext
        val originalFile = File(path)

        if (ctx == null) {
            return if (originalFile.exists()) originalFile.absolutePath else path
        }

        val activeTelegramDir = StorageManager.getFilesDirectory(ctx)
        val relativeSubPath = extractRelativeTelegramPath(originalFile, ctx)

        // 1. Check active storage directory first
        if (relativeSubPath != null) {
            val activeCandidate = File(activeTelegramDir, relativeSubPath)
            val lock = FileTransferLocks.getLock(activeCandidate.name)
            val activeExists =
                synchronized(lock) {
                    activeCandidate.exists() && activeCandidate.isFile && activeCandidate.length() > 0
                }
            if (activeExists) {
                return activeCandidate.absolutePath
            }
        } else if (originalFile.exists() && isInsideDirectory(originalFile, activeTelegramDir)) {
            return originalFile.absolutePath
        }

        // 2. Check fallback storage directories
        val fallbackDirs = getFallbackTelegramDirectories(ctx, activeTelegramDir)
        for (fallbackDir in fallbackDirs) {
            val fallbackCandidate =
                if (relativeSubPath != null) {
                    File(fallbackDir, relativeSubPath)
                } else if (originalFile.exists() && isInsideDirectory(originalFile, fallbackDir)) {
                    originalFile
                } else {
                    null
                }

            if (fallbackCandidate != null) {
                val lock = FileTransferLocks.getLock(fallbackCandidate.name)
                val exists =
                    synchronized(lock) {
                        fallbackCandidate.exists() && fallbackCandidate.isFile && fallbackCandidate.length() > 0
                    }
                if (exists) {
                    // Lazily copy to active directory if target candidate is defined
                    if (relativeSubPath != null) {
                        val targetCandidate = File(activeTelegramDir, relativeSubPath)
                        triggerLazyMigration(fallbackCandidate, targetCandidate)
                    }
                    return fallbackCandidate.absolutePath
                }
            }
        }

        // 3. Fallback: If original file exists at its literal path anywhere, return it
        if (originalFile.exists() && originalFile.isFile && originalFile.length() > 0) {
            return originalFile.absolutePath
        }

        // If target file was constructed, return target path or original path
        return if (relativeSubPath != null) {
            File(activeTelegramDir, relativeSubPath).absolutePath
        } else {
            path
        }
    }

    /**
     * Resolves a File instance using the dual-path fallback strategy.
     */
    fun resolveFile(
        context: Context?,
        file: File?,
    ): File? {
        if (file == null) return null
        val resolved = resolvePath(context, file.absolutePath) ?: return file
        return File(resolved)
    }

    /**
     * Extracts the relative path after the 'Telegram' directory.
     */
    internal fun extractRelativeTelegramPath(
        file: File,
        context: Context,
    ): String? {
        val path = file.absolutePath
        val marker = "/$TELEGRAM_DIR_NAME/"
        val idx = path.indexOf(marker)
        if (idx != -1) {
            return path.substring(idx + marker.length)
        }

        val activeDir = StorageManager.getFilesDirectory(context)
        val relActive = file.relativeToOrNull(activeDir)?.path
        if (relActive != null && !relActive.startsWith("..")) {
            return relActive
        }

        val internalDir = StorageManager.getFilesDirectoryForType(context, StorageLocationType.INTERNAL)
        val relInternal = file.relativeToOrNull(internalDir)?.path
        if (relInternal != null && !relInternal.startsWith("..")) {
            return relInternal
        }

        if (StorageManager.isSdCardAvailable(context)) {
            val sdDir = StorageManager.getFilesDirectoryForType(context, StorageLocationType.SD_CARD)
            val relSd = file.relativeToOrNull(sdDir)?.path
            if (relSd != null && !relSd.startsWith("..")) {
                return relSd
            }
        }

        return null
    }

    private fun isInsideDirectory(
        file: File,
        dir: File,
    ): Boolean {
        return try {
            file.canonicalPath.startsWith(dir.canonicalPath)
        } catch (_: Exception) {
            file.absolutePath.startsWith(dir.absolutePath)
        }
    }

    /**
     * Retrieves alternative Telegram storage directories to inspect during fallback.
     */
    internal fun getFallbackTelegramDirectories(
        context: Context,
        activeDir: File,
    ): List<File> {
        val candidates = mutableListOf<File>()

        // 1. Internal storage Telegram directory
        val internalDir = StorageManager.getFilesDirectoryForType(context, StorageLocationType.INTERNAL)
        if (internalDir.absolutePath != activeDir.absolutePath) {
            candidates.add(internalDir)
        }

        // 2. SD Card storage Telegram directory (if available)
        if (StorageManager.isSdCardAvailable(context)) {
            val sdDir = StorageManager.getFilesDirectoryForType(context, StorageLocationType.SD_CARD)
            if (sdDir.absolutePath != activeDir.absolutePath) {
                candidates.add(sdDir)
            }
        }

        return candidates
    }

    /**
     * Asynchronously and safely copies a file from source to target storage.
     */
    internal fun triggerLazyMigration(
        sourceFile: File,
        targetFile: File,
    ) {
        if (sourceFile.absolutePath == targetFile.absolutePath) return
        resolverScope.launch {
            val lock = FileTransferLocks.getLock(sourceFile.name)
            synchronized(lock) {
                try {
                    if (!sourceFile.exists()) return@synchronized
                    if (targetFile.exists() && targetFile.length() == sourceFile.length()) return@synchronized
                    targetFile.parentFile?.mkdirs()
                    val tempTarget = File(targetFile.parentFile, "${targetFile.name}.tmp")
                    sourceFile.copyTo(tempTarget, overwrite = true)
                    tempTarget.renameTo(targetFile)
                } catch (e: Exception) {
                    Log.w(TAG, "Lazy migration failed for ${sourceFile.name}: ${e.message}")
                }
            }
        }
    }
}
