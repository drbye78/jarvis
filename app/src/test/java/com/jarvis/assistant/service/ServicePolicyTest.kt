package com.jarvis.assistant.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Decision-matrix tests for [ServicePolicy] (REMEDIATION_PLAN P1.4).
 *
 * The policy is a behavior-preserving extraction from JarvisForegroundService:
 * each test row below pins a branch that previously lived untested in the
 * service (hostile-OEM critical paths — watchdog revive, mute gating,
 * Android 10 background-start activation, action routing, maintenance
 * timing). Deterministic: fixed GMT clock for the timing tests, no waits.
 */
class ServicePolicyTest {

    private val originalTimeZone = TimeZone.getDefault()

    @Before
    fun setUp() {
        // Deterministic wall clock for nextMaintenanceAt (no DST in GMT).
        TimeZone.setDefault(TimeZone.getTimeZone("GMT"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun inputs(
        graphReady: Boolean = true,
        userStopped: Boolean = false,
        givenUp: Boolean = false,
        muted: Boolean = false,
    ) = ServicePolicy.RunningInstanceInputs(
        graphReady = graphReady,
        userStopped = userStopped,
        pipelineGivenUp = givenUp,
        muted = muted,
    )

    /** Midnight-anchored UTC millis in the test (GMT) zone. */
    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0, millis: Int = 0): Long =
        Calendar.getInstance(TimeZone.getTimeZone("GMT")).apply {
            clear()
            set(year, month, day, hour, minute, second)
            set(Calendar.MILLISECOND, millis)
        }.timeInMillis

    private fun gmtCalendar(ms: Long): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("GMT")).apply { timeInMillis = ms }

    // ------------------------------------------------------------------
    // Action mapping
    // ------------------------------------------------------------------

    @Test
    fun `actionFor maps every service ACTION_ constant`() {
        assertEquals(
            ServiceAction.EXPLICIT_START,
            ServicePolicy.actionFor(JarvisForegroundService.ACTION_EXPLICIT_START),
        )
        assertEquals(
            ServiceAction.WATCHDOG,
            ServicePolicy.actionFor(JarvisForegroundService.ACTION_WATCHDOG),
        )
        assertEquals(
            ServiceAction.RUN_COGNITIVE_MAINTENANCE,
            ServicePolicy.actionFor(JarvisForegroundService.ACTION_RUN_COGNITIVE_MAINTENANCE),
        )
    }

    @Test
    fun `actionFor maps null intent to UNKNOWN (START_STICKY recreation)`() {
        assertEquals(ServiceAction.UNKNOWN, ServicePolicy.actionFor(null))
    }

    @Test
    fun `actionFor maps foreign action strings to UNKNOWN`() {
        assertEquals(ServiceAction.UNKNOWN, ServicePolicy.actionFor("com.other.app.SOMETHING"))
        assertEquals(ServiceAction.UNKNOWN, ServicePolicy.actionFor(""))
    }

    // ------------------------------------------------------------------
    // Watchdog decision (revive gate + mute gating + user stop)
    // ------------------------------------------------------------------

    @Test
    fun `exhaustive watchdog matrix - stop outranks revive, revive needs ready given-up unmuted`() {
        // The full 2^4 truth table of the original branch condition:
        //   userStopped -> STOP
        //   else graphReady && pipelineGivenUp && !muted -> REVIVE
        //   else NO_ACTION
        for (userStopped in listOf(false, true)) {
            for (graphReady in listOf(false, true)) {
                for (givenUp in listOf(false, true)) {
                    for (muted in listOf(false, true)) {
                        val expected = when {
                            userStopped -> ServicePolicy.WatchdogDecision.STOP_FOR_USER_STOP
                            graphReady && givenUp && !muted -> ServicePolicy.WatchdogDecision.REVIVE_PIPELINE
                            else -> ServicePolicy.WatchdogDecision.NO_ACTION
                        }
                        assertEquals(
                            "userStopped=$userStopped graphReady=$graphReady givenUp=$givenUp muted=$muted",
                            expected,
                            ServicePolicy.watchdogDecision(
                                inputs(
                                    graphReady = graphReady,
                                    userStopped = userStopped,
                                    givenUp = givenUp,
                                    muted = muted,
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `watchdog - explicit user stop wins even when a revive would be possible`() {
        assertEquals(
            ServicePolicy.WatchdogDecision.STOP_FOR_USER_STOP,
            ServicePolicy.watchdogDecision(inputs(userStopped = true, givenUp = true, muted = false)),
        )
    }

    @Test
    fun `watchdog - muted pipeline is never revived (mute is a user intent, m12)`() {
        assertEquals(
            ServicePolicy.WatchdogDecision.NO_ACTION,
            ServicePolicy.watchdogDecision(inputs(givenUp = true, muted = true)),
        )
    }

    @Test
    fun `watchdog - healthy pipeline is not touched`() {
        assertEquals(
            ServicePolicy.WatchdogDecision.NO_ACTION,
            ServicePolicy.watchdogDecision(inputs(givenUp = false, muted = false)),
        )
    }

    @Test
    fun `watchdog - graph not ready never revives even if given-up is somehow reported`() {
        assertEquals(
            ServicePolicy.WatchdogDecision.NO_ACTION,
            ServicePolicy.watchdogDecision(inputs(graphReady = false, givenUp = true, muted = false)),
        )
    }

    @Test
    fun `watchdog - P3_3 revive-budget fields are reserved and currently inert`() {
        // The counter/cap/backoff (P3.3) must NOT change today's decision:
        // a would-be-exhausted budget still revives until the counter lands.
        val budgeted = inputs(givenUp = true, muted = false).copy(
            reviveCountToday = 999,
            dailyReviveCap = 3,
            lastReviveMs = 1000L,
            nowMs = 10_000_000L,
        )
        assertEquals(ServicePolicy.WatchdogDecision.REVIVE_PIPELINE, ServicePolicy.watchdogDecision(budgeted))
    }

    // ------------------------------------------------------------------
    // Running-instance command routing
    // ------------------------------------------------------------------

    @Test
    fun `maintenance tick on ready graph - reschedule and run now`() {
        assertEquals(
            ServicePolicy.RunningCommandDecision.RunTail(
                rescheduleMaintenance = true,
                runMaintenanceNow = true,
            ),
            ServicePolicy.runningInstanceCommand(ServiceAction.RUN_COGNITIVE_MAINTENANCE, inputs()),
        )
    }

    @Test
    fun `maintenance tick before init - reschedule but defer the run`() {
        assertEquals(
            ServicePolicy.RunningCommandDecision.RunTail(
                rescheduleMaintenance = true,
                runMaintenanceNow = false,
            ),
            ServicePolicy.runningInstanceCommand(
                ServiceAction.RUN_COGNITIVE_MAINTENANCE,
                inputs(graphReady = false),
            ),
        )
    }

    @Test
    fun `watchdog with user stop - immediate stop, no tail`() {
        assertEquals(
            ServicePolicy.RunningCommandDecision.StopForUserStop,
            ServicePolicy.runningInstanceCommand(
                ServiceAction.WATCHDOG,
                inputs(userStopped = true, givenUp = true, muted = false),
            ),
        )
    }

    @Test
    fun `watchdog with given-up unmuted pipeline - revive then fall through`() {
        assertEquals(
            ServicePolicy.RunningCommandDecision.RunTail(revivePipeline = true),
            ServicePolicy.runningInstanceCommand(ServiceAction.WATCHDOG, inputs(givenUp = true, muted = false)),
        )
    }

    @Test
    fun `watchdog with healthy or muted pipeline - plain fall-through`() {
        assertEquals(
            ServicePolicy.RunningCommandDecision.RunTail(),
            ServicePolicy.runningInstanceCommand(ServiceAction.WATCHDOG, inputs(givenUp = false)),
        )
        assertEquals(
            ServicePolicy.RunningCommandDecision.RunTail(),
            ServicePolicy.runningInstanceCommand(ServiceAction.WATCHDOG, inputs(givenUp = true, muted = true)),
        )
    }

    @Test
    fun `explicit start and unknown actions on a running instance - plain fall-through`() {
        assertEquals(
            ServicePolicy.RunningCommandDecision.RunTail(),
            ServicePolicy.runningInstanceCommand(ServiceAction.EXPLICIT_START, inputs()),
        )
        assertEquals(
            ServicePolicy.RunningCommandDecision.RunTail(),
            ServicePolicy.runningInstanceCommand(ServiceAction.UNKNOWN, inputs()),
        )
    }

    // ------------------------------------------------------------------
    // Background-start routing (Android 10 activation policy)
    // ------------------------------------------------------------------

    @Test
    fun `background start of a stopped assistant - no prompt, no revival`() {
        val route = ServicePolicy.backgroundStartRoute(ServiceAction.WATCHDOG, userStopped = true)
        assertFalse(route.postActivationPrompt)
        assertFalse(route.rescheduleMaintenance)
    }

    @Test
    fun `background start while active - tap-to-activate prompt`() {
        val route = ServicePolicy.backgroundStartRoute(ServiceAction.WATCHDOG, userStopped = false)
        assertTrue(route.postActivationPrompt)
        assertFalse(route.rescheduleMaintenance)
    }

    @Test
    fun `background maintenance tick keeps the nightly chain booked even when the user stopped`() {
        val stopped = ServicePolicy.backgroundStartRoute(ServiceAction.RUN_COGNITIVE_MAINTENANCE, userStopped = true)
        assertTrue(stopped.rescheduleMaintenance)
        assertFalse(stopped.postActivationPrompt)

        val active = ServicePolicy.backgroundStartRoute(ServiceAction.RUN_COGNITIVE_MAINTENANCE, userStopped = false)
        assertTrue(active.rescheduleMaintenance)
        assertTrue(active.postActivationPrompt)
    }

    @Test
    fun `background sticky recreation (null action) prompts without rescheduling`() {
        val route = ServicePolicy.backgroundStartRoute(ServiceAction.UNKNOWN, userStopped = false)
        assertTrue(route.postActivationPrompt)
        assertFalse(route.rescheduleMaintenance)
    }

    // ------------------------------------------------------------------
    // Initialization gate (RECORD_AUDIO before init)
    // ------------------------------------------------------------------

    @Test
    fun `init - already initialized or bootstrapping always skips`() {
        assertEquals(
            ServicePolicy.InitStep.SKIP,
            ServicePolicy.initializationStep(initialized = true, bootstrapping = false, micPermissionGranted = true),
        )
        assertEquals(
            ServicePolicy.InitStep.SKIP,
            ServicePolicy.initializationStep(initialized = false, bootstrapping = true, micPermissionGranted = true),
        )
        assertEquals(
            ServicePolicy.InitStep.SKIP,
            ServicePolicy.initializationStep(initialized = true, bootstrapping = false, micPermissionGranted = false),
        )
    }

    @Test
    fun `init - missing mic permission asks instead of burning the one-shot init`() {
        assertEquals(
            ServicePolicy.InitStep.REQUEST_MIC_PERMISSION,
            ServicePolicy.initializationStep(initialized = false, bootstrapping = false, micPermissionGranted = false),
        )
    }

    @Test
    fun `init - fresh permitted start begins bootstrapping`() {
        assertEquals(
            ServicePolicy.InitStep.BEGIN_BOOTSTRAP,
            ServicePolicy.initializationStep(initialized = false, bootstrapping = false, micPermissionGranted = true),
        )
    }

    // ------------------------------------------------------------------
    // Watchdog cadence
    // ------------------------------------------------------------------

    @Test
    fun `watchdog fires exactly one interval from now`() {
        assertEquals(1_900_000L, ServicePolicy.watchdogTriggerAt(nowMs = 1_000_000L, intervalMs = 900_000L))
        assertEquals(0L, ServicePolicy.watchdogTriggerAt(nowMs = 0L, intervalMs = 0L))
    }

    @Test
    fun `exact allow-while-idle scheduling from API 23 (M) onward`() {
        assertFalse(ServicePolicy.useExactAllowWhileIdle(sdkInt = 22))
        assertTrue(ServicePolicy.useExactAllowWhileIdle(sdkInt = 23))
        assertTrue(ServicePolicy.useExactAllowWhileIdle(sdkInt = 29))
        assertTrue(ServicePolicy.useExactAllowWhileIdle(sdkInt = 34))
    }

    // ------------------------------------------------------------------
    // Typed FGS decisions
    // ------------------------------------------------------------------

    @Test
    fun `typed foreground start only on API 34+`() {
        assertFalse(ServicePolicy.requiresTypedForegroundStart(sdkInt = 33))
        assertTrue(ServicePolicy.requiresTypedForegroundStart(sdkInt = 34))
    }

    @Test
    fun `mediaProjection promotion needs an already-foregrounded instance AND API 34+`() {
        assertFalse(ServicePolicy.mayPromoteForMediaProjection(everForegrounded = false, sdkInt = 34))
        assertFalse(ServicePolicy.mayPromoteForMediaProjection(everForegrounded = true, sdkInt = 33))
        assertFalse(ServicePolicy.mayPromoteForMediaProjection(everForegrounded = false, sdkInt = 29))
        assertTrue(ServicePolicy.mayPromoteForMediaProjection(everForegrounded = true, sdkInt = 34))
    }

    // ------------------------------------------------------------------
    // Activation prompt permission gate
    // ------------------------------------------------------------------

    @Test
    fun `activation prompt is denied only by API 33+ with POST_NOTIFICATIONS revoked`() {
        assertFalse(ServicePolicy.activationPromptAllowed(sdkInt = 33, notificationsPermissionGranted = false))
        assertFalse(ServicePolicy.activationPromptAllowed(sdkInt = 34, notificationsPermissionGranted = false))
        assertTrue(ServicePolicy.activationPromptAllowed(sdkInt = 33, notificationsPermissionGranted = true))
        assertTrue(ServicePolicy.activationPromptAllowed(sdkInt = 32, notificationsPermissionGranted = false))
        assertTrue(ServicePolicy.activationPromptAllowed(sdkInt = 29, notificationsPermissionGranted = false))
    }

    // ------------------------------------------------------------------
    // Cognitive maintenance timing (COGNITIVE_PLAN §9.1)
    // ------------------------------------------------------------------

    @Test
    fun `next maintenance is the upcoming local 03_30`() {
        val at = ServicePolicy.nextMaintenanceAt(utc(2026, Calendar.SEPTEMBER, 6, 2, 0))
        val cal = gmtCalendar(at)
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(Calendar.SEPTEMBER, cal.get(Calendar.MONTH))
        assertEquals(6, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(3, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
        assertEquals(0, cal.get(Calendar.MILLISECOND))
    }

    @Test
    fun `next maintenance rolls to tomorrow once 03_30 has passed`() {
        val after = gmtCalendar(ServicePolicy.nextMaintenanceAt(utc(2026, Calendar.SEPTEMBER, 6, 3, 30, second = 0, millis = 1)))
        assertEquals(7, after.get(Calendar.DAY_OF_MONTH))
        assertEquals(3, after.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, after.get(Calendar.MINUTE))

        val evening = gmtCalendar(ServicePolicy.nextMaintenanceAt(utc(2026, Calendar.SEPTEMBER, 6, 23, 45)))
        assertEquals(7, evening.get(Calendar.DAY_OF_MONTH))

        val exactlyAt = gmtCalendar(ServicePolicy.nextMaintenanceAt(utc(2026, Calendar.SEPTEMBER, 6, 3, 30, second = 0, millis = 0)))
        // Boundary is inclusive (timeInMillis <= now): 03:30:00.000 sharp
        // schedules the NEXT night, never an alarm at `now`.
        assertEquals(7, exactlyAt.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `next maintenance just before 03_30 stays today`() {
        val justBefore = gmtCalendar(ServicePolicy.nextMaintenanceAt(utc(2026, Calendar.SEPTEMBER, 6, 3, 29, second = 59, millis = 999)))
        assertEquals(6, justBefore.get(Calendar.DAY_OF_MONTH))
        assertEquals(3, justBefore.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, justBefore.get(Calendar.MINUTE))

        val justAfterMidnight = gmtCalendar(ServicePolicy.nextMaintenanceAt(utc(2026, Calendar.SEPTEMBER, 6, 0, 0)))
        assertEquals(6, justAfterMidnight.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `maintenance staleness - strict threshold, missing entry counts as never run`() {
        val now = utc(2026, Calendar.SEPTEMBER, 6, 12, 0)
        val staleMs = ServicePolicy.MAINTENANCE_STALE_MS
        assertTrue(ServicePolicy.isMaintenanceStale(lastMaintenanceAtMs = null, nowMs = now))
        assertTrue(ServicePolicy.isMaintenanceStale(lastMaintenanceAtMs = 0L, nowMs = now))
        assertFalse(ServicePolicy.isMaintenanceStale(lastMaintenanceAtMs = now - (staleMs - 1), nowMs = now))
        // Exactly 20 h is NOT yet stale (strictly-greater comparison).
        assertFalse(ServicePolicy.isMaintenanceStale(lastMaintenanceAtMs = now - staleMs, nowMs = now))
        assertTrue(ServicePolicy.isMaintenanceStale(lastMaintenanceAtMs = now - staleMs - 1, nowMs = now))
        // Override used by tests/future callers.
        assertTrue(ServicePolicy.isMaintenanceStale(lastMaintenanceAtMs = now - 60, nowMs = now, staleMs = 59))
    }

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    @Test
    fun `watchdog alarm is cancelled on destroy only after an explicit user stop (m7)`() {
        assertTrue(ServicePolicy.cancelWatchdogOnDestroy(userStopped = true))
        assertFalse(ServicePolicy.cancelWatchdogOnDestroy(userStopped = false))
    }
}
