package com.teleflip.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import com.teleflip.app.MainActivity
import com.teleflip.app.R

object NotificationHelper {
    const val CHANNEL_MESSAGES = "teleflip_messages"
    const val CHANNEL_CALLS = "teleflip_calls"
    const val CHANNEL_BACKGROUND_SYNC = "teleflip_bg_sync"
    const val EXTRA_OPEN_CHAT_ID = "extra_open_chat_id"

    @Volatile
    private var channelsCreated = false

    @VisibleForTesting
    fun resetChannelsCreatedForTests() {
        channelsCreated = false
    }

    fun ensureChannels(context: Context) {
        if (channelsCreated || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        synchronized(this) {
            if (!channelsCreated) {
                val msgChannel =
                    NotificationChannel(
                        CHANNEL_MESSAGES,
                        "Telegram Messages",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = "Alerts for new Telegram messages when flip phone is closed"
                        enableVibration(true)
                        enableLights(true)
                    }

                val callChannel =
                    NotificationChannel(
                        CHANNEL_CALLS,
                        "Telegram Voice Calls",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = "Incoming encrypted Telegram voice calls"
                        enableVibration(true)
                        enableLights(true)
                    }

                val syncChannel =
                    NotificationChannel(
                        CHANNEL_BACKGROUND_SYNC,
                        "Background Flip-Closed Push Sync",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = "Keeps MTProto connection alive while the flip phone is closed"
                        setShowBadge(false)
                    }

                nm.createNotificationChannels(listOf(msgChannel, callChannel, syncChannel))
                channelsCreated = true
            }
        }
    }

    fun showIncomingMessageNotification(
        context: Context,
        chatId: Long,
        chatTitle: String,
        senderName: String,
        messageText: String,
    ) {
        try {
            ensureChannels(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            val openIntent =
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_OPEN_CHAT_ID, chatId)
                }
            val pendingFlags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            val pendingIntent = PendingIntent.getActivity(context, chatId.toInt(), openIntent, pendingFlags)

            val contentTitle = if (senderName.isNotBlank() && senderName != chatTitle) "$chatTitle ($senderName)" else chatTitle
            val notification =
                NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(contentTitle)
                    .setContentText(messageText)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()

            nm.notify(chatId.hashCode(), notification)
            acquireWakeLock(context, 3000L)
        } catch (_: Throwable) {
            // Safe no-op in headless unit tests where NotificationManager is mocked
        }
    }

    fun cancelMessageNotification(
        context: Context,
        chatId: Long,
    ) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.cancel(chatId.hashCode())
        } catch (_: Throwable) {
        }
    }

    fun showIncomingCallNotification(
        context: Context,
        callId: Int,
        callerName: String,
    ) {
        try {
            ensureChannels(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            val openIntent =
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            val pendingFlags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            val pendingIntent = PendingIntent.getActivity(context, callId, openIntent, pendingFlags)

            val notification =
                NotificationCompat.Builder(context, CHANNEL_CALLS)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("📞 Incoming Telegram Call")
                    .setContentText("$callerName is calling...")
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .build()

            nm.notify(900000 + callId, notification)
            acquireWakeLock(context, 10000L)
        } catch (_: Throwable) {
        }
    }

    private fun acquireWakeLock(
        context: Context,
        timeoutMs: Long,
    ) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "teleflip:notification_wake")
            wl?.setReferenceCounted(false)
            wl?.acquire(timeoutMs)
        } catch (_: Throwable) {
        }
    }
}
