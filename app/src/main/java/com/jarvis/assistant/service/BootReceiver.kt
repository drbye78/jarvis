package com.jarvis.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.assistant.tools.AlarmSchedulerProvider
import com.jarvis.assistant.tools.AlertPermissionReconciler
import com.jarvis.assistant.tools.canScheduleExactAlarms
import com.jarvis.assistant.util.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Reacts to boot / app update AND re-arms every persisted alert through the
 * unified scheduler: alarms always (dailies rolled past missed days) and
 * timers while still in the future — before the unified store timers vanished
 * on reboot entirely (M9/S3).
 *
 * The receiver no longer starts the assistant service itself. Since minSdk 29
 * (Android 10 / HarmonyOS 2.0 wall device) a foreground service started from
 * this always-background context would run with a silenced microphone
 * (Android 10 while-in-use rule), so the receiver posts the "tap to
 * activate" prompt instead; the user's tap provides the user-present start
 * the platform requires.
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
                // Re-arm through the shared scheduler (never a per-call-site
                // armer), then reconcile so exactness newly granted since the
                // alerts were armed is exploited. Alarms are armed even when
                // exact is unavailable — the armer degrades honestly.
                val scheduler = AlarmSchedulerProvider.get(context)
                scheduler.rescheduleAllOnBoot()
                AlertPermissionReconciler(
                    scheduler,
                    canScheduleExact = { canScheduleExactAlarms(context) },
                ).reconcile()
            } finally {
                pending.finish()
                scope.cancel()
            }
        }

        val freshBoot = action == Intent.ACTION_BOOT_COMPLETED
        if (freshBoot) {
            prefs.userStopped = false // fresh boot = assistant may auto-start
        }
        // Activate the pipeline only when the assistant is supposed to run:
        // onboarding finished AND (fresh boot OR the user never stopped it).
        //
        // Android 10 (minSdk 29): this receiver ALWAYS runs while the app is
        // in the background, and the while-in-use rule grants microphone
        // access only to foreground services started while the user is
        // present — a service started here would run with a SILENCED
        // microphone (AudioRecord returns zeros, no error) and the wake word
        // would never fire. Android 12+ goes further and restricts the
        // background FGS start itself. So instead of starting the service,
        // post the activation prompt: the user taps it (or the app icon),
        // the activity comes to the foreground, and the pipeline starts with
        // a guaranteed-working microphone.
        val shouldActivate = prefs.onboarded && (freshBoot || !prefs.userStopped)
        if (shouldActivate) {
            JarvisForegroundService.postActivationPrompt(context)
        }
    }
}
