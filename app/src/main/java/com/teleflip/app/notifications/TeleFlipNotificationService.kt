package com.teleflip.app.notifications

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
                    context.startForegroundService(intent)
                } else {
                    context.stopService(intent)
                }
            } catch (_: Throwable) {
            }
        }
    }

    @android.annotation.SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()
        try {
            NotificationHelper.ensureChannels(this)
            val openIntent = Intent(this, MainActivity::class.java)
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
