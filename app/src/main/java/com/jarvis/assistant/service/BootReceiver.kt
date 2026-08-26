package com.jarvis.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.jarvis.assistant.data.AppDatabase
import com.jarvis.assistant.tools.AndroidAlarmScheduler
import com.jarvis.assistant.util.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Starts the assistant after boot / app update AND re-arms every persisted
 * alert through the unified scheduler: alarms always (dailies rolled past
 * missed days) and timers while still in the future — before the unified
 * store timers vanished on reboot entirely (M9/S3).
 *
 * A fresh boot clears the userStopped flag: the appliance profile expects the
 * assistant to come back after a reboot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val prefs = AppPrefs(context)

        // Re-arm persisted alerts first — they must survive reboots.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(context).alarmDao()
                AndroidAlarmScheduler(context, dao).rescheduleAllOnBoot()
            } finally {
                pending.finish()
            }
        }

        prefs.userStopped = false // fresh boot = assistant may auto-start
        JarvisForegroundService.explicitStart(context)
    }
}
