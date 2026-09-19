package com.jarvis.assistant.audio

import com.jarvis.assistant.audio.aec.EchoCanceller
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.contracts.AudioSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.coroutines.coroutineContext

/**
 * Owns an [AudioSource] and a SINGLE producer coroutine — the only code path
 * allowed to touch the underlying AudioRecord.
 *
 * SOFTWARE AEC (Phase B): when an [EchoCanceller] is injected, every mic
 * frame passes through it ONCE here — the ring buffer and the SharedFlow
 * share the SAME echo-cancelled snapshot, so the wake-word engine and ASR
 * both receive clean audio. The canceller's far-end reference is fed by the
 * external lanes (TTS tap / playback capture) via
 * [com.jarvis.assistant.audio.aec.FarEndMixer].
 *
 * One defensive copy remains here even though [AudioRecordSource.read] now
 * always returns a private copy (audit #8): an injected [EchoCanceller] may
 * return its OWN internal buffer (the bypass path returns the input
 * instance), so the single copy below is what guarantees the ring buffer and
 * the flow share one immutable snapshot that no later stage can overwrite.
 *
 * M8: ring capacity derives from [JarvisConfig.preRollMs] instead of a fixed
 * 160 ms; evictions of unread pre-roll frames are counted and logged.
 *
 * m5: when the source reports itself not started/closed ([IllegalStateException]
 * from [AudioSource.read]) the producer exits cleanly with a single log line
 * instead of spamming retry delays; [start] revives it.
 */
