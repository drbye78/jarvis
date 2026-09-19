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
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.data.AlertArmSpec
import com.jarvis.assistant.data.AlertDao
import com.jarvis.assistant.data.ClockDomain
import com.jarvis.assistant.data.FiredResolution
import com.jarvis.assistant.data.ScheduledAlertEntity
import com.jarvis.assistant.util.NotificationIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
 * `setAlarmClock` is NOT exempt from that permission at target 31+: it needs
 * SCHEDULE_EXACT_ALARM exactly like `setExact*`, and revoking the permission
 * deletes every alarm armed through setExact, setExactAndAllowWhileIdle AND
 * setAlarmClock. So BOTH the alarm and the timer path can degrade; [planFor]
 * is a per-kind delivery plan rather than a timer-only schedule.
 */
object ExactAlarmPolicy {

    /** Per-kind exact-delivery plan; [Degraded] carries why it degraded. */
    sealed interface AlertDeliveryPlan {
        /** `setAlarmClock` — user-facing alarm, Doze-proof + system indicator. */
        data object AlarmClock : AlertDeliveryPlan

        /** `setExactAndAllowWhileIdle` — exact, Doze-proof timer path. */
        data object ExactAllowWhileIdle : AlertDeliveryPlan

        /** Exact delivery unavailable; arm an inexact fallback + tell the user. */
        data class Degraded(val reason: DegradeReason) : AlertDeliveryPlan
    }

    /** Why the plan could not use an exact API. */
    enum class DegradeReason {
        /** API 31+ and `canScheduleExactAlarms()` is not true. */
        PERMISSION_DENIED,

        /**
         * The OS rejected `setAlarmClock` with a [SecurityException] despite
         * the permission query (revoked between query and call, or an OEM
         * ROM that ignores the declared permission).
         */
        ALARM_CLOCK_REJECTED,
    }

    /** Window granted to a degraded timer: keeps "in ~10 min" roughly honest. */
    const val INEXACT_WINDOW_MS = 10L * 60 * 1000L

    /**
     * Per-kind plan. null [canScheduleExactAlarms] on API < 31 means the OS
     * call does not exist; the manifest-declared normal permission is granted
     * there, so exact. On API 31+ anything other than `true` (false OR the
     * defensive null) degrades — including alarms, which are NOT exempt.
     */
    fun planFor(kind: String, sdkInt: Int, canScheduleExactAlarms: Boolean?): AlertDeliveryPlan =
        when {
            sdkInt >= 31 && canScheduleExactAlarms != true ->
                AlertDeliveryPlan.Degraded(DegradeReason.PERMISSION_DENIED)
            kind == ScheduledAlertEntity.KIND_TIMER ->
                AlertDeliveryPlan.ExactAllowWhileIdle
            else ->
                AlertDeliveryPlan.AlarmClock
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
 * Current SCHEDULE_EXACT_ALARM availability. True below API 31 (the
 * manifest-declared normal permission cannot be revoked there) and the system
 * query on API 31+. A missing AlarmManager is honestly reported as
 * unavailable rather than crashing — `as?` per the project rule.
 */
fun canScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
    return am.canScheduleExactAlarms()
}

/**
 * T13: pure decision for an alert discovered OVERDUE (boot / re-arm sweep).
 * NO Android imports — the overdue delta and row fields come in as plain
 * values so the ring-vs-roll-forward-vs-disable matrix is JVM-testable.
 *
 * Within [OVERDUE_GRACE_MS] the alert is close enough to its trigger that
 * ringing now is honest. Beyond grace a one-shot alarm has missed its
 * wall-clock moment and is rolled forward to the next occurrence, while a
 * one-shot timer has no meaningful recurrence and is disabled with a notice.
 * Anything else (daily alarms, unknown kinds) rings.
 */
object OverdueAlertPolicy {

    sealed interface OverdueDecision {
        /** Fire it now: the overdue gap is within the honest grace window. */
        data object Ring : OverdueDecision

        /** One-shot alarm missed its moment: re-arm the next occurrence. */
        data object RollForward : OverdueDecision

        /** One-shot timer elapsed long ago: disable the row + tell the user. */
        data object DisableWithNotice : OverdueDecision
    }

    /** Beyond this the alert is no longer "just a little late". */
    const val OVERDUE_GRACE_MS = 15L * 60 * 1000L

