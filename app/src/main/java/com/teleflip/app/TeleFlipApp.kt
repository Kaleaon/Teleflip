package com.teleflip.app

import android.app.Application
import org.telegram.messenger.ApplicationLoader

class TeleFlipApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ApplicationLoader.applicationContext = this
    }
}