class AudioPipeline(
    private val scope: CoroutineScope,
    private val source: AudioSource,
    private val preRollMs: Long = JarvisConfig.DEFAULT_PRE_ROLL_MS,
    /** Software AEC stage; null = raw capture (OFF/HARDWARE modes). */
    private val echoCanceller: EchoCanceller? = null,
) {
    companion object {
        /** One capture frame = 20 ms @ 16 kHz (320 samples), see [AudioRecordSource]. */
        const val FRAME_MS = 20L

        /** After the first eviction, re-log only every Nth eviction to avoid log floods. */
        private const val EVICTION_LOG_STRIDE = 50L

        /** Park interval while the producer waits for [start] (m5). */
        private const val PRODUCER_IDLE_PARK_MS = 10L

        /** Backoff between consecutive read retries. */
        private const val READ_RETRY_DELAY_MS = 100L

        /** Consecutive read failures after which the producer gives up (#25). */
        private const val GIVE_UP_AFTER_CONSECUTIVE_FAILURES = 50

        /**
         * Ring capacity in frames for a given pre-roll window. Coerced so even
         * degenerate config values keep at least one frame of headroom.
         * Default 3000 ms / 20 ms = 150 frames ≈ 96 KB at 320 samples × 2 bytes.
         */
        fun ringCapacity(preRollMs: Long): Int =
            (preRollMs.coerceAtLeast(FRAME_MS) / FRAME_MS).toInt()
    }

    private val _frames = MutableSharedFlow<ShortArray>(
        extraBufferCapacity = 10,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    val frames: Flow<ShortArray> = _frames

    val ringBuffer = AudioRingBuffer(ringCapacity(preRollMs))

    /**
     * SharedFlow drop observability. Frames emitted while no subscribers are
     * active may overflow the extra buffer (capacity 10) and be silently
     * dropped by [kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST].
     * This counter tracks such events; logged periodically to avoid spam.
     */
    private var sharedFlowDropCount = 0
    private var lastSharedFlowDropLog = 0L

    @Volatile private var running = false

    /**
     * The DESIRED capture state — the level the producer converges to. Written
     * only by [start]/[stop]/[release]; the producer owns the ACTUAL source
     * lifecycle. This split exists so the slow native open
     * (`AudioRecord(...)` + `startRecording()`) runs on the producer thread,
     * never on a main-thread caller: [start] used to open the source under
     * [producerLock] on the watchdog/unmute/power paths and could ANR. [running]
     * flips true only once the open completes, so [isRunning] is eventually
     * consistent.
     */
    @Volatile private var wantRunning = false

    /**
     * True while the native source is OPEN — tracked independently of
     * [running] (A3). A producer that gave up after repeated read failures
     * leaves `running == false` while the AudioRecord may still be open, so a
     * [stop] in that state must still release the mic. Mutated only under
     * [producerLock] (see [closeSourceLocked]).
     */
    @Volatile private var sourceOpen = false

    /**
     * True when the producer exited after [GIVE_UP_AFTER_CONSECUTIVE_FAILURES]
     * consecutive read failures (audit #25). Distinct from a clean stop
     * ([stop]) or a source-unavailable exit: only a give-up means "the source
     * itself is failing repeatedly", which is exactly the condition the
     * service watchdog should retry. Cleared by a successful [start].
     */
    @Volatile private var gaveUp = false

    private var producerJob: Job? = null

    /**
     * Serializes [producerJob] hand-offs (audit #11): [start] can be called
     * from the init thread, a binder thread (unmute) and the power receiver,
     * and two overlapping `ensureProducer()` calls used to race their
     * `isActive` check and launch DUPLICATE capture coroutines (double
     * AudioRecord reads, doubled frame flow). Monitor-only, never held
     * across suspension.
     */
    private val producerLock = Any()

    init {
        ensureProducer()
    }

    private fun ensureProducer() {
        synchronized(producerLock) {
            if (producerJob?.isActive == true) return
            producerJob = scope.launch { runProducer() }
        }
    }

    /**
     * Releases the native source exactly once. MUST be called while holding
     * [producerLock] — the `Locked` suffix is the contract. Idempotent: a
     * second call is a no-op, which is what makes the stop/release paths safe
     * to race the producer's publish step. [AudioSource.stop] is
     * non-suspending, so this is safe inside `synchronized`.
     */
    private fun closeSourceLocked() {
        if (sourceOpen) {
            sourceOpen = false
            source.stop()
        }
    }

    /**
     * Converges the ACTUAL capture state to the DESIRED state ([wantRunning])
     * and reports whether the producer may read this iteration.
     *
     * The slow native open (`AudioRecord(...)` + `startRecording()`) runs HERE,
     * on the producer coroutine — never on a caller thread — which is what
     * removes the ANR from the watchdog/unmute/power paths. It deliberately
     * runs OUTSIDE [producerLock] so a main-thread [stop] cannot queue behind a
     * stalled HAL open; the publish step then re-reads [wantRunning] under the
     * monitor, so a stop that landed mid-open is never published as running.
     *
     * A failed open mirrors the give-up path (`gaveUp = true`) so the service
     * watchdog's revive machinery engages instead of failing silently.
     */
    private suspend fun reconcileDesire(): Boolean {
        if (!wantRunning) {
            // Withdrawn desire: close an open source exactly once, then park.
            // source.stop() is non-suspending, so the monitor may be held here.
            if (running || sourceOpen) {
                synchronized(producerLock) {
                    running = false
                    closeSourceLocked()
                }
            }
            delay(PRODUCER_IDLE_PARK_MS)
            return false
        }
        if (running) return true
        val opened = try {
            source.start()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // WARN, content-free: the reason is a device/HAL condition, and the
            // exception text is the standard diagnostic precedent in this file.
            Timber.w(e, "AudioPipeline: audio source open failed — retrying")
            false
        }
        if (!opened) {
            running = false
            gaveUp = true
            delay(READ_RETRY_DELAY_MS)
            return false
        }
        sourceOpen = true
        synchronized(producerLock) {
            // Re-read the DESIRED state under the monitor: a stop()/release()
            // that landed while the open was in flight must not be published as
            // running, or the mic would stay open while the UI reports muted.
            if (wantRunning) {
                running = true
            } else {
                running = false
                closeSourceLocked()
            }
        }
        return running
    }

    private suspend fun runProducer() {
        var loggedEvictions = 0L
        var consecutiveFailures = 0
        while (coroutineContext.isActive) {
            if (!reconcileDesire()) continue
            try {
                val raw = source.read()
                if (raw.isNotEmpty()) {
                    consecutiveFailures = 0
                    // Software AEC first — ring buffer AND flow get the clean
                    // frame. Bypass returns the input instance unchanged
                    // (copied immediately below).
                    val frame = echoCanceller?.process(raw) ?: raw
                    // Single defensive copy shared by ring buffer and flow.
                    val snapshot = frame.copyOf()
                    ringBuffer.add(snapshot)
                    val evicted = ringBuffer.evictionCount
                    if (evicted > loggedEvictions &&
                        (loggedEvictions == 0L || evicted - loggedEvictions >= EVICTION_LOG_STRIDE)
                    ) {
                        loggedEvictions = evicted
                        Timber.w(
                            "Pre-roll overflow: %d unread frames evicted so far (capacity=%d frames)",
                            evicted,
                            ringBuffer.capacity,
                        )
                    }
                    _frames.emit(snapshot)
                    // Track frames emitted with no active subscribers — these
                    // may overflow the SharedFlow buffer and be silently
                    // dropped by DROP_OLDEST. Log periodically for observability.
                    if (_frames.subscriptionCount.value == 0) {
                        sharedFlowDropCount++
                        val now = System.currentTimeMillis()
                        if (sharedFlowDropCount >= 100 || now - lastSharedFlowDropLog >= 5_000) {
                            Timber.w(
                                "SharedFlow: %d frames emitted with no active subscribers (potential drops)",
                                sharedFlowDropCount,
                            )
                            lastSharedFlowDropLog = now
                            sharedFlowDropCount = 0
                        }
                    }
                } else {
                    // Empty frame (source had no data yet — AudioRecord.read
                    // returning 0, an honest non-error). A bare `continue`
                    // here hot-spins the producer loop when a driver keeps
                    // returning zero-length reads: park one frame interval
                    // (~20 ms) so the loop paces itself without counting the
                    // read as a failure.
                    delay(FRAME_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                // Source not started / closed underneath us (m5): producing is
                // pointless until the next start(); exit cleanly, once.
                // Log FIRST: isRunning()==false must be the LAST observable
                // event, so observers never miss this line.
                Timber.w(e, "Audio source unavailable — pipeline producer exiting cleanly")
                synchronized(producerLock) {
                    wantRunning = false
                    running = false
                    closeSourceLocked()
                }
                return
            } catch (e: Exception) {
                consecutiveFailures++
                if (consecutiveFailures >= GIVE_UP_AFTER_CONSECUTIVE_FAILURES) {
                    Timber.e(e, "AudioPipeline: %d consecutive failures, giving up", consecutiveFailures)
                    // #25: leave observable, actionable state behind. With
                    // running still true the pipeline REPORTED active while
                    // producing nothing, and the only revival path (an
                    // external start()) never fires on its own. running=false
                    // + gaveUp=true lets the service watchdog (15-min ping)
                    // distinguish "source is failing" from "user stopped" and
                    // revive the capture on the next tick.
                    synchronized(producerLock) {
                        wantRunning = false
                        running = false
                        gaveUp = true
                        closeSourceLocked()
                    }
                    return
                }
                Timber.w(e, "AudioPipeline read error (attempt %d)", consecutiveFailures)
                delay(READ_RETRY_DELAY_MS)
            }
        }
    }

    fun start() {
        synchronized(producerLock) {
            // Level-triggered desire only: do NOT open the source here. The
            // producer performs the slow native open off the caller thread, so
            // a main-thread caller (watchdog revive, unmute, power receiver)
            // cannot ANR. isRunning() turns true only once the open completes.
            wantRunning = true
            gaveUp = false // a successful start clears the give-up flag
        }
        // Revive a producer that exited cleanly on an unavailable source.
        ensureProducer()
    }

    /**
     * Stops capture. [producerLock] serializes the job/lifecycle HAND-OFF
     * against [start] and [release] — it deliberately does NOT and MUST NOT
     * cover the producer's blocking `source.read()` (that would let a stalled
     * HAL park a binder/main-thread caller).
     *
     * P1-S #4: read-vs-teardown safety is the SOURCE's job — [AudioRecordSource]
     * counts in-flight native reads and defers the record release to the last
     * one, so [AudioSource.stop] returns promptly without ever pulling the
     * record out from under a reader.
     */
    fun stop() {
        synchronized(producerLock) {
            wantRunning = false
            // Close based on sourceOpen, NOT running: after a give-up the
            // producer leaves running=false while the AudioRecord is still
            // open, and the user's stop must actually release the mic (A3).
            running = false
            closeSourceLocked()
        }
    }

    fun isRunning(): Boolean = running

    /** True when the producer gave up after repeated read failures (audit #25). */
    fun hasGivenUp(): Boolean = gaveUp

    fun release() {
        synchronized(producerLock) {
            wantRunning = false
            running = false
            gaveUp = false
            closeSourceLocked()
            producerJob?.cancel()
            producerJob = null
        }
    }
}
