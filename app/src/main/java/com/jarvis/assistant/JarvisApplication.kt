package com.jarvis.assistant

import android.app.Application
import com.jarvis.assistant.BuildConfig
import timber.log.Timber

class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        val missingAssets = assets?.list("")?.let { !it.contains("jarvis_ru.ppn") } ?: true
        if (missingAssets) {
            Timber.e("Wake word model 'jarvis_ru.ppn' not found in assets/")
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Timber.e(throwable, "Uncaught exception in thread ${thread.name}")
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}