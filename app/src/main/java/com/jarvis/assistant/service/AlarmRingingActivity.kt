package com.jarvis.assistant.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import com.jarvis.assistant.R
import com.jarvis.assistant.tools.AlarmReceiver
import com.jarvis.assistant.tools.AlarmRinger
import com.jarvis.assistant.tools.RingCoordinator
import com.jarvis.assistant.tools.RingCoordinatorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Full-screen alarm ringing experience (the original AlarmReceiver only
 * logged a line). Shows over the lock screen with the screen lit, loops the
 * alarm sound, vibrates, and offers Dismiss / Snooze (+10 min).
 *
 * REMEDIATION_PLAN P3.1/P3.5: this activity is now PURELY the ring UI. The
 * terminal DB transition and the ringing notification are owned by
 * [AlarmReceiver]'s ring-begin ([RingCoordinator]) — the activity used to
 * RE-POST the notification here (the duplicate) and re-run `onFired` from its
 * own ioScope, which was the "ring lost if the activity never launched" bug.
 *
 * Every action is token-guarded (P3.5): the durable ring-session token from
 * the intent must match the live session, so a notification left over from an
 * earlier ring cannot dismiss/snooze a newer one. Back = dismiss — backing out
 * never strands an un-dismissable ring.
 */
class AlarmRingingActivity : Activity() {

    private var alertId = -1
    private var label = ""
    private var isTimer = false
    private var ringToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!applyIntent(intent)) {
            // No alert identity: nothing to ring. Do not show a broken UI.
            finish()
            return
        }

        setContentView(R.layout.activity_alarm_ringing)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        render()

        findViewById<android.widget.Button>(R.id.btnDismiss).setOnClickListener {
            dismissAndFinish()
        }
        findViewById<android.widget.Button>(R.id.btnSnooze).setOnClickListener {
            snoozeAndFinish()
        }

        // The receiver posted THE notification (with Dismiss/Snooze actions and
        // the delete intent) before launching us; the ringer is the only part
        // of the ring this activity still owns.
        AlarmRinger.start(this)
    }

    /**
     * A new ring delivered to a live instance (FLAG_ACTIVITY_CLEAR_TOP /
     * single-top relaunch): refresh identity and restart the ringer for the
     * NEW session token. Without this the UI would keep acting on the old
     * token and every action would be (correctly) rejected as stale.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!applyIntent(intent)) {
            finish()
            return
        }
        render()
        AlarmRinger.start(this)
    }

    private fun applyIntent(incoming: Intent?): Boolean {
        val src = incoming ?: return false
        val id = src.getIntExtra(AlarmReceiver.EXTRA_ALERT_ID, -1)
        if (id < 0) return false
        alertId = id
        label = src.getStringExtra(AlarmReceiver.EXTRA_LABEL)
            ?: getString(R.string.default_alarm_label)
        isTimer = src.getBooleanExtra(AlarmReceiver.EXTRA_IS_TIMER, false)
        ringToken = src.getStringExtra(AlarmReceiver.EXTRA_RING_TOKEN)
        return true
    }

    private fun render() {
        findViewById<TextView>(R.id.alarmLabel).text = label
        findViewById<TextView>(R.id.alarmTime).text =
            if (isTimer) getString(R.string.timer_done) else currentTime()
    }

    private fun dismissAndFinish() {
        // Stop sound immediately (the DB delete is async on the app scope).
        AlarmRinger.stop(this)
        withCoordinator { it.dismiss(alertId, ringToken) }
        finish()
    }

    private fun snoozeAndFinish() {
        AlarmRinger.stop(this)
        withCoordinator { it.snooze(alertId, ringToken) }
        finish()
    }

    /** Backing out dismisses — never a silent, un-dismissable ring. */
    @Deprecated("Deprecated in API 33; still required for minSdk 29 devices.")
    override fun onBackPressed() {
        dismissAndFinish()
    }

    private fun withCoordinator(action: suspend (RingCoordinator) -> Boolean) {
        val coordinator = coordinator()
        ioScope.launch {
            runCatching { action(coordinator) }
                .onFailure { Timber.e(it, "Ring action failed for alert %d", alertId) }
        }
    }

    /**
     * The process-wide coordinator ([RingCoordinatorProvider]) — never built
     * per instance: a fresh one would fork the ring-session bookkeeping
     * (mirrors the [com.jarvis.assistant.tools.AlarmSchedulerProvider] rule).
     */
    private fun coordinator(): RingCoordinator = RingCoordinatorProvider.get(applicationContext)

    private fun currentTime(): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())

    override fun onDestroy() {
        AlarmRinger.stop(this, quiet = true)
        super.onDestroy()
    }

    companion object {
        /**
         * Application-lifetime scope shared by every ringing instance: a fast
         * dismiss destroys the activity before its launch dispatches, and the
         * token-guarded dismiss/snooze writes MUST still complete. Short-lived
         * DB writes only; never cancelled.
         */
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
