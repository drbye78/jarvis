package com.jarvis.assistant.service

import java.util.Calendar

/**
 * Pure decision logic for [JarvisForegroundService] (REMEDIATION_PLAN P1.4).
 *
 * NO Android imports: every input is a plain value the service reads from
 * its Android surroundings, every output is a decision the service executes.
 * All Android I/O (startForeground, notifications, AlarmManager, audio,
 * graph construction) stays in the service; this class only answers
 * "what should happen" so the hostile-OEM decision matrix (watchdog revive,
 * mute gating, Android 10 background-start activation policy, action
 * routing, maintenance timing) is unit-testable on the JVM.
 *
 * Behavior-preserving extraction (P1.4): each branch condition below was
 * moved verbatim from the service; decision evaluation order matches the
 * original. The EXPLICIT_START branch of [JarvisForegroundService.onStartCommand]
 * has no conditional decision beyond the [JarvisForegroundService.everForegrounded]
 * bookkeeping (always: clear userStopped, promote to a foreground service
 * iff this instance is fresh), so it stays inline in the service.
 */
enum class ServiceAction { EXPLICIT_START, WATCHDOG, RUN_COGNITIVE_MAINTENANCE, UNKNOWN }

object ServicePolicy {

    // ------------------------------------------------------------------
    // Action routing
    // ------------------------------------------------------------------

    /**
     * Maps the start intent's action string onto the routing table. A null
     * action (START_STICKY recreation) and any unknown action are UNKNOWN.
     */
    fun actionFor(intentAction: String?): ServiceAction = when (intentAction) {
        JarvisForegroundService.ACTION_EXPLICIT_START -> ServiceAction.EXPLICIT_START
        JarvisForegroundService.ACTION_WATCHDOG -> ServiceAction.WATCHDOG
        JarvisForegroundService.ACTION_RUN_COGNITIVE_MAINTENANCE -> ServiceAction.RUN_COGNITIVE_MAINTENANCE
        else -> ServiceAction.UNKNOWN
    }

    /**
     * Fresh instance created by a BACKGROUND start (watchdog/maintenance
     * alarm via PendingIntent.getService, or a START_STICKY recreation with
     * a null intent): never becomes a foreground service (Android 10
     * while-in-use rule would silence its microphone; Android 12+ restricts
     * the start itself). Keep the maintenance chain booked when this start
     * IS the maintenance tick, ask the user to tap-activate unless they
     * stopped the assistant, and tear the instance down.
     */
    data class BackgroundStartRoute(
        val rescheduleMaintenance: Boolean,
        val postActivationPrompt: Boolean,
    )

    fun backgroundStartRoute(action: ServiceAction, userStopped: Boolean): BackgroundStartRoute =
        BackgroundStartRoute(
            rescheduleMaintenance = action == ServiceAction.RUN_COGNITIVE_MAINTENANCE,
            postActivationPrompt = !userStopped,
        )

    /**
     * Live state of a running (user-activated) instance at the moment an
     * alarm-delivered command is handled. The service fills this from
     * `graph`/`prefs` at the same points the original branch conditions
     * read them. `graphReady` means `graph != null && initialized`.
     *
     * The `revive*` fields are RESERVED for P3.3 (wedge-revive guard: revive
     * counter + daily cap + backoff). They are passed in so the policy is
     * structurally ready, but the counter is deliberately NOT implemented
     * yet: with the inert defaults no current decision changes.
     */
    data class RunningInstanceInputs(
        val graphReady: Boolean,
        val userStopped: Boolean,
        val pipelineGivenUp: Boolean,
        val muted: Boolean,
        val reviveCountToday: Int = 0,
        val dailyReviveCap: Int = Int.MAX_VALUE,
        val lastReviveMs: Long? = null,
        val nowMs: Long = 0L,
    )

    /** Watchdog tick outcome. Evaluation order: user stop FIRST (the ping
     *  must never silently undo a user intent), then the audit #25 revive
     *  gate (`graphReady && pipelineGivenUp && !muted`). */
    enum class WatchdogDecision { STOP_FOR_USER_STOP, REVIVE_PIPELINE, NO_ACTION }

    fun watchdogDecision(inputs: RunningInstanceInputs): WatchdogDecision = when {
        inputs.userStopped -> WatchdogDecision.STOP_FOR_USER_STOP
        inputs.graphReady && inputs.pipelineGivenUp && !inputs.muted -> WatchdogDecision.REVIVE_PIPELINE
        else -> WatchdogDecision.NO_ACTION
    }

    /**
     * Decision for an alarm-delivered command targeting a RUNNING instance.
     *
     * - [RunningCommandDecision.StopForUserStop]: the caller must stopSelf()
     *   and return START_NOT_STICKY immediately (skip the common tail).
     * - [RunningCommandDecision.RunTail]: execute the flagged side effects
     *   (reschedule the maintenance alarm / run or defer the maintenance
     *   tick / revive the given-up pipeline), then fall through to the
     *   common tail and return START_STICKY.
     *
     * `runMaintenanceNow` is non-null only for the maintenance tick
     * (true = graph ready, run now; false = arrived before init, defer).
     */
    sealed interface RunningCommandDecision {
        data object StopForUserStop : RunningCommandDecision
        data class RunTail(
            val rescheduleMaintenance: Boolean = false,
            val runMaintenanceNow: Boolean? = null,
            val revivePipeline: Boolean = false,
        ) : RunningCommandDecision
    }

