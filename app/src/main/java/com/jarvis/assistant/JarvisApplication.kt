package com.jarvis.assistant

import android.app.Application
import timber.log.Timber
import com.jarvis.assistant.util.FileLoggingTree

class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // Release builds log to rotating files under filesDir/logs/ so the
        // RUNBOOK's debugging procedures work everywhere.
        Timber.plant(FileLoggingTree(this))

        val missingModel = assets?.list("")?.let { !it.contains("jarvis_ru.ppn") } ?: true
        if (missingModel) {
            Timber.e("Wake word model 'jarvis_ru.ppn' not found in assets/")
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Timber.e(throwable, "Uncaught exception in thread ${thread.name}")
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
