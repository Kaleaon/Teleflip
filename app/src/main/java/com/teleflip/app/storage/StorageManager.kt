package com.teleflip.app.storage

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale

enum class StorageLocationType {
    INTERNAL,
    SD_CARD,
}

enum class AutoDownloadMode(val label: String) {
    WIFI_ONLY("Wi-Fi Only"),
    ALWAYS("Always (Cellular & Wi-Fi)"),
    NEVER("Never (Manual Download)"),
}

const val DAYS_IN_MONTH = 30
const val DAYS_IN_WEEK = 7
const val DAYS_IN_3_DAYS = 3

enum class KeepMediaDuration(val days: Int, val label: String) {
    FOREVER(0, "Forever (No Auto-Delete)"),
    MONTH_1(DAYS_IN_MONTH, "1 Month"),
    WEEK_1(DAYS_IN_WEEK, "1 Week"),
    DAYS_3(DAYS_IN_3_DAYS, "3 Days"),
}

data class StorageOption(
    val type: StorageLocationType,
    val title: String,
    val description: String,
    val path: File,
    val freeBytes: Long,
    val totalBytes: Long,
    val isRemovable: Boolean,
    val isAvailable: Boolean,
)

object StorageManager {
    private const val PREFS_NAME = "teleflip_prefs"
    private const val PREF_STORAGE_LOCATION = "pref_storage_location"
    private const val PREF_AUTO_DOWNLOAD = "pref_auto_download_media"
    private const val PREF_KEEP_MEDIA = "pref_keep_media_days"
    private const val PREF_SAVE_TO_DOWNLOADS = "pref_save_to_downloads"

    private const val BYTES_PER_KB = 1024L
    private const val BYTES_PER_MB = 1024L * 1024L
    private const val BYTES_PER_GB = 1024L * 1024L * 1024L
    const val SECONDS_PER_DAY = 86400
    const val DEFAULT_CACHE_TTL_MS = 15_000L

    private data class SdCardCache(
        val directory: File?,
        val isAvailable: Boolean,
        val timestamp: Long,
    )

    @Volatile
    private var sdCardCache: SdCardCache? = null

    internal var timeProvider: () -> Long = System::currentTimeMillis

    @Synchronized
    fun invalidateCache() {
        sdCardCache = null
    }

    fun formatStorageSize(bytes: Long): String {
        return if (bytes >= BYTES_PER_GB) {
            String.format(Locale.US, "%.1f GB", bytes.toDouble() / BYTES_PER_GB.toDouble())
        } else if (bytes >= BYTES_PER_MB) {
            String.format(Locale.US, "%.1f MB", bytes.toDouble() / BYTES_PER_MB.toDouble())
        } else {
            String.format(Locale.US, "%d KB", bytes / BYTES_PER_KB)
        }
    }

    fun getStorageOptions(context: Context): List<StorageOption> {
        val options = mutableListOf<StorageOption>()

        // 1. Internal Storage
        val internalDir = context.getExternalFilesDir(null) ?: context.filesDir
        val intFree = internalDir.freeSpace
        val intTotal = internalDir.totalSpace
        options.add(
            StorageOption(
                type = StorageLocationType.INTERNAL,
                title = "Internal Storage",
                description = "${formatStorageSize(intFree)} available of ${formatStorageSize(intTotal)}",
                path = internalDir,
                freeBytes = intFree,
                totalBytes = intTotal,
                isRemovable = false,
                isAvailable = true,
            ),
        )

        // 2. MicroSD Card / Removable External Storage
        val sdCardDir = detectSdCardDirectory(context)
        if (sdCardDir != null && sdCardDir.exists() && sdCardDir.canWrite()) {
            val sdFree = sdCardDir.freeSpace
            val sdTotal = sdCardDir.totalSpace
            options.add(
                StorageOption(
                    type = StorageLocationType.SD_CARD,
                    title = "MicroSD Card (Removable)",
                    description = "${formatStorageSize(sdFree)} available of ${formatStorageSize(sdTotal)}",
                    path = sdCardDir,
                    freeBytes = sdFree,
                    totalBytes = sdTotal,
                    isRemovable = true,
                    isAvailable = true,
                ),
            )
        } else {
            options.add(
                StorageOption(
                    type = StorageLocationType.SD_CARD,
                    title = "MicroSD Card",
                    description = "Not detected or not mounted",
                    path = File("/storage"),
                    freeBytes = 0L,
                    totalBytes = 0L,
                    isRemovable = true,
                    isAvailable = false,
                ),
            )
        }

        return options
    }

    fun detectSdCardDirectory(
        context: Context,
        forceRefresh: Boolean = false,
    ): File? {
        if (!forceRefresh) {
            val cache = sdCardCache
            if (cache != null) {
                val age = timeProvider() - cache.timestamp
                if (age in 0 until DEFAULT_CACHE_TTL_MS) {
                    return cache.directory
                }
            }
        }
        return refreshSdCardCache(context, forceRefresh).directory
    }

    private fun refreshSdCardCache(
        context: Context,
        forceRefresh: Boolean = false,
    ): SdCardCache {
        synchronized(this) {
            if (!forceRefresh) {
                val cache = sdCardCache
                if (cache != null) {
                    val age = timeProvider() - cache.timestamp
                    if (age in 0 until DEFAULT_CACHE_TTL_MS) {
                        return cache
                    }
                }
            }
            val dir = performSdCardDetection(context)
            val available = dir != null && dir.exists() && dir.canWrite()
            val newCache = SdCardCache(dir, available, timeProvider())
            sdCardCache = newCache
            return newCache
        }
    }