    fun runningInstanceCommand(action: ServiceAction, inputs: RunningInstanceInputs): RunningCommandDecision =
        when (action) {
            ServiceAction.RUN_COGNITIVE_MAINTENANCE -> RunningCommandDecision.RunTail(
                rescheduleMaintenance = true,
                runMaintenanceNow = inputs.graphReady,
            )
            ServiceAction.WATCHDOG -> when (watchdogDecision(inputs)) {
                WatchdogDecision.STOP_FOR_USER_STOP -> RunningCommandDecision.StopForUserStop
                WatchdogDecision.REVIVE_PIPELINE -> RunningCommandDecision.RunTail(revivePipeline = true)
                WatchdogDecision.NO_ACTION -> RunningCommandDecision.RunTail()
            }
            ServiceAction.EXPLICIT_START, ServiceAction.UNKNOWN -> RunningCommandDecision.RunTail()
        }

    // ------------------------------------------------------------------
    // Initialization gate (RECORD_AUDIO checked BEFORE init)
    // ------------------------------------------------------------------

    enum class InitStep { SKIP, REQUEST_MIC_PERMISSION, BEGIN_BOOTSTRAP }

    /**
     * The RECORD_AUDIO-first gate: an in-progress or completed init skips
     * silently; a fresh start without the mic permission shows the
     * permission notification instead of burning the one-shot init; only a
     * fresh permitted start begins bootstrapping.
     */
    fun initializationStep(initialized: Boolean, bootstrapping: Boolean, micPermissionGranted: Boolean): InitStep =
        when {
            initialized || bootstrapping -> InitStep.SKIP
            !micPermissionGranted -> InitStep.REQUEST_MIC_PERMISSION
            else -> InitStep.BEGIN_BOOTSTRAP
        }

    // ------------------------------------------------------------------
    // Watchdog cadence (constants live in JarvisConfig; unchanged)
    // ------------------------------------------------------------------

    /** Next watchdog ping: exactly one interval from now (no drift, no backoff). */
    fun watchdogTriggerAt(nowMs: Long, intervalMs: Long): Long = nowMs + intervalMs

    /** `setExactAndAllowWhileIdle` from API 23 (Build.VERSION_CODES.M) on. */
    fun useExactAllowWhileIdle(sdkInt: Int): Boolean = sdkInt >= 23

    // ------------------------------------------------------------------
    // Foreground-service type decisions (typed FGS on API 34+)
    // ------------------------------------------------------------------

    /** API 34+ requires the FGS type to be passed to startForeground explicitly. */
    fun requiresTypedForegroundStart(sdkInt: Int): Boolean = sdkInt >= 34

    /**
     * Adding the mediaProjection FGS type at runtime: only an instance that
     * already IS a foreground service can be promoted, and only API 34+
     * needs the explicit two-type call (API 29–33 inherit all manifest
     * types via the two-arg startForeground).
     */
    fun mayPromoteForMediaProjection(everForegrounded: Boolean, sdkInt: Int): Boolean =
        everForegrounded && sdkInt >= 34

    // ------------------------------------------------------------------
    // Android 10 background-start "tap to activate" prompt
    // ------------------------------------------------------------------

    /**
     * Permission gate of the activation prompt: POST_NOTIFICATIONS is only
     * a runtime permission from API 33 (Build.VERSION_CODES.TIRAMISU); on
     * older APIs the prompt always shows. (The caller resolves the runtime
     * permission state; on API < 33 the lookup result is irrelevant.)
     */
    fun activationPromptAllowed(sdkInt: Int, notificationsPermissionGranted: Boolean): Boolean =
        sdkInt < 33 || notificationsPermissionGranted

    // ------------------------------------------------------------------
    // Cognitive maintenance timing (COGNITIVE_PLAN §9.1)
    // ------------------------------------------------------------------

    /** §9.1: nightly maintenance lands at ~03:30 local. */
    const val MAINTENANCE_HOUR = 3

    /** §9.1: the opportunistic trigger — last maintenance older than 20 h. */
    const val MAINTENANCE_STALE_MS = 20L * 60 * 60_000L

    /** Next local ~03:30 (today if still before it, tomorrow otherwise). */
    fun nextMaintenanceAt(nowMs: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, MAINTENANCE_HOUR)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= nowMs) add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /**
     * Opportunistic maintenance gate: run when the last maintenance is
     * older than [staleMs]. A missing entry ([lastMaintenanceAtMs] == null,
     * treated as epoch 0 exactly like the original `?: 0L`) counts as stale.
     * Strictly greater — a run exactly [staleMs] old is not yet due.
     */
    fun isMaintenanceStale(lastMaintenanceAtMs: Long?, nowMs: Long, staleMs: Long = MAINTENANCE_STALE_MS): Boolean =
        nowMs - (lastMaintenanceAtMs ?: 0L) > staleMs

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    /**
     * m7: only an EXPLICIT user stop may cancel the watchdog alarm on
     * destroy. Any other teardown (system service-stop, Apply-restart
     * handoff) leaves the restart alarm armed so the assistant revives.
     */
    fun cancelWatchdogOnDestroy(userStopped: Boolean): Boolean = userStopped
}
