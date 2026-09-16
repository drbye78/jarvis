package com.jarvis.assistant.tools

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.data.AlertDao
import com.jarvis.assistant.data.FiredResolution
import com.jarvis.assistant.data.ScheduledAlertEntity
import com.jarvis.assistant.util.NotificationIds
import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

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
 * REMEDIATION_PLAN P3.1: pure decision logic for exact-alarm availability.
 * NO Android imports: [sdkInt] and the `AlarmManager.canScheduleExactAlarms()`
 * result (null = not queried, API < 31) come in as plain values so the
 * degrade-vs-exact matrix is JVM-testable without Robolectric.
 *
 * SCHEDULE_EXACT_ALARM is declared in the manifest (normal permission:
 * granted by default on API 29–30; on API 31+ it may be revoked in Settings).
 * `setAlarmClock` (alarms) is exempt from the permission and always exact —
 * only TIMER's `setExactAndAllowWhileIdle` can degrade.
 */
object ExactAlarmPolicy {

    sealed interface TimerSchedule {
        /** Permission available (declared+granted): exact, Doze-proof alarm path. */
        data object ExactAllowWhileIdle : TimerSchedule

        /**
         * DENIED (API 31+, revoked in Settings) or unusable: honest inexact
         * degradation — a bounded-latency `setWindow` window instead of the
         * exact call, plus a user-visible one-time notification from the
         * armer so the user can grant Alarms & reminders.
         */
        data object Inexact : TimerSchedule
    }

    /** Window granted to a degraded timer: keeps "in ~10 min" roughly honest. */
    const val INEXACT_WINDOW_MS = 10L * 60 * 1000L

    /**
     * null [canScheduleExactAlarms] = API < 31 (the OS call does not exist);
     * the manifest-declared normal permission is granted there, so exact.
     */
    fun timerSchedule(sdkInt: Int, canScheduleExactAlarms: Boolean?): TimerSchedule =
        if (sdkInt >= 31 && canScheduleExactAlarms != true) {
            TimerSchedule.Inexact
        } else {
            TimerSchedule.ExactAllowWhileIdle
        }

    /** Separate low-importance lane: never competes with the alarm channel. */
    const val DEGRADE_CHANNEL_ID = "jarvis_alarm_hint"

    /**
     * The «grant it» deep-link (`Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`)
     * exists only on API 31+: below that the manifest-declared normal
     * permission cannot be revoked, so the degrade note carries no intent
     * there. (The degrade path itself is only reachable on 31+ — this is the
     * honest guard for the contentIntent, not a second opinion on degrading.)
     */
    fun shouldOfferExactAlarmSettings(sdkInt: Int): Boolean = sdkInt >= 31
}

/**
 * Thread-safe one-shot permit: exactly ONE [acquire] across the process ever
 * returns true. Used by [SystemAlertArmer] so the exact-alarm degrade note is
 * posted once per armer lifetime — and because the armer is a process singleton
 * ([AlarmSchedulerProvider], graph-installed from P2 on), once per app run,
 * NOT once per re-arm/toggle.
 */
class OneShotGate {
    private val taken = AtomicBoolean(false)

