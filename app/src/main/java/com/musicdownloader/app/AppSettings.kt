package com.musicdownloader.app

import android.content.Context

class AppSettings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var allowYoutube: Boolean
        get() = prefs.getBoolean("allow_youtube", false)
        set(value) { prefs.edit().putBoolean("allow_youtube", value).apply() }

    companion object {
        @Volatile private var instance: AppSettings? = null

        fun get(): AppSettings = instance ?: synchronized(this) {
            instance ?: AppSettings(MusicApp.instance).also { instance = it }
        }
    }
}
