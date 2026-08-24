package com.jarvis.assistant

import android.app.Application

class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Entry point. The foreground service is launched by BootReceiver
        // (on boot) or by a launcher activity / quick-settings tile.
    }
}
