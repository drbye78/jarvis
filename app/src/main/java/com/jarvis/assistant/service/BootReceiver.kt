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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Starts the assistant after boot / app update AND re-arms every persisted
 * alert through the unified scheduler: alarms always (dailies rolled past
 * missed days) and timers while still in the future — before the unified
 * store timers vanished on reboot entirely (M9/S3).
 *
 * A fresh boot clears the userStopped flag: the appliance profile expects the
 * assistant to come back after a reboot. An app UPDATE (MY_PACKAGE_REPLACED)
 * does NOT clear it — the service documents that an explicit stop keeps the
 * assistant stopped, and resurrecting it on every APK update violates that
 * contract. The assistant also never auto-starts before the user finished
 * onboarding (the old code started the service on every reboot of a
 * half-configured install, spamming the "microphone needed" notification).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val prefs = AppPrefs(context)

        // Re-arm persisted alerts first — they must survive reboots.
        // Scope is cancelled after pending.finish() so it never outlives the
        // BroadcastReceiver's 10-second goAsync() window.
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val dao = AppDatabase.getInstance(context).alarmDao()
                AndroidAlarmScheduler(context, dao).rescheduleAllOnBoot()
            } finally {
                pending.finish()
                scope.cancel()
            }
        }

        val freshBoot = action == Intent.ACTION_BOOT_COMPLETED
        if (freshBoot) {
            prefs.userStopped = false // fresh boot = assistant may auto-start
        }
        // Start the pipeline only when the assistant is supposed to run:
        // onboarding finished AND (fresh boot OR the user never stopped it).
        val shouldStart = prefs.onboarded && (freshBoot || !prefs.userStopped)
        if (shouldStart) {
            JarvisForegroundService.explicitStart(context)
        }
    }
}
