package com.teleflip.app

import android.app.Application
import android.os.Build
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.teleflip.app.notifications.TeleFlipNotificationService
import com.teleflip.app.telegram.TelegramManager
import org.telegram.messenger.ApplicationLoader

class TeleFlipApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ApplicationLoader.applicationContext = this

        val telegramManager = TelegramManager.getInstance(this)
        if (telegramManager.backgroundPushEnabled) {
            TeleFlipNotificationService.startOrStop(this, true)
        }

        val imageLoader =
            ImageLoader.Builder(this)
                .components {
                    if (Build.VERSION.SDK_INT >= 28) {
                        add(ImageDecoderDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
                .build()
        Coil.setImageLoader(imageLoader)
    }
}
