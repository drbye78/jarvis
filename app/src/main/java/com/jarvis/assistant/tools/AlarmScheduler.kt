package com.jarvis.assistant.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.data.AlertDao
import com.jarvis.assistant.data.ScheduledAlertEntity
import timber.log.Timber
import java.util.Calendar

/** Pure date logic, JVM-testable. */
object AlarmTimes {

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Next wall-clock occurrence of hour:minute at or after [nowMillis]. */
    fun nextOccurrence(hour: Int, minute: Int, nowMillis: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= nowMillis) add(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    /**
     * Next daily occurrence strictly after [nowMillis], rolling forward from
     * [previousTriggerMillis] in whole days. Rolling the stored trigger (not
     * recomputing a wall-clock time) keeps daily alarms self-healing after
     * reboots that skip several days. Fixed 24 h steps ignore DST — accepted
     * for this appliance profile.
     */
    fun nextDailyOccurrence(previousTriggerMillis: Long, nowMillis: Long): Long {
        var trigger = previousTriggerMillis
        while (trigger <= nowMillis) trigger += DAY_MS
        return trigger
    }

    /** Parses "HH:mm" (or "H:mm") into (hour, minute), null if invalid. */
    fun parseTime(text: String): Pair<Int, Int>? {
        val parts = text.trim().split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }
}

/**
 * Thin seam over [AlarmManager] so scheduling DECISIONS are unit-testable on
 * the JVM without instrumentation. Production implementation:
 * [SystemAlertArmer].
 */
interface AlertArmer {
    fun arm(id: Int, triggerAtMillis: Long, kind: String, label: String)
    fun cancel(id: Int, kind: String)
}

/**
 * Production armer. EVERY PendingIntent is built through the private helpers
 * below, so arm and cancel are structurally identical (same action, class,
 * request code and flags) — parity by construction, not convention (cf. M2).
 *
 * The request code is always the alert row id ([ScheduledAlertEntity.id],
 * Int, no narrowing): collision-free by construction. The old scheme of
 * arming timers with `TIMER_REQUEST_BASE + epoch-millis-truncated-to-Int`
 * (wrapping mod 2³², colliding under FLAG_UPDATE_CURRENT) is deleted.
 */
class SystemAlertArmer(private val context: Context) : AlertArmer {

    private fun fireIntent(id: Int, kind: String, label: String): Intent =
        Intent(context, AlarmReceiver::class.java).apply {
            action =
                if (kind == ScheduledAlertEntity.KIND_TIMER) AlarmReceiver.ACTION_TIMER_FIRED
                else AlarmReceiver.ACTION_ALARM_FIRED
            putExtra(AlarmReceiver.EXTRA_ALERT_ID, id)
            putExtra(AlarmReceiver.EXTRA_LABEL, label)
        }

    private fun fireOperation(id: Int, kind: String, label: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, id, fireIntent(id, kind, label),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun showOperation(id: Int, kind: String, label: String): PendingIntent =
        PendingIntent.getActivity(
            context, id,
            Intent(context, com.jarvis.assistant.service.AlarmRingingActivity::class.java).apply {
                action = fireIntent(id, kind, label).action
                putExtra(AlarmReceiver.EXTRA_ALERT_ID, id)
                putExtra(AlarmReceiver.EXTRA_LABEL, label)
                putExtra(AlarmReceiver.EXTRA_IS_TIMER, kind == ScheduledAlertEntity.KIND_TIMER)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    override fun arm(id: Int, triggerAtMillis: Long, kind: String, label: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val operation = fireOperation(id, kind, label)
        if (kind == ScheduledAlertEntity.KIND_TIMER) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        } else {
            // setAlarmClock: the correct API for user-facing alarms — fires
            // reliably through Doze and shows the system alarm-clock indicator.
            am.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAtMillis, showOperation(id, kind, label)),
                operation,
            )
        }
    }

