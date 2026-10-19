package com.teleflip.app.notifications

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.teleflip.app.MainActivity
import com.teleflip.app.R

class TeleFlipNotificationService : Service() {
    companion object {
        private const val FG_NOTIFICATION_ID = 4201

        fun startOrStop(
            context: Context,
            enabled: Boolean,
        ) {
            try {
                val intent = Intent(context, TeleFlipNotificationService::class.java)
                if (enabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } else {
                    context.stopService(intent)
                }
            } catch (_: Throwable) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            NotificationHelper.ensureChannels(this)
            val openIntent = Intent(this, MainActivity::class.java)
            val pendingFlags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            val pendingIntent = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)

            val notification =
                NotificationCompat.Builder(this, NotificationHelper.CHANNEL_BACKGROUND_SYNC)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("TeleFlip Active")
                    .setContentText("Listening for messages & calls while flip is closed")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setContentIntent(pendingIntent)
                    .build()

            @Suppress("ForegroundServiceType")
            startForeground(FG_NOTIFICATION_ID, notification)
        } catch (_: Throwable) {
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