    fun decide(overdueMillis: Long, kind: String, repeatDaily: Boolean): OverdueDecision =
        when {
            overdueMillis <= OVERDUE_GRACE_MS -> OverdueDecision.Ring
            kind == ScheduledAlertEntity.KIND_ALARM && !repeatDaily -> OverdueDecision.RollForward
            kind == ScheduledAlertEntity.KIND_TIMER -> OverdueDecision.DisableWithNotice
            else -> OverdueDecision.Ring
        }
}

/**
 * Thread-safe one-shot permit: exactly ONE [acquire] across the process ever
 * returns true. Used by [SystemAlertArmer] so the exact-alarm degrade note is
 * posted once per armer lifetime — and because the armer is a process singleton
 * ([AlarmSchedulerProvider], graph-installed by AppGraph), once per app run,
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
    /**
     * Arms the alert described by the row. The row carries its own clock
     * domain ([ClockDomain.ELAPSED] timers vs [ClockDomain.RTC] alarms), so
     * the armer never infers the clock from the kind.
     */
    fun arm(alert: ScheduledAlertEntity)
    fun cancel(id: Int, kind: String)
}

/**
 * The AlarmManager clock space an alert row is armed in, plus the raw trigger
 * value that space expects. Pure and JVM-testable so the elapsed-vs-RTC
 * mapping is pinned without an [AlarmManager].
 */
data class AlertArmClock(val alarmType: Int, val triggerAtMillis: Long)

/**
 * Duration timers are anchored to the ELAPSED clock (immune to wall-clock
 * changes during a boot session); alarms stay on RTC. The type is chosen from
 * the persisted [ScheduledAlertEntity.clockDomain], never from the kind.
 */
