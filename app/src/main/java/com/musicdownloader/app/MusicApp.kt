package com.musicdownloader.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.musicdownloader.app.data.db.AppDatabase

class MusicApp : Application() {
    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.create(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        // Channel for music playback notification (required by Media3)
        nm.createNotificationChannel(
            NotificationChannel(
                PLAYER_CHANNEL_ID,
                "Music Player",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback controls"
            }
        )
    }

    companion object {
        const val PLAYER_CHANNEL_ID = "music_player"

        lateinit var instance: MusicApp
            private set
    }
}
