package com.teleflip.app.calls

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.teleflip.app.MainActivity
import com.teleflip.app.R
import com.teleflip.app.telegram.TelegramManager

class CallAudioService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Ongoing calls", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val end = PendingIntent.getService(this, 1, Intent(this, CallAudioService::class.java).setAction(END), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(
            4201,
            NotificationCompat.Builder(
                this,
                CHANNEL,
            ).setSmallIcon(
                R.mipmap.ic_launcher,
            ).setContentTitle(
                "TeleFlip call",
            ).setContentText("Call in progress").setOngoing(true).setCategory(NotificationCompat.CATEGORY_CALL).setContentIntent(open).addAction(0, "End call", end).build(),
        )
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TeleFlip:Call").apply { acquire() }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == END) {
            val manager = TelegramManager.getInstance(this)
            manager.endCurrentCall()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        TelegramManager.getInstance(this).onCallAudioServiceStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "ongoing_call_audio"
        private const val END = "com.teleflip.app.END_CALL"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CallAudioService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallAudioService::class.java))
        }
    }
}