fun alertArmClockFor(alert: ScheduledAlertEntity): AlertArmClock =
    if (alert.clockDomain == ClockDomain.ELAPSED) {
        AlertArmClock(AlarmManager.ELAPSED_REALTIME_WAKEUP, alert.anchorElapsedMillis)
    } else {
        AlertArmClock(AlarmManager.RTC_WAKEUP, alert.triggerAtMillis)
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

    private fun fireIntent(id: Int, kind: String, label: String, triggerAtMillis: Long): Intent =
        Intent(context, AlarmReceiver::class.java).apply {
            action =
                if (kind == ScheduledAlertEntity.KIND_TIMER) {
                    AlarmReceiver.ACTION_TIMER_FIRED
                } else {
                    AlarmReceiver.ACTION_ALARM_FIRED
                }
            putExtra(AlarmReceiver.EXTRA_ALERT_ID, id)
            putExtra(AlarmReceiver.EXTRA_LABEL, label)
            // Fire identity (P3.2): the receiver hands this back to
            // AlertDao.applyFired so a snooze/edit racing the fire cannot be
            // clobbered by a stale delivery.
            putExtra(AlarmReceiver.EXTRA_TRIGGER_AT, triggerAtMillis)
        }

    private fun fireOperation(id: Int, kind: String, label: String, triggerAtMillis: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id,
            fireIntent(id, kind, label, triggerAtMillis),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun showOperation(id: Int, kind: String, label: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            id,
            Intent(context, com.jarvis.assistant.service.AlarmRingingActivity::class.java).apply {
                action = fireIntent(id, kind, label, 0L).action
                putExtra(AlarmReceiver.EXTRA_ALERT_ID, id)
                putExtra(AlarmReceiver.EXTRA_LABEL, label)
                putExtra(AlarmReceiver.EXTRA_IS_TIMER, kind == ScheduledAlertEntity.KIND_TIMER)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    override fun arm(alert: ScheduledAlertEntity) {
        // Audit #12: null-safe lookups — a missing manager logs and skips
        // instead of crashing the scheduling call.
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: run {
                Timber.e("AlarmManager unavailable — alert %d NOT armed", alert.id)
                return
            }
        // Clock domain comes from the row: ELAPSED timers survive wall-clock
        // changes; RTC alarms keep wall-clock semantics. The kind only decides
        // WHICH exact API is used, never the clock space.
        val clock = alertArmClockFor(alert)
        val operation = fireOperation(alert.id, alert.kind, alert.label, alert.triggerAtMillis)
        when (val plan = ExactAlarmPolicy.planFor(alert.kind, Build.VERSION.SDK_INT, exactAvailable(am))) {
            ExactAlarmPolicy.AlertDeliveryPlan.ExactAllowWhileIdle ->
                am.setExactAndAllowWhileIdle(clock.alarmType, clock.triggerAtMillis, operation)

            ExactAlarmPolicy.AlertDeliveryPlan.AlarmClock -> {
                // setAlarmClock: the correct API for user-facing alarms — fires
                // reliably through Doze and shows the system alarm-clock
                // indicator. It is NOT exempt from SCHEDULE_EXACT_ALARM at
                // target 31+: revoked permission rejects it (and deletes
                // alarms armed via it), so never let that crash scheduling.
                try {
                    am.setAlarmClock(
                        AlarmManager.AlarmClockInfo(
                            clock.triggerAtMillis,
                            showOperation(alert.id, alert.kind, alert.label),
                        ),
                        operation,
                    )
                } catch (e: SecurityException) {
                    // Race: permission revoked between the query and the call,
                    // or an OEM ROM that rejects it anyway. Degrade, don't die.
                    Timber.w(e, "setAlarmClock rejected for alarm %d — arming inexact", alert.id)
                    am.setAndAllowWhileIdle(clock.alarmType, clock.triggerAtMillis, operation)
                    noteDegradedOnce()
                }
            }

            is ExactAlarmPolicy.AlertDeliveryPlan.Degraded -> {
                // P3.1 honest degradation: bounded-latency inexact window for
                // timers, `setAndAllowWhileIdle` for alarms (no exact-alarm
                // permission needed for either) + a ONE-TIME user-visible
                // notification (Timber alone would hide the degraded precision).
                Timber.w("Exact alarm unavailable (%s) — arming %s %d as inexact", plan.reason, alert.kind, alert.id)
                if (alert.kind == ScheduledAlertEntity.KIND_TIMER) {
                    am.setWindow(
                        clock.alarmType,
                        clock.triggerAtMillis,
                        ExactAlarmPolicy.INEXACT_WINDOW_MS,
                        operation,
                    )
                } else {
                    am.setAndAllowWhileIdle(clock.alarmType, clock.triggerAtMillis, operation)
                }
                noteDegradedOnce()
            }
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
     * User-visible signal for the inexact degradation (timer OR alarm): once
     * per armer instance, NOT once per alert, so every timer/alarm/toggle does
     * not spam a notification. Call sites must share ONE armer
     * ([AlarmSchedulerProvider], AppGraph-owned — decision #10);
     * a per-call-site armer would silently re-arm this gate and re-post the
     * note on every toggle.
     *
     * The note carries a contentIntent to the system «Alarms & reminders»
     * grant screen (API 31+): asking for the permission in text without a way
     * to grant it was the audit finding — the tap now lands the user exactly
     * where the text points.
     */
    private val degradeNoteGate = OneShotGate()
    private fun noteDegradedOnce() {
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
        // cancel never delivers; 0L is a placeholder for the fire-identity extra.
        am.cancel(fireOperation(id, kind, "", 0L))
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
 * [AlarmSchedulerProvider]; AppGraph installs the graph-owned one at
 * construction (decision #10, P2-A wired).
 */
class AndroidAlarmScheduler(
    private val dao: AlertDao,
    private val armer: AlertArmer,
    private val now: () -> Long = { System.currentTimeMillis() },
    /**
     * ELAPSED clock source. Timers are anchored here so a wall-clock change
     * during a boot session cannot stretch or collapse a countdown. Injected
     * so the elapsed-vs-RTC arming decisions are JVM-testable (the real
     * `SystemClock` is an unmocked stub in unit tests).
     */
    private val elapsedNow: () -> Long = { SystemClock.elapsedRealtime() },
) {

    /**
     * Persists a new alert and arms it. The generated row id IS the request code.
     * Non-timer rows are pinned to the RTC domain; the timer row's ELAPSED
     * anchor is set by [scheduleTimer] before it reaches here.
     */
    suspend fun schedule(alert: ScheduledAlertEntity): ScheduledAlertEntity {
        val toStore = if (alert.kind == ScheduledAlertEntity.KIND_TIMER) {
            alert
        } else {
            alert.copy(clockDomain = ClockDomain.RTC, armedElapsedMillis = elapsedNow())
        }
        val id = dao.insert(toStore).toInt()
        val stored = toStore.copy(id = id)
        armer.arm(stored)
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
     *
     * Both clock domains are computed at arm time: the countdown is anchored
     * to the ELAPSED clock (its end is `elapsedNow() + delay`), while
     * [ScheduledAlertEntity.triggerAtMillis] stays a wall-clock value for
     * display and overdue checks.
     */
    suspend fun scheduleTimer(label: String, delayMillis: Long): ScheduledAlertEntity {
        val elapsed = elapsedNow()
        return schedule(
            ScheduledAlertEntity(
                kind = ScheduledAlertEntity.KIND_TIMER,
                label = label,
                triggerAtMillis = now() + delayMillis,
                repeatDaily = false,
                clockDomain = ClockDomain.ELAPSED,
                anchorElapsedMillis = elapsed + delayMillis,
                armedElapsedMillis = elapsed,
            ),
        )
    }

    /**
     * Cancels the pending intent and deletes the row. ALSO stops a live ring
     * for that alert (REMEDIATION_PLAN P3.9): the voice/UI cancel tools used
     * to delete the DB row while the ringer kept sounding and the notification
     * stayed up. The ring stop is owned by [RingCoordinatorProvider].
     */
    suspend fun cancel(id: Int) {
        dao.byId(id)?.let { armer.cancel(it.id, it.kind) }
        dao.delete(id)
        RingCoordinatorProvider.endLiveRingForAlert(id)
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
        armer.arm(spec.toAlert())
    }

    /**
     * Called when an alert actually rang (M3). IDEMPOTENT, and invoked from
     * [AlarmReceiver]'s ring-begin (REMEDIATION_PLAN P3.1) — NOT from the
     * ringing activity and NOT from button handlers — so the terminal DB
     * transition survives "the activity never launched or was killed".
     *
     * Atomicity fix (audit P1-D): the read, the idempotency guards and the
     * write now happen inside ONE [AlertDao.applyFired] transaction, so a
     * snooze racing this call cannot lose either write — whichever
     * transaction commits last fully defines the row, and the arm/cancel
     * below acts on THAT transaction's returned values, never on a snapshot
     * taken before it. Arming stays outside the transaction.
     *
     * Fire-identity guard (P3.2): [firedTriggerMillis] is the trigger the
     * delivered broadcast was armed for; a non-daily row whose stored trigger
     * no longer equals it (snoozed/edited concurrently) is left alone.
     *
     * - Daily alert: rolls the stored trigger forward to the next occurrence
     *   (from [ScheduledAlertEntity.anchorTimeMillis], immune to snooze
     *   drift) and re-arms. A second call sees an already-future trigger and
     *   does nothing → exactly one next occurrence, ever.
     * - One-shot (timer or single alarm): disables the row.
     *
     * Returns the [FiredResolution] so the ring-begin can distinguish a live
     * fire from a stale/snoozed one and suppress the ring accordingly (P3.2).
     */
    suspend fun onFired(id: Int, firedTriggerMillis: Long): FiredResolution =
        when (
            val resolution = dao.applyFired(id, firedTriggerMillis, now()) { alert ->
                AlarmTimes.nextDailyOccurrence(alert.anchorTimeMillis, now())
            }
        ) {
            FiredResolution.NoOp -> resolution // gone / disabled / stale fire / already re-armed
            is FiredResolution.OneShotDisabled -> {
                armer.cancel(id, resolution.kind) // defensive cleanup of any stale operation
                resolution
            }
            is FiredResolution.DailyRearmed -> {
                armer.arm(resolution.toAlert())
                resolution
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
        if (spec.clockDomain == ClockDomain.ELAPSED) {
            // The wall-clock trigger moved but an ELAPSED timer is armed from
            // its elapsed anchor, so move that anchor with it (same delay),
            // otherwise the armer would reread the old, already-passed anchor.
            val elapsed = elapsedNow()
            val rearmed = spec.toAlert().copy(
                anchorElapsedMillis = elapsed + delayMillis,
                armedElapsedMillis = elapsed,
            )
            dao.update(rearmed)
            armer.arm(rearmed)
        } else {
            armer.arm(spec.toAlert())
        }
    }

    /**
     * Boot / package-replace re-arm (M9): enabled ALARMs are always armed
     * (dailies rolled forward past missed days); TIMERs only while their
     * wall-clock trigger is still in the future — expired ones are disabled so
     * they do not linger as armed-able ghosts. Each alarm's roll-forward write
     * goes through the same [AlertDao.applyEnable] transaction as the UI
     * toggle, so a boot sweep racing a user edit cannot lose either write.
     *
     * Reboot resets the ELAPSED clock, so a timer's stored
     * [ScheduledAlertEntity.anchorElapsedMillis] is invalid here. The remaining
     * wall-clock delta is re-anchored to the fresh boot-relative clock
     * (`elapsedNow() + remaining`); an already-overdue timer is disabled, never
     * armed into the past.
     */
    suspend fun rescheduleAllOnBoot(): Int {
        var armed = 0
        for (alert in dao.all()) {
            if (!alert.enabled) continue
            if (alert.kind == ScheduledAlertEntity.KIND_TIMER) {
                val remaining = alert.triggerAtMillis - now()
                if (remaining <= 0) {
                    // Existing expired-timer behavior: honestly persist DISABLED.
                    dao.applyEnable(alert.id) { null }
                    continue
                }
                val elapsed = elapsedNow()
                val rearmed = alert.copy(
                    clockDomain = ClockDomain.ELAPSED,
                    anchorElapsedMillis = elapsed + remaining,
                    armedElapsedMillis = elapsed,
                )
                dao.update(rearmed)
                armer.arm(rearmed)
                armed++
                continue
            }
            val spec = dao.applyEnable(alert.id) { row -> nextTriggerFor(row, now()) } ?: continue
            armer.arm(spec.toAlert())
            armed++
        }
        Timber.i("Rescheduled %d alerts after boot", armed)
        return armed
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
 * Re-arms every enabled alert after the SCHEDULE_EXACT_ALARM state changes.
 * At target 31+ the OS DELETES every alarm/timer armed through `setExact*`,
 * `setExactAndAllowWhileIdle` and `setAlarmClock` when the permission is
 * revoked, and the revoke/grant broadcast is not reliably delivered — so
 * [reconcile] is invoked from three independent hooks (boot, the permission
 * broadcast, and app foreground).
 *
 * While exactness is unavailable it returns WITHOUT arming: the armer's own
 * degrade path already re-arms inexact on the next real scheduling call, and
 * forcing a sweep here would only throw again.
 */
class AlertPermissionReconciler(
    private val scheduler: AndroidAlarmScheduler,
    private val canScheduleExact: () -> Boolean,
) {
    suspend fun reconcile() {
        if (!canScheduleExact()) {
            Timber.i("Exact-alarm reconciliation skipped: exact-alarm permission unavailable")
            return
        }
        val count = scheduler.rescheduleAllOnBoot()
        Timber.i("Exact-alarm reconciliation re-armed %d alerts", count)
    }
}

/**
 * Hook A: Android 12+ (S) broadcast sent when SCHEDULE_EXACT_ALARM is
 * granted after a revoke. Delivery is not guaranteed, so this is one of three
 * reconciliation hooks (see [AlertPermissionReconciler]). It handles ONLY the
 * permission-state action and resolves the shared scheduler through
 * [AlarmSchedulerProvider] so no per-call-site armer is forked.
 */
class ExactAlarmPermissionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return
        // goAsync pattern copied from BootReceiver: the scope is cancelled
        // after pending.finish() so it never outlives the receiver's window.
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val scheduler = AlarmSchedulerProvider.get(context)
                AlertPermissionReconciler(
                    scheduler,
                    canScheduleExact = { canScheduleExactAlarms(context) },
                ).reconcile()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Exact-alarm permission reconciliation failed")
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }
}

/**
 * Rebuilds the armable row from a transaction's [AlertArmSpec]. The clock
 * domain and elapsed anchor ride along so a re-enabled/snoozed ELAPSED timer
 * is armed on the elapsed clock, not inferred back to RTC.
 */
private fun AlertArmSpec.toAlert(): ScheduledAlertEntity = ScheduledAlertEntity(
    id = id,
    kind = kind,
    label = label,
    triggerAtMillis = triggerAtMillis,
    clockDomain = clockDomain,
    anchorElapsedMillis = anchorElapsedMillis,
    armedElapsedMillis = armedElapsedMillis,
)

/** Rebuilds the armable row from a daily roll-forward resolution. */
private fun FiredResolution.DailyRearmed.toAlert(): ScheduledAlertEntity = ScheduledAlertEntity(
    id = id,
    kind = kind,
    label = label,
    triggerAtMillis = triggerAtMillis,
    clockDomain = clockDomain,
    anchorElapsedMillis = anchorElapsedMillis,
    armedElapsedMillis = armedElapsedMillis,
)

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
 * Lifecycle (P2-A wired):
 *  - AppGraph builds the graph-owned scheduler (same DAO + armer it hands
 *    to FunctionRouter) and calls [install] at construction — every graph
 *    (re)build re-installs, last graph wins;
 *  - before any graph exists (e.g. a boot alarm with the service not yet
 *    started), [get] lazily memoizes ONE fallback scheduler bound to the
 *    application context; once a graph installs, the fallback is superseded.
 */
object AlarmSchedulerProvider {

    @Volatile
    private var installed: AndroidAlarmScheduler? = null

    @Volatile
    private var fallback: AndroidAlarmScheduler? = null

    /** The graph-owned scheduler, if [install] has run (null before any graph). */
    val installedOrNull: AndroidAlarmScheduler? get() = installed

    /** Called by AppGraph at construction with THE graph-owned scheduler. */
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
 * Fired by AlarmManager when an alarm or timer triggers (REMEDIATION_PLAN
 * P3.1). The receiver OWNS the terminal transition: it `goAsync()`es and runs
 * [RingCoordinator.beginRing] on the process app scope ([RingCoordinatorProvider.scope])
 * BEFORE posting the notification / launching the full-screen activity. The
 * old flow was fire-and-forget from the ringing activity's ioScope, so a ring
 * that never got an activity (FSI denied, killed process) lost the daily
 * re-arm / one-shot disable forever.
 *
 * It also handles the notification's Dismiss and Snooze broadcast actions
 * (previously orphaned constants), validating the durable ring-session token
 * so a stale notification cannot act on a newer ring.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ALARM_FIRED, ACTION_TIMER_FIRED -> {
                val label = intent.getStringExtra(EXTRA_LABEL)
                    ?: context.getString(R.string.default_alarm_label)
                val isTimer = intent.action == ACTION_TIMER_FIRED
                val alertId = intent.getIntExtra(EXTRA_ALERT_ID, -1)
                val firedTriggerMillis = intent.getLongExtra(EXTRA_TRIGGER_AT, -1L)
                val pending = goAsync()
                RingCoordinatorProvider.scope.launch {
                    try {
                        val outcome = RingCoordinatorProvider.get(context).beginRing(
                            alertId = alertId,
                            label = label,
                            isTimer = isTimer,
                            firedTriggerMillis = firedTriggerMillis,
                        )
                        if (outcome is RingBeginOutcome.Ringing) {
                            launchRingingActivity(context, outcome.session)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.e(e, "Ring begin failed for alert %d", alertId)
                    } finally {
                        pending.finish()
                    }
                }
            }

            ACTION_SNOOZE, ACTION_DISMISS -> {
                val alertId = intent.getIntExtra(EXTRA_ALERT_ID, -1)
                val token = intent.getStringExtra(EXTRA_RING_TOKEN)
                val dismiss = intent.action == ACTION_DISMISS
                val pending = goAsync()
                RingCoordinatorProvider.scope.launch {
                    try {
                        val coordinator = RingCoordinatorProvider.get(context)
                        if (dismiss) {
                            coordinator.dismiss(alertId, token)
                        } else {
                            coordinator.snooze(alertId, token)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.e(e, "Ring action failed for alert %d", alertId)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    /**
     * Best-effort fast path to bring the ringing UI up directly when the app is
     * already foreground. A background activity launch is silently BLOCKED on
     * API 29+ when no launch window exists — the FSI notification is the
     * guaranteed path (posted by the coordinator above), so this never crashes
     * the receiver.
     */
    private fun launchRingingActivity(context: Context, session: com.jarvis.assistant.data.RingSessionEntity) {
        runCatching {
            context.startActivity(RingingNotifier.ringActivityIntent(context, session))
        }.onFailure {
            // Content-free: the exception class name only.
            Timber.d("Ringing activity fast path blocked: %s", it.javaClass.simpleName)
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
         * alert, stable across the receiver's post and its cancel). Only the
         * NOTIFICATION id gains the band offset ([NotificationIds.ringingId]):
         * raw row ids would collide with the assistant band's fixed ids 1–4
         * (FGS state/permission/activation + the exact-alarm degrade note),
         * silently overwriting or cancelling the wrong notification.
         */
        fun ringingNotificationId(alertId: Int): Int = NotificationIds.ringingId(alertId)

        const val EXTRA_ALERT_ID = "alert_id"
        const val EXTRA_LABEL = "label"
        const val EXTRA_IS_TIMER = "is_timer"

        /** Trigger time the broadcast was armed for (fire identity, P3.2). */
        const val EXTRA_TRIGGER_AT = "trigger_at_millis"

        /** Durable ring-session token carried by the activity + action intents. */
        const val EXTRA_RING_TOKEN = "ring_token"
    }
}