    private fun performSdCardDetection(context: Context): File? {
        var resultDir: File? = null
        try {
            val externalDirs = ContextCompat.getExternalFilesDirs(context, null)
            for (i in 1 until externalDirs.size) {
                val dir = externalDirs[i]
                if (dir != null && dir.exists() && dir.canWrite()) {
                    resultDir = dir
                    break
                }
            }
        } catch (_: Throwable) {
        }
        if (resultDir == null) {
            try {
                val storageRoot = File("/storage")
                if (storageRoot.exists() && storageRoot.isDirectory) {
                    val list = storageRoot.listFiles() ?: emptyArray()
                    for (sub in list) {
                        val name = sub.name.lowercase(Locale.ROOT)
                        if (name != "emulated" && name != "self" && name != "knox-emulated") {
                            val appDir = File(sub, "Android/data/${context.packageName}/files")
                            if ((appDir.mkdirs() || appDir.exists()) && appDir.canWrite()) {
                                resultDir = appDir
                                break
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return resultDir
    }

    fun isSdCardAvailable(
        context: Context,
        forceRefresh: Boolean = false,
    ): Boolean {
        if (!forceRefresh) {
            val cache = sdCardCache
            if (cache != null) {
                val age = timeProvider() - cache.timestamp
                if (age in 0 until DEFAULT_CACHE_TTL_MS) {
                    return cache.isAvailable
                }
            }
        }
        return refreshSdCardCache(context, forceRefresh).isAvailable
    }

    fun getSelectedStorageType(context: Context): StorageLocationType {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getString(PREF_STORAGE_LOCATION, "internal")
        return if (value == "sd_card" && isSdCardAvailable(context)) {
            StorageLocationType.SD_CARD
        } else {
            StorageLocationType.INTERNAL
        }
    }

    fun setSelectedStorageType(
        context: Context,
        type: StorageLocationType,
    ): Boolean {
        invalidateCache()
        if (type == StorageLocationType.SD_CARD && !isSdCardAvailable(context)) {
            return false
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = if (type == StorageLocationType.SD_CARD) "sd_card" else "internal"
        prefs.edit().putString(PREF_STORAGE_LOCATION, value).apply()
        invalidateCache()
        return true
    }

    fun getFilesDirectory(context: Context): File {
        val currentType = getSelectedStorageType(context)
        val baseDir =
            if (currentType == StorageLocationType.SD_CARD) {
                detectSdCardDirectory(context) ?: context.getExternalFilesDir(null) ?: context.filesDir
            } else {
                context.getExternalFilesDir(null) ?: context.filesDir
            }
        val telegramDir = File(baseDir, "Telegram")
        if (!telegramDir.exists()) {
            telegramDir.mkdirs()
        }
        return telegramDir
    }

    fun getDownloadDirectory(context: Context): File {
        val currentType = getSelectedStorageType(context)
        val baseDir =
            if (currentType == StorageLocationType.SD_CARD) {
                detectSdCardDirectory(context) ?: context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            } else {
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            }
        val target = File(baseDir, "TeleFlip_Downloads")
        if (!target.exists()) {
            target.mkdirs()
        }
        return target
    }

    fun getExportDirectory(
        context: Context,
        type: StorageLocationType? = null,
    ): File {
        val targetType = type ?: getSelectedStorageType(context)
        val baseDir =
            if (targetType == StorageLocationType.SD_CARD) {
                detectSdCardDirectory(context) ?: context.getExternalFilesDir(null) ?: context.filesDir
            } else {
                context.getExternalFilesDir(null) ?: context.filesDir
            }
        val target = File(baseDir, "TeleFlip_Exports")
        if (!target.exists()) {
            target.mkdirs()
        }
        return target
    }

    fun getAutoDownloadMode(context: Context): AutoDownloadMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_AUTO_DOWNLOAD, AutoDownloadMode.WIFI_ONLY.name)
        return try {
            AutoDownloadMode.valueOf(raw ?: AutoDownloadMode.WIFI_ONLY.name)
        } catch (_: Exception) {
            AutoDownloadMode.WIFI_ONLY
        }
    }

    fun setAutoDownloadMode(
        context: Context,
        mode: AutoDownloadMode,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_AUTO_DOWNLOAD, mode.name).apply()
    }

    fun shouldAutoDownloadMedia(context: Context): Boolean {
        return when (getAutoDownloadMode(context)) {
            AutoDownloadMode.NEVER -> false
            AutoDownloadMode.ALWAYS -> true
            AutoDownloadMode.WIFI_ONLY -> isConnectedToWifi(context)
        }
    }

    private fun isConnectedToWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        return caps != null &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    fun getKeepMediaDuration(context: Context): KeepMediaDuration {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val days = prefs.getInt(PREF_KEEP_MEDIA, 0)
        return KeepMediaDuration.values().find { it.days == days } ?: KeepMediaDuration.FOREVER
    }

    fun setKeepMediaDuration(
        context: Context,
        duration: KeepMediaDuration,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(PREF_KEEP_MEDIA, duration.days).apply()
    }

    fun isSaveToDownloadsEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_SAVE_TO_DOWNLOADS, false)
    }

    fun setSaveToDownloadsEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_SAVE_TO_DOWNLOADS, enabled).apply()
    }

    fun copyMediaFiles(
        fromDir: File,
        toDir: File,
    ): Int {
        if (!fromDir.exists() || !fromDir.isDirectory) return 0
        if (!toDir.exists()) toDir.mkdirs()
        var count = 0
        fromDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val relPath = file.relativeTo(fromDir).path
                val destFile = File(toDir, relPath)
                destFile.parentFile?.mkdirs()
                if (!destFile.exists() || destFile.length() != file.length()) {
                    file.copyTo(destFile, overwrite = true)
                    count++
                }
            }
        }
        return count
    }
}
