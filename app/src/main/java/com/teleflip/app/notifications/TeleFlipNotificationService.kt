package com.teleflip.app.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import com.teleflip.app.MainActivity
import com.teleflip.app.R
import com.teleflip.app.telegram.TelegramManager
import org.drinkless.tdlib.TdApi

class TeleFlipNotificationService : Service() {
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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

    @android.annotation.SuppressLint("ForegroundServiceType")
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FG_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(FG_NOTIFICATION_ID, notification)
            }

            registerNetworkCallback()
        } catch (_: Throwable) {
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        try {
            val telegramManager = TelegramManager.getInstance(applicationContext)
            telegramManager.start()
            connectivityManager?.let { updateCurrentNetworkType(it) }
        } catch (_: Throwable) {
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            val telegramManager = TelegramManager.getInstance(applicationContext)
            if (telegramManager.backgroundPushEnabled) {
                val restartIntent = Intent(applicationContext, TeleFlipNotificationService::class.java)
                val restartPending =
                    PendingIntent.getService(
                        applicationContext,
                        1,
                        restartIntent,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                        } else {
                            PendingIntent.FLAG_ONE_SHOT
                        },
                    )
                val alarmService = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                alarmService?.set(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + 1000L,
                    restartPending,
                )
            }
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterNetworkCallback()
        try {
            val telegramManager = TelegramManager.getInstance(applicationContext)
            if (!telegramManager.backgroundPushEnabled) {
                telegramManager.stop()
            }
        } catch (_: Throwable) {
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            connectivityManager = cm
            updateCurrentNetworkType(cm)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val callback =
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            updateCurrentNetworkType(cm)
                        }

                        override fun onLost(network: Network) {
                            updateCurrentNetworkType(cm)
                        }

                        override fun onCapabilitiesChanged(
                            network: Network,
                            networkCapabilities: NetworkCapabilities,
                        ) {
                            updateCurrentNetworkType(cm)
                        }
                    }
                networkCallback = callback
                cm.registerDefaultNetworkCallback(callback)
            }
        } catch (_: Throwable) {
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
            }
        } catch (_: Throwable) {
        }
        networkCallback = null
    }

    private fun updateCurrentNetworkType(cm: ConnectivityManager) {
        try {
            val type = getTdNetworkType(cm)
            val telegramManager = TelegramManager.getInstance(applicationContext)
            telegramManager.setNetworkType(type)
        } catch (_: Throwable) {
        }
    }

    private fun getTdNetworkType(cm: ConnectivityManager): TdApi.NetworkType {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = cm.activeNetwork ?: return TdApi.NetworkTypeNone()
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return TdApi.NetworkTypeNone()
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return TdApi.NetworkTypeNone()
            }
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TdApi.NetworkTypeWiFi()
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                    if (tm?.isNetworkRoaming == true) {
                        TdApi.NetworkTypeMobileRoaming()
                    } else {
                        TdApi.NetworkTypeMobile()
                    }
                }
                else -> TdApi.NetworkTypeOther()
            }
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            if (info == null || !info.isConnected) {
                return TdApi.NetworkTypeNone()
            }
            @Suppress("DEPRECATION")
            when (info.type) {
                ConnectivityManager.TYPE_WIFI -> TdApi.NetworkTypeWiFi()
                ConnectivityManager.TYPE_MOBILE -> {
                    if (info.isRoaming) TdApi.NetworkTypeMobileRoaming() else TdApi.NetworkTypeMobile()
                }
                else -> TdApi.NetworkTypeOther()
            }
        }
    }
}
