package com.jarvis.assistant.tools

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.data.FiredResolution
import com.jarvis.assistant.data.RingSessionDao
import com.jarvis.assistant.data.RingSessionEntity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber
import java.util.UUID

/**
 * Pure ring-session identity logic (REMEDIATION_PLAN P3.2/P3.5), extracted so
 * the JVM suite can pin capture/resume without Android. Ring state is durable
 * in `ring_sessions`, NOT a process-local field: the token minted at
 * ring-begin is the only thing a notification action / activity may act on, so
 * a stale notification from a previous ring cannot dismiss the current one.
 */
object RingSessionPolicy {

    fun newSession(
        alertId: Int,
        token: String,
        startedAtElapsed: Long,
        label: String,
        isTimer: Boolean,
        firedTriggerMillis: Long,
    ): RingSessionEntity = RingSessionEntity(
        alertId = alertId,
        token = token,
        startedAtElapsed = startedAtElapsed,
        label = label,
        isTimer = isTimer,
        firedTriggerMillis = firedTriggerMillis,
    )

    /** True only when a session exists and its token matches the acting intent. */
    fun isLive(session: RingSessionEntity?, token: String?): Boolean =
        session != null && !token.isNullOrEmpty() && session.token == token

    /**
     * Authorization for an action carrying [token]. A NON-empty token must
     * match the live session (this is what stops a notification left over from
     * an earlier ring from acting on a newer one). An ABSENT token is allowed
     * on a live session because the system alarm-clock show-intent is built at
     * ARM time — before any ring token exists — yet the user tapping the
     * system alarm indicator must still be able to dismiss. Notification
     * actions always carry the ring-begin token, so they stay strictly checked.
     */
    fun authorizes(session: RingSessionEntity?, token: String?): Boolean =
        session != null && (token.isNullOrEmpty() || session.token == token)
}

/**
 * What the platform needs to do to surface/retire a ring — the Android seam of
 * [RingCoordinator], injected so begin/dismiss/snooze policy is JVM-testable.
 */
interface RingPresenter {
    /** True when the system will let us launch a full-screen intent (API 34+ may deny). */
    fun fullScreenAllowed(): Boolean

    /** `NotificationManagerCompat.areNotificationsEnabled()` — false = no control surface. */
    fun notificationsEnabled(): Boolean

    /** Posts the one ring notification; returns false when notifications are disabled. */
    fun present(session: RingSessionEntity, fullScreenAllowed: Boolean): Boolean

    /** Stops the ringer and cancels the ring notification. */
    fun retire(alertId: Int)
}

/** Production [RingPresenter]: [RingingNotifier] + [AlarmRinger]. */
class AndroidRingPresenter(private val appContext: Context) : RingPresenter {

    override fun fullScreenAllowed(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            true // FSI permission did not exist before API 34
        } else {
            // `as?`: odd OEM ROMs can return null for the notification service.
            (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.canUseFullScreenIntent()
                ?: false
        }

    override fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(appContext).areNotificationsEnabled()

    override fun present(session: RingSessionEntity, fullScreenAllowed: Boolean): Boolean =
        RingingNotifier.post(appContext, session, fullScreenAllowed)

    override fun retire(alertId: Int) {
        AlarmRinger.stop(appContext, quiet = true)
        RingingNotifier.cancel(appContext, alertId)
    }
}

/** Result of [RingCoordinator.beginRing]. */
sealed interface RingBeginOutcome {
    /** Ring is live; [session] holds the token the activity/actions must present. */
    data class Ringing(
        val session: RingSessionEntity,
        val notificationPosted: Boolean,
        val fullScreenAllowed: Boolean,
        val notificationsEnabled: Boolean,
    ) : RingBeginOutcome

    /** The fired occurrence is stale (snoozed/disabled/deleted) — do NOT ring. */
    data object Suppressed : RingBeginOutcome