    override fun cancel(id: Int, kind: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(fireOperation(id, kind, ""))
    }
}

/**
 * Single authority for everything that rings (M9/S3/M3): persists rows in
 * `scheduled_alerts` and arms/cancels [AlarmManager] through [AlertArmer].
 * Request code == row id, so schedule/cancel parity is exact and codes are
 * unique by construction.
 *
 * All mutations go through here; the ringing activity, BootReceiver and the
 * voice tools delegate instead of doing their own intent math.
 *
 * Construction: tests use the primary constructor with fakes; production
 * call sites use `AndroidAlarmScheduler(context, dao)` (kept as a secondary
 * constructor because FunctionRouter — another lane's file — calls it).
 */
class AndroidAlarmScheduler(
    private val dao: AlertDao,
    private val armer: AlertArmer,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    constructor(context: Context, dao: AlertDao) : this(dao, SystemAlertArmer(context))

    /** Persists a new alert and arms it. The generated row id IS the request code. */
    suspend fun schedule(alert: ScheduledAlertEntity): ScheduledAlertEntity {
        val id = dao.insert(alert).toInt()
        val stored = alert.copy(id = id)
        armer.arm(id, stored.triggerAtMillis, stored.kind, stored.label)
        Timber.i("Alert scheduled: %s '%s' at %d (id=%d)", stored.kind, stored.label, stored.triggerAtMillis, id)
        return stored
    }

    /** Clock-time alarm convenience (setAlarm tool + AlarmsActivity). */
    suspend fun schedule(label: String, hour: Int, minute: Int, repeatDaily: Boolean): ScheduledAlertEntity {
        val trigger = AlarmTimes.nextOccurrence(hour, minute, now())
        return schedule(
            ScheduledAlertEntity(
                kind = ScheduledAlertEntity.KIND_ALARM,
                label = label,
                triggerAtMillis = trigger,
                repeatDaily = repeatDaily,
            ),
        )
    }

    /**
     * Persists a TIMER row and arms it — timers survive reboot now (M9/S3);
     * previously they existed only inside AlarmManager and vanished on reboot.
     */
    suspend fun scheduleTimer(label: String, delayMillis: Long): ScheduledAlertEntity =
        schedule(
            ScheduledAlertEntity(
                kind = ScheduledAlertEntity.KIND_TIMER,
                label = label,
                triggerAtMillis = now() + delayMillis,
                repeatDaily = false,
            ),
        )

    /** Cancels the pending intent and deletes the row. */
    suspend fun cancel(id: Int) {
        dao.byId(id)?.let { armer.cancel(it.id, it.kind) }
        dao.delete(id)
    }

    /** UI enable/disable toggle: re-arm on enable, disarm on disable. */
    suspend fun setEnabled(id: Int, enabled: Boolean) {
        val alert = dao.byId(id) ?: return
        dao.setEnabled(id, enabled)
        if (!enabled) {
            armer.cancel(id, alert.kind)
            return
        }
        val trigger = nextTriggerFor(alert, now()) ?: return // expired one-shot stays disarmed
        if (trigger != alert.triggerAtMillis) {
            dao.update(alert.copy(triggerAtMillis = trigger))
        }
        armer.arm(id, trigger, alert.kind, alert.label)
    }

    /**
     * Called when an alert actually rang (M3). IDEMPOTENT, and invoked from
     * the ringing activity onCreate — NOT from button handlers — so back,
     * HOME, process death and the auto-timeout path all still re-arm daily
     * alarms.
     *
     * - Daily alert: rolls the stored trigger forward to the next occurrence
     *   and re-arms. A second call sees an already-future trigger and does
     *   nothing → exactly one next occurrence, ever.
     * - One-shot (timer or single alarm): disables the row.
     */
    suspend fun onFired(id: Int) {
        val alert = dao.byId(id) ?: return
        if (!alert.enabled) return // already handled by a previous onFired
        if (!alert.repeatDaily) {
            dao.setEnabled(id, false)
            armer.cancel(id, alert.kind) // defensive cleanup of any stale operation
            return
        }
        if (alert.triggerAtMillis > now()) return // already re-armed (idempotent)
        val next = AlarmTimes.nextDailyOccurrence(alert.triggerAtMillis, now())
        dao.update(alert.copy(triggerAtMillis = next))
        armer.arm(id, next, alert.kind, alert.label)
    }

    /** Moves the ring [delayMillis] into the future (snooze); same request code. */
    suspend fun snooze(id: Int, delayMillis: Long = DEFAULT_SNOOZE_MS) {
        val alert = dao.byId(id) ?: return
        val trigger = now() + delayMillis
        dao.update(alert.copy(triggerAtMillis = trigger, enabled = true))
        armer.arm(id, trigger, alert.kind, alert.label)
    }

    /**
     * Boot / package-replace re-arm (M9): enabled ALARMs are always armed
     * (dailies rolled forward past missed days); TIMERs only while their
     * trigger is still in the future — expired ones are disabled so they do
     * not linger as armed-able ghosts.
     */
    suspend fun rescheduleAllOnBoot() {
        var armed = 0
        for (alert in dao.all()) {
            if (!alert.enabled) continue
            val trigger = nextTriggerFor(alert, now())
            if (trigger == null) {
                dao.setEnabled(alert.id, false) // expired one-shot / timer
                continue
            }
            if (trigger != alert.triggerAtMillis) {
                dao.update(alert.copy(triggerAtMillis = trigger))
            }
            armer.arm(alert.id, trigger, alert.kind, alert.label)
            armed++
        }
        Timber.i("Rescheduled %d alerts after boot", armed)
    }

    /** Shared roll-forward policy: daily → next occurrence; one-shot → itself while future. */
    private fun nextTriggerFor(alert: ScheduledAlertEntity, nowMillis: Long): Long? =
        if (alert.repeatDaily) {
            AlarmTimes.nextDailyOccurrence(alert.triggerAtMillis, nowMillis)
        } else if (alert.triggerAtMillis > nowMillis) {
            alert.triggerAtMillis
        } else {
            null
        }

    companion object {
        const val DEFAULT_SNOOZE_MS = 10 * 60 * 1000L
    }
}

/**
 * Fired by AlarmManager when an alarm or timer triggers. Starts the ringing
 * experience (full-screen activity + sound + vibration); the activity owns
 * re-arm/disable via AndroidAlarmScheduler.onFired.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ALARM_FIRED, ACTION_TIMER_FIRED -> {
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "Будильник"
                val isTimer = intent.action == ACTION_TIMER_FIRED
                val alertId = intent.getIntExtra(EXTRA_ALERT_ID, -1)
                // N2: post the full-screen-intent notification from the receiver
                // itself, so the alarm still rings even when the foreground
                // service is stopped and a background startActivity is blocked
                // (timers use setExactAndAllowWhileIdle, which grants no launch
                // window). The FSI launches the activity over the lock screen.
                postRingingNotification(context, label, isTimer, alertId)
                // Fast path: bring the activity up directly when already foreground.
                val service = Intent(context, com.jarvis.assistant.service.AlarmRingingActivity::class.java).apply {
                    putExtra(EXTRA_LABEL, label)
                    putExtra(EXTRA_IS_TIMER, isTimer)
                    putExtra(EXTRA_ALERT_ID, alertId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(service)
            }

            ACTION_SNOOZE -> {
                // Handled by the ringing activity itself (it cancels its own ringer).
            }

            ACTION_DISMISS -> {
                AlarmRinger.stop(context)
            }
        }
    }

    companion object {
        const val ACTION_ALARM_FIRED = "com.jarvis.assistant.ALARM_FIRED"
        const val ACTION_TIMER_FIRED = "com.jarvis.assistant.TIMER_FIRED"
        const val ACTION_SNOOZE = "com.jarvis.assistant.ALARM_SNOOZE"
        const val ACTION_DISMISS = "com.jarvis.assistant.ALARM_DISMISS"

        private const val ALARM_NOTIFICATION_ID = 500

        private fun postRingingNotification(
            context: Context,
            label: String,
            isTimer: Boolean,
            alertId: Int,
        ) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        "jarvis_alarm",
                        context.getString(R.string.channel_alarm),
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            val activityIntent = Intent(context, com.jarvis.assistant.service.AlarmRingingActivity::class.java).apply {
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_IS_TIMER, isTimer)
                putExtra(EXTRA_ALERT_ID, alertId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val fullScreen = PendingIntent.getActivity(
                context, 0, activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, "jarvis_alarm")
                .setContentTitle(context.getString(R.string.alarm_notification_title))
                .setContentText(label)
                .setSmallIcon(R.drawable.ic_mic)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(fullScreen, true)
                .setOngoing(true)
                .build()
            nm.notify(ALARM_NOTIFICATION_ID, notification)
        }
        const val EXTRA_ALERT_ID = "alert_id"
        const val EXTRA_LABEL = "label"
        const val EXTRA_IS_TIMER = "is_timer"
    }
}
