package com.imageshare.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Application
import com.imageshare.app.work.BatchProcessWorker

class ImageShareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
        val channel = NotificationChannel(
            BatchProcessWorker.CHANNEL_ID,
            getString(R.string.batch_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.batch_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
