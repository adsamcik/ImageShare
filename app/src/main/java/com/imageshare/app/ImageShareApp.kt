package com.imageshare.app

import android.app.Application

class ImageShareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
    }
}
