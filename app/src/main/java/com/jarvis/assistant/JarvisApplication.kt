package com.jarvis.assistant

import android.app.Application
import timber.log.Timber

class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
    }
}