    /** No usable alert id in the broadcast. */
    data object Invalid : RingBeginOutcome
}

/**
 * Owns the ring lifecycle (REMEDIATION_PLAN P3.1/P3.3/P3.9): the RECEIVER runs
 * [beginRing] (terminal DB transition + durable ring session + notification)
 * before anything is surfaced; the activity and notification actions call
 * [dismiss]/[snooze]; cancel tools call [endLiveRing].
 *
 * Ordering matters: [AndroidAlarmScheduler.onFired] runs FIRST, and a NoOp
 * (stale fire) suppresses the whole ring. That is what makes a snoozed alert
 * resume rather than ring again for the old occurrence.
 */
class RingCoordinator(
    private val ringDao: RingSessionDao,
    private val scheduler: AndroidAlarmScheduler,
    private val presenter: RingPresenter,
    private val nowElapsed: () -> Long = { SystemClock.elapsedRealtime() },
    private val newToken: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * Ring-begin: terminal transition, durable session capture, then surface.
     * MUST be awaited before the notification/activity is attempted.
     */
    suspend fun beginRing(
        alertId: Int,
        label: String,
        isTimer: Boolean,
        firedTriggerMillis: Long,
    ): RingBeginOutcome {
        if (alertId < 0) return RingBeginOutcome.Invalid
        val resolution = scheduler.onFired(alertId, firedTriggerMillis)
        if (resolution == FiredResolution.NoOp) {
            Timber.i("Ring suppressed: alert %d gone/disabled or fire is stale", alertId)
            return RingBeginOutcome.Suppressed
        }
        val session = RingSessionPolicy.newSession(
            alertId = alertId,
            token = newToken(),
            startedAtElapsed = nowElapsed(),
            label = label,
            isTimer = isTimer,
            firedTriggerMillis = firedTriggerMillis,
        )
        ringDao.upsert(session)

        val notificationsEnabled = presenter.notificationsEnabled()
        val fullScreenAllowed = presenter.fullScreenAllowed()
        val posted = if (notificationsEnabled) {
            presenter.present(session, fullScreenAllowed)
        } else {
            false
        }
        if (!notificationsEnabled) {
            // Honest degrade: sound may still come from the activity, but there
            // is NO notification control surface. Never pretend otherwise.
            Timber.w("Ring degraded: notifications disabled for alert %d", alertId)
        }
        // The user-set label is content: DEBUG only.
        Timber.d("Ring began for alert %d label=%s", alertId, session.label)
        return RingBeginOutcome.Ringing(session, posted, fullScreenAllowed, notificationsEnabled)
    }

    /** True when [alertId] has a live ring whose token matches [token]. */
    suspend fun isLive(alertId: Int, token: String?): Boolean =
        RingSessionPolicy.isLive(ringDao.byAlertId(alertId), token)

    /**
     * Dismiss: validate the token against the durable session, then stop the
     * ringer + cancel the notification + delete the session. A stale token is
     * ignored (a notification for an old ring cannot act on a new one).
     */
    suspend fun dismiss(alertId: Int, token: String?): Boolean {
        val session = ringDao.byAlertId(alertId) ?: return false
        if (!RingSessionPolicy.authorizes(session, token)) {
            Timber.w("Ignored stale dismiss for alert %d", alertId)
            return false
        }
        endRing(alertId)
        return true
    }

    /** Snooze: same token guard, then end the ring and move the trigger forward. */
    suspend fun snooze(
        alertId: Int,
        token: String?,
        delayMillis: Long = AndroidAlarmScheduler.DEFAULT_SNOOZE_MS,
    ): Boolean {
        val session = ringDao.byAlertId(alertId) ?: return false
        if (!RingSessionPolicy.authorizes(session, token)) {
            Timber.w("Ignored stale snooze for alert %d", alertId)
            return false
        }
        endRing(alertId)
        scheduler.snooze(alertId, delayMillis)
        return true
    }

    /**
     * Unconditional stop for the cancel/delete tools (P3.9): they do not carry
     * a ring token, but deleting an alert must silence it. No-op when nothing
     * is live.
     */
    suspend fun endLiveRing(alertId: Int) {
        if (ringDao.byAlertId(alertId) == null) return
        endRing(alertId)
    }

    private suspend fun endRing(alertId: Int) {
        presenter.retire(alertId)
        ringDao.delete(alertId)
    }
}

/**
 * Process-wide access to the live [RingCoordinator] for lanes that do not get
 * one by construction: the receiver (which often runs before any graph exists)
 * and the ringing activity. Graph installs the graph-owned instance; before
 * that, a lazy fallback is bound to the application context, mirroring
 * [AlarmSchedulerProvider].
 *
 * [scope] is the receiver-driven app scope: ring-begin must outlive the
 * activity lifecycle (the whole point of P3.1), so it is NOT tied to a
 * BroadcastReceiver's transient scope.
 */
object RingCoordinatorProvider {

    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Timber.e(e, "Uncaught coroutine exception in ring scope")
        },
    )

    @Volatile
    private var installed: RingCoordinator? = null

    @Volatile
    private var fallback: RingCoordinator? = null

    val installedOrNull: RingCoordinator? get() = installed

    fun install(coordinator: RingCoordinator) {
        installed = coordinator
    }

    fun clearForTests() {
        installed = null
        fallback = null
    }

    fun get(context: Context?): RingCoordinator {
        installed?.let { return it }
        return fallback ?: synchronized(this) {
            fallback ?: run {
                val appContext = requireNotNull(context) {
                    "RingCoordinatorProvider.get requires a Context before AppGraph installs the graph-owned coordinator"
                }.applicationContext
                RingCoordinator(
                    com.jarvis.assistant.data.AppDatabase.getInstance(appContext).ringSessionDao(),
                    AlarmSchedulerProvider.get(appContext),
                    AndroidRingPresenter(appContext),
                ).also { fallback = it }
            }
        }
    }

    /** Cancel-tool hook: stop a live ring for [alertId], if any. */
    suspend fun endLiveRingForAlert(alertId: Int) {
        (installed ?: fallback)?.endLiveRing(alertId)
    }
}
