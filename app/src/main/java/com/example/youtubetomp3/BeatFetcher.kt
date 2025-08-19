package com.example.youtubetomp3

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class BeatFetcher : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}