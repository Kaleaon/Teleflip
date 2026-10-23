package com.teleflip.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.teleflip.app.telegram.TelegramManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val tm = TelegramManager.getInstance(context)
            if (tm.backgroundPushEnabled) {
                TeleFlipNotificationService.startOrStop(context, true)
            }
        }
    }
}