    /** True for the first caller only; every later caller gets false. */
    fun acquire(): Boolean = taken.compareAndSet(false, true)
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
 * Request codes are a DIFFERENT namespace from notification ids — the
 * ringing notification id is row-id-banded via
 * [com.jarvis.assistant.util.NotificationIds.ringingId] (decision #3);
 * these arm/cancel codes stay raw.
 */
class SystemAlertArmer(private val context: Context) : AlertArmer {

    private fun fireIntent(id: Int, kind: String, label: String): Intent =
        Intent(context, AlarmReceiver::class.java).apply {
            action =
                if (kind == ScheduledAlertEntity.KIND_TIMER) {
                    AlarmReceiver.ACTION_TIMER_FIRED
                } else {
                    AlarmReceiver.ACTION_ALARM_FIRED
                }
            putExtra(AlarmReceiver.EXTRA_ALERT_ID, id)
            putExtra(AlarmReceiver.EXTRA_LABEL, label)
        }

    private fun fireOperation(id: Int, kind: String, label: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id,
            fireIntent(id, kind, label),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun showOperation(id: Int, kind: String, label: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            id,
            Intent(context, com.jarvis.assistant.service.AlarmRingingActivity::class.java).apply {
                action = fireIntent(id, kind, label).action
                putExtra(AlarmReceiver.EXTRA_ALERT_ID, id)
                putExtra(AlarmReceiver.EXTRA_LABEL, label)
                putExtra(AlarmReceiver.EXTRA_IS_TIMER, kind == ScheduledAlertEntity.KIND_TIMER)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    override fun arm(id: Int, triggerAtMillis: Long, kind: String, label: String) {
        // Audit #12: null-safe lookups — a missing manager logs and skips
        // instead of crashing the scheduling call.
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: run {
                Timber.e("AlarmManager unavailable — alert %d NOT armed", id)
                return
            }
        val operation = fireOperation(id, kind, label)
        if (kind == ScheduledAlertEntity.KIND_TIMER) {
            when (ExactAlarmPolicy.timerSchedule(Build.VERSION.SDK_INT, exactAvailable(am))) {
                ExactAlarmPolicy.TimerSchedule.ExactAllowWhileIdle ->
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
                ExactAlarmPolicy.TimerSchedule.Inexact -> {
                    // P3.1 honest degradation: bounded-latency inexact window
                    // + a ONE-TIME user-visible notification (Timber alone
                    // would hide the degraded precision from the user).
                    Timber.w("Exact alarm permission denied — arming timer %d as inexact", id)
                    am.setWindow(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        ExactAlarmPolicy.INEXACT_WINDOW_MS,
                        operation,
                    )
                    noteInexactTimerOnce()
                }
            }
        } else {
            // setAlarmClock: the correct API for user-facing alarms — fires
            // reliably through Doze, shows the system alarm-clock indicator
            // and is exempt from SCHEDULE_EXACT_ALARM entirely.
            am.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAtMillis, showOperation(id, kind, label)),
                operation,
            )
        }
    }

    /**
     * null on API < 31 (the method does not exist; normal permission is
     * granted there). Catching NoSuchMethodError is not needed — the SDK
     * guard keeps the call compiled-in only on API 31+ devices where the
     * method is present.
     */
    private fun exactAvailable(am: AlarmManager): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else null

    /**
     * User-visible signal for the inexact degradation: once per armer
     * instance, NOT once per timer, so every timer/toggle does not spam a
     * notification. Call sites must share ONE armer
     * ([AlarmSchedulerProvider] today, AppGraph-owned from P2 — decision #10);
     * a per-call-site armer would silently re-arm this gate and re-post the
     * note on every toggle.
     *
     * The note carries a contentIntent to the system «Alarms & reminders»
     * grant screen (API 31+): asking for the permission in text without a way
     * to grant it was the audit finding — the tap now lands the user exactly
     * where the text points.
     */
    private val degradeNoteGate = OneShotGate()
    private fun noteInexactTimerOnce() {
        if (!degradeNoteGate.acquire()) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm == null) {
            Timber.e("NotificationManager unavailable — no exact-alarm degrade note posted")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    ExactAlarmPolicy.DEGRADE_CHANNEL_ID,
                    context.getString(R.string.channel_alarm_hint_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        // Odd OEM safety: the channel for a fixed low-importance lane can
        // still vanish; a failed note must not break the arm() call.
        try {
            val builder = NotificationCompat.Builder(context, ExactAlarmPolicy.DEGRADE_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.exact_alarm_degrade_title))
                .setContentText(context.getString(R.string.exact_alarm_degrade_note))
                .setSmallIcon(R.drawable.ic_mic)
                .setAutoCancel(true)
            exactAlarmSettingsIntent()?.let { builder.setContentIntent(it) }
            nm.notify(NotificationIds.ALARM_DEGRADE, builder.build())
        } catch (e: Exception) {
            Timber.e(e, "Failed to post exact-alarm degrade note")
        }
    }

    /**
     * PendingIntent to the exact-alarm grant screen for THIS package
     * (`Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` + `package:` data).
     * null when the OS has no such screen (API < 31 — [ExactAlarmPolicy]
     * .shouldOfferExactAlarmSettings) or an OEM ROM cannot resolve the action
     * (honest degrade: a text-only note is still better than a crash).
     */
    private fun exactAlarmSettingsIntent(): PendingIntent? {
        if (!ExactAlarmPolicy.shouldOfferExactAlarmSettings(Build.VERSION.SDK_INT)) return null
        return try {
            PendingIntent.getActivity(
                context,
                NotificationIds.ALARM_DEGRADE,
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } catch (e: Exception) {
            Timber.w(e, "Exact-alarm settings screen unavailable — degrade note posted without contentIntent")
            null
        }
    }

    override fun cancel(id: Int, kind: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: run {
                Timber.e("AlarmManager unavailable — alert %d cancel skipped", id)
                return
            }
        am.cancel(fireOperation(id, kind, ""))
    }
}

/**
 * Single authority for everything that rings (M9/S3/M3): persists rows in
 * `scheduled_alerts` and arms/cancels [AlarmManager] through [AlertArmer].
 * Request code == row id, so schedule/cancel parity is exact and codes are
 * unique by construction; the ringing NOTIFICATION id is that row id banded
 * through [com.jarvis.assistant.util.NotificationIds.ringingId] (decision #3)
 * so it can never intersect the assistant notification band 0..999.
 *
 * All mutations go through here; the ringing activity, BootReceiver and the
 * voice tools delegate instead of doing their own intent math.
 *
 * Construction: the primary constructor takes the DAO + an [AlertArmer]
 * (tests use fakes). Production code must NOT build one per call site —
 * the armer holds one-shot degradation state ([OneShotGate]) and the whole
 * scheduler is state-bearing; use the shared instance from
 * [AlarmSchedulerProvider]. AppGraph installs the graph-owned one from P2
 * (decision #10).
 */
class AndroidAlarmScheduler(
    private val dao: AlertDao,
    private val armer: AlertArmer,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /** Persists a new alert and arms it. The generated row id IS the request code. */
    suspend fun schedule(alert: ScheduledAlertEntity): ScheduledAlertEntity {
        val id = dao.insert(alert).toInt()
        val stored = alert.copy(id = id)
        armer.arm(id, stored.triggerAtMillis, stored.kind, stored.label)
        // Content-logging rule (AGENTS.md): user-set labels are DEBUG-only —
        // FileLoggingTree persists every INFO+ line, and LogScrubber cannot
        // catch this shape (rule 1 needs a `label:` key; rule 2 deliberately
        // skips single-quoted spans). The INFO line stays content-free.
        Timber.i("Alert scheduled: kind=%s id=%d at %d", stored.kind, stored.id, stored.triggerAtMillis)
        Timber.d("Alert scheduled label: %s", stored.label)
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

    /**
     * UI enable/disable toggle: re-arm on enable, disarm on disable.
     *
     * Lying-switch fix: `enabled=1` used to be persisted BEFORE the trigger
     * was computed, so re-enabling an expired one-shot left a row claiming
     * armed while nothing was scheduled (UI switch ON, nothing ever rings).
     * Atomicity fix (audit P1-D): the whole read → compute → write sequence
     * now runs inside [AlertDao.applyEnable]'s single transaction — a snooze
     * or a fire racing this toggle can no longer be silently clobbered by a
     * full-row update from a stale snapshot — and the arming value comes from
     * the transaction's own result, never from a pre-transaction read.
     * Arming stays OUTSIDE the transaction:
     * - an expired KIND_ALARM one-shot rolls its wall-clock time forward from
     *   [ScheduledAlertEntity.anchorTimeMillis] to the next future occurrence;
     * - an expired KIND_TIMER has no meaningful recurrence — the row is
     *   honestly persisted as DISABLED and nothing is armed.
     * The DB row must never claim armed while nothing is armed.
     */
    suspend fun setEnabled(id: Int, enabled: Boolean) {
        if (!enabled) {
            val alert = dao.byId(id) ?: return
            dao.setEnabled(id, false) // single atomic UPDATE
            armer.cancel(id, alert.kind)
            return
        }
        val spec = dao.applyEnable(id) { alert -> nextTriggerFor(alert, now()) } ?: return
        armer.arm(spec.id, spec.triggerAtMillis, spec.kind, spec.label)
    }

    /**
     * Called when an alert actually rang (M3). IDEMPOTENT, and invoked from
     * the ringing activity onCreate — NOT from button handlers — so back,
     * HOME, process death and the auto-timeout path all still re-arm daily
     * alarms.
     *
     * Atomicity fix (audit P1-D): the read, the idempotency guards and the
     * write now happen inside ONE [AlertDao.applyFired] transaction, so a
     * snooze racing this call cannot lose either write — whichever
     * transaction commits last fully defines the row, and the arm/cancel
     * below acts on THAT transaction's returned values, never on a snapshot
     * taken before it. Arming stays outside the transaction.
     *
     * - Daily alert: rolls the stored trigger forward to the next occurrence
     *   (from [ScheduledAlertEntity.anchorTimeMillis], immune to snooze
     *   drift) and re-arms. A second call sees an already-future trigger and
     *   does nothing → exactly one next occurrence, ever.
     * - One-shot (timer or single alarm): disables the row.
     */
    suspend fun onFired(id: Int) {
        when (
            val resolution = dao.applyFired(id, now()) { alert ->
                AlarmTimes.nextDailyOccurrence(alert.anchorTimeMillis, now())
            }
        ) {
            FiredResolution.NoOp -> Unit // gone / disabled / already re-armed
            is FiredResolution.OneShotDisabled ->
                armer.cancel(id, resolution.kind) // defensive cleanup of any stale operation
            is FiredResolution.DailyRearmed ->
                armer.arm(id, resolution.triggerAtMillis, resolution.kind, resolution.label)
        }
    }

    /**
     * Moves the ring [delayMillis] into the future (snooze); same request
     * code. The read + write are one [AlertDao.applySnooze] transaction and
     * the arming value is its result, so a concurrent [onFired] can neither
     * be clobbered by a stale-row update nor make this arm from a trigger the
     * DB no longer holds.
     */
    suspend fun snooze(id: Int, delayMillis: Long = DEFAULT_SNOOZE_MS) {
        val spec = dao.applySnooze(id, now() + delayMillis) ?: return
        armer.arm(spec.id, spec.triggerAtMillis, spec.kind, spec.label)
    }

    /**
     * Boot / package-replace re-arm (M9): enabled ALARMs are always armed
     * (dailies rolled forward past missed days); TIMERs only while their
     * trigger is still in the future — expired ones are disabled so they do
     * not linger as armed-able ghosts. Each row's roll-forward write goes
     * through the same [AlertDao.applyEnable] transaction as the UI toggle,
     * so a boot sweep racing a user edit cannot lose either write.
     */
    suspend fun rescheduleAllOnBoot() {
        var armed = 0
        for (alert in dao.all()) {
            if (!alert.enabled) continue
            val spec = dao.applyEnable(alert.id) { row -> nextTriggerFor(row, now()) } ?: continue
            armer.arm(spec.id, spec.triggerAtMillis, spec.kind, spec.label)
            armed++
        }
        Timber.i("Rescheduled %d alerts after boot", armed)
    }

    /**
     * Shared roll-forward policy for [setEnabled] and [rescheduleAllOnBoot]:
     * - daily (repeat or wall-clock) → next occurrence from the anchor;
     * - one-shot in the future → itself, untouched;
     * - EXPIRED one-shot ALARM → next wall-clock occurrence from the anchor
     *   (re-enabling yesterday's 07:00 single alarm means tomorrow 07:00 —
     *   the same "row must never claim armed while nothing is armed" rule
     *   as the daily case, so this rolls instead of disabling);
     * - EXPIRED one-shot TIMER → null: an elapsed countdown has no
     *   meaningful recurrence, so the transaction persists DISABLED honestly.
     */
    private fun nextTriggerFor(alert: ScheduledAlertEntity, nowMillis: Long): Long? =
        when {
            alert.repeatDaily ->
                // Use anchorTimeMillis so boot re-arm and setEnabled re-compute from
                // the original recurring time, not a potentially snoozed triggerAtMillis.
                AlarmTimes.nextDailyOccurrence(alert.anchorTimeMillis, nowMillis)
            alert.triggerAtMillis > nowMillis -> alert.triggerAtMillis
            alert.kind == ScheduledAlertEntity.KIND_ALARM ->
                AlarmTimes.nextDailyOccurrence(alert.anchorTimeMillis, nowMillis)
            else -> null
        }

    companion object {
        const val DEFAULT_SNOOZE_MS = 10 * 60 * 1000L
    }
}

/**
 * Process-wide shared [AndroidAlarmScheduler] for lanes that do not (yet)
 * receive one by construction — the alarms UI and the ringing activity
 * (audit P1-D / decision #10).
 *
 * Why a provider at all: constructing a scheduler per call-site (the old
 * `AndroidAlarmScheduler(dao, SystemAlertArmer(this))` in every click
 * listener) rebuilt the [SystemAlertArmer] every time, resetting its
 * one-shot degrade note ([OneShotGate]) — the «exact alarms denied»
 * notification then re-posted on every toggle. The provider guarantees ONE
 * armer per process, so the note is honest and the instance graph is stable.
 *
 * Lifecycle (interim until P2-A wires AppGraph):
 *  - [install] is the P2 hook: AppGraph builds the graph-owned scheduler
 *    (same DAO + armer it hands to FunctionRouter) and installs it once;
 *  - without an installed instance, [get] lazily memoizes ONE fallback
 *    scheduler bound to the application context — correct but not yet
 *    shared with the voice lane's armer.
 */
object AlarmSchedulerProvider {

    @Volatile
    private var installed: AndroidAlarmScheduler? = null

    @Volatile
    private var fallback: AndroidAlarmScheduler? = null

    /** The graph-owned scheduler, if [install] has run (P2 wiring; null until then). */
    val installedOrNull: AndroidAlarmScheduler? get() = installed

    /** P2-A hook: AppGraph installs THE graph-owned scheduler here. */
    fun install(scheduler: AndroidAlarmScheduler) {
        installed = scheduler
    }

    /** Test seam: drop both the installed and the memoized fallback instance. */
    fun clearForTests() {
        installed = null
        fallback = null
    }

    /**
     * The shared scheduler. [context] is only consulted on the very first
     * call before any [install] (to build the fallback); with an installed
     * graph instance it may be null — call sites that can hold a reference
     * should take the scheduler by constructor/parameter injection instead.
     */
    fun get(context: Context?): AndroidAlarmScheduler {
        installed?.let { return it }
        return fallback ?: synchronized(this) {
            fallback ?: run {
                val appContext = requireNotNull(context) {
                    "AlarmSchedulerProvider.get requires a Context before AppGraph installs the graph-owned scheduler"
                }.applicationContext
                AndroidAlarmScheduler(
                    com.jarvis.assistant.data.AppDatabase.getInstance(appContext).alarmDao(),
                    SystemAlertArmer(appContext),
                ).also { fallback = it }
            }
        }
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
                val label = intent.getStringExtra(EXTRA_LABEL) ?: context.getString(R.string.default_alarm_label)
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
                // Honest degradation (API 29+): a background activity launch
                // is silently BLOCKED when no launch window exists — the FSI
                // notification above is the guaranteed path, so this is
                // best-effort and must never crash the receiver.
                runCatching { context.startActivity(service) }
                    .onFailure {
                        // Content-free: the exception class name only.
                        Timber.d("Ringing activity fast path blocked: %s", it.javaClass.simpleName)
                    }
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

        /**
         * Notification identity for an alert's ringing notification (audit
         * #20 + remediation decision #3).
         *
         * The row id stays the AlarmManager request code AND the
         * full-screen-intent PendingIntent request code (parity by
         * construction with [SystemAlertArmer]'s arm/cancel — unique per
         * alert, stable across the receiver's post, the ringing activity's
         * re-post and its cancel). Only the NOTIFICATION id gains the band
         * offset ([NotificationIds.ringingId]): raw row ids would collide
         * with the assistant band's fixed ids 1–4 (FGS state/permission/
         * activation + the exact-alarm degrade note), silently overwriting or
         * cancelling the wrong notification.
         */
        fun ringingNotificationId(alertId: Int): Int = NotificationIds.ringingId(alertId)

        private fun postRingingNotification(
            context: Context,
            label: String,
            isTimer: Boolean,
            alertId: Int,
        ) {
            val notificationId = ringingNotificationId(alertId)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm == null) {
                Timber.e("NotificationManager unavailable — alarm notification not posted (id=%d)", notificationId)
                return
            }
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
            // Request code = the alert row id (NOT the banded notification id),
            // so FLAG_UPDATE_CURRENT updates only THIS alert's pending intent —
            // not every concurrent alarm's. Parity with AlarmManager codes.
            val fullScreen = PendingIntent.getActivity(
                context,
                NotificationIds.ringingRequestCode(alertId),
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            // Lockscreen privacy (audit P1-D): the user-set label is content
            // — VISIBILITY_PRIVATE keeps it off the locked screen while the
            // public version shows only generic «Alarm» text. The full-screen
            // intent still launches the real ringing UI immediately.
            val publicVersion = NotificationCompat.Builder(context, "jarvis_alarm")
                .setContentTitle(context.getString(R.string.alarm_notification_title))
                .setContentText(context.getString(R.string.alarm_public_text))
                .setSmallIcon(R.drawable.ic_mic)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .build()
            val notification = NotificationCompat.Builder(context, "jarvis_alarm")
                .setContentTitle(context.getString(R.string.alarm_notification_title))
                .setContentText(label)
                .setSmallIcon(R.drawable.ic_mic)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(fullScreen, true)
                .setOngoing(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .build()
            nm.notify(notificationId, notification)
        }
        const val EXTRA_ALERT_ID = "alert_id"
        const val EXTRA_LABEL = "label"
        const val EXTRA_IS_TIMER = "is_timer"
    }
}
