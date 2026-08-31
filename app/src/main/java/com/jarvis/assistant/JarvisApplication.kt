package com.jarvis.assistant

import android.app.Application
import timber.log.Timber
import com.jarvis.assistant.media.AppForegroundTracker
import com.jarvis.assistant.util.CredentialsStore
import com.jarvis.assistant.util.FileLoggingTree

class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        CredentialsStore.init(this)

        // M2: process-foreground counting for the music lane. While no
        // activity is started, Android 10+ silently blocks our background
        // activity launches (deep links / cold starts), and the orchestrator
        // must phrase those outcomes as attempts, not achievements.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) =
                AppForegroundTracker.onActivityStarted()

            override fun onActivityStopped(activity: android.app.Activity) =
                AppForegroundTracker.onActivityStopped()

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })

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
