package com.jarvis.assistant.cognitive.behavior

import com.jarvis.assistant.cognitive.data.BehaviorLogDao
import com.jarvis.assistant.cognitive.data.BehaviorLogEntity
import com.jarvis.assistant.home.Capability
import com.jarvis.assistant.home.HomeDeviceKey
import com.jarvis.assistant.home.HomeEntityCodec
import com.jarvis.assistant.home.HomeEventSource
import com.jarvis.assistant.home.HomeNotice
import com.jarvis.assistant.home.HomeNoticeDebouncer
import com.jarvis.assistant.home.HomeNoticePolicy
import com.jarvis.assistant.home.HomeStateChange
import com.jarvis.assistant.home.HomeValue
import com.jarvis.assistant.home.TransitionClass
import com.jarvis.assistant.tools.ToolStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The smart-home awareness loop (R4/H4). Graph-scoped, off the main thread.
 *
 * It reacts LIVE to [awarenessEnabled] AND [curated] (a `combine` +
 * `collectLatest`): disabling the toggle or editing the curated set cancels the
 * current subscription and (for the latter) opens a new one. An EMPTY curated
 * set means "curate nothing" — no socket is opened at all.
 *
 * A pushed [HomeStateChange] is classified by the pure [HomeNoticePolicy] into a
 * content-free [HomeNotice], passed through [HomeNoticeGate] (the habit lane's
 * gate vocabulary with its own master switch and daily cap), debounced, then
 * spoken through [ProactiveSpeaker] and logged as a `HOME_NOTICE` row (never
 * `FIRED`, so the habit quota and reject path are untouched).
 *
 * Reconnect is bounded backoff; `CancellationException` is always rethrown so a
 * scope cancel closes the socket. Nothing here ever writes to a device.
 */
// Many collaborators, mostly value/flow references; the defaults keep the
// required count under the configured threshold.
@Suppress("LongParameterList")
class HomeAwarenessLoop(
    private val scope: CoroutineScope,
    private val eventSource: () -> HomeEventSource?,
    private val awarenessEnabled: StateFlow<Boolean>,
    private val curated: StateFlow<String>,
    private val logDao: BehaviorLogDao,
    private val speaker: ProactiveSpeaker,
    private val strings: ToolStrings,
    private val signals: DeviceSignals,
    private val sessionIdle: StateFlow<Boolean>,
    private val lastInteractionAt: suspend () -> Long?,
    private val quietStart: StateFlow<Int>,
    private val quietEnd: StateFlow<Int>,
    private val dailyCap: StateFlow<Int>,
    private val hourOfDay: () -> Int,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val debouncer = HomeNoticeDebouncer()
    private val started = AtomicBoolean(false)
    private var job: Job? = null

    /** Start once; a second call is a no-op. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        job = scope.launch(io) {
            combine(awarenessEnabled, curated) { enabled, blob -> enabled to blob }
                .collectLatest { (enabled, blob) ->
                    if (!enabled) return@collectLatest
                    val keys = HomeEntityCodec.decodeOrEmpty(blob)
                    if (keys.isEmpty()) return@collectLatest
                    val source = eventSource() ?: return@collectLatest
                    subscribe(source, keys)
                }
        }
    }

    /** Stop the loop and its subscription. Safe to call before [start]. */
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun subscribe(source: HomeEventSource, keys: Set<HomeDeviceKey>) {
        var backoff = INITIAL_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            try {
                source.events(keys).collect { change ->
                    // A successful emission resets the backoff.
                    backoff = INITIAL_BACKOFF_MS
                    onEvent(change)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d("Home awareness: subscription ended (%s)", e::class.java.simpleName)
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private suspend fun onEvent(change: HomeStateChange) {
        val old = change.old ?: return
        val transition = HomeNoticePolicy.classify(change.kind, old, change.new) ?: return
        val now = change.atMs
        if (!gateAllows(now)) return
        val notice = HomeNotice(change.key, change.kind, transition, now)
        // Debounce only once the gate has accepted, so a blocked notice does not
        // consume the cooldown.
        if (!debouncer.accept(notice, now)) return
        deliver(change, transition, now)
    }

    private suspend fun gateAllows(now: Long): Boolean {
        val quiet = BehaviorArbiter.isQuietHour(hourOfDay(), quietStart.value, quietEnd.value)
        val presence = lastInteractionAt()?.let { now - it <= BehaviorArbiter.PRESENCE_WINDOW_MS } ?: false
        val quotaLeft = quotaUsedToday(now) < dailyCap.value
        val ctx = BehaviorArbiter.ArbiterContext(
            // Ignored by HomeNoticeGate (it has its own master switch); supplied
            // only to satisfy the shared carrier.
            behaviorEnabled = true,
            quietHoursActive = quiet,
            dndActive = signals.dndActive(),
            batteryOk = signals.batteryOk(),
            sessionIdle = sessionIdle.value,
            mediaActive = signals.mediaActive(),
            recentInteraction = presence,
            quotaLeft = quotaLeft,
            cooldownOk = true,
            notRecentlyDelivered = true,
        )
        val decision = HomeNoticeGate.evaluate(
            ctx = ctx,
            awarenessEnabled = awarenessEnabled.value,
            quotaLeft = quotaLeft,
            cooldownOk = true,
        )
        return decision is BehaviorArbiter.Decision.Fired
    }

    private suspend fun quotaUsedToday(now: Long): Int = try {
        logDao.homeNoticeSince(BehaviorArbiter.startOfDayMs(now))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A failed count must not unleash a flood; treat it as exhausted.
        Timber.d("Home awareness: notice count failed (%s)", e::class.java.simpleName)
        Int.MAX_VALUE
    }

    private suspend fun deliver(
        change: HomeStateChange,
        transition: TransitionClass,
        now: Long,
    ) {
        val locked = (change.new.values[Capability.LOCK] as? HomeValue.Bool)?.value ?: false
        val text = strings.homeNotice(change.kind, transition, locked)
        if (text.isBlank()) return
        val spoken = try {
            speaker.speak(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Home awareness: speak failed (%s)", e::class.java.simpleName)
            false
        }
        try {
            logDao.insert(
                BehaviorLogEntity(
                    at = now,
                    ruleId = null,
                    decision = BehaviorLogEntity.DECISION_HOME_NOTICE,
                    reason = transition.name,
                    utterance = text,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Home awareness: notice log failed (%s)", e::class.java.simpleName)
        }
        Timber.i("Home awareness: %s %s", if (spoken) "delivered" else "refused", transition.name)
    }

    private companion object {
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}
