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

    @Volatile private var running = false

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

    private suspend fun runProducer() {
        var loggedEvictions = 0L
        var consecutiveFailures = 0
        while (coroutineContext.isActive) {
            if (!running) {
                delay(PRODUCER_IDLE_PARK_MS)
                continue
            }
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
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                // Source not started / closed underneath us (m5): producing is
                // pointless until the next start(); exit cleanly, once.
                // Log FIRST: isRunning()==false must be the LAST observable
                // event, so observers never miss this line.
                Timber.w(e, "Audio source unavailable — pipeline producer exiting cleanly")
                running = false
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
                    running = false
                    gaveUp = true
                    return
                }
                Timber.w(e, "AudioPipeline read error (attempt %d)", consecutiveFailures)
                delay(READ_RETRY_DELAY_MS)
            }
        }
    }

    fun start() {
        synchronized(producerLock) {
            if (!running) {
                // Start the SOURCE first, then flip running: if source.start()
                // throws, isRunning() must not claim active with a dead source.
                // The exception still propagates to the caller (unchanged
                // contract — e.g. AppGraph.start() turns it into a retryable
                // init failure).
                source.start()
                running = true
            }
            gaveUp = false // a successful start clears the give-up flag
        }
        // Revive a producer that exited cleanly on an unavailable source.
        ensureProducer()
    }

    fun stop() {
        synchronized(producerLock) {
            if (!running) return
            running = false
            source.stop()
        }
    }

    fun isRunning(): Boolean = running

    /** True when the producer gave up after repeated read failures (audit #25). */
    fun hasGivenUp(): Boolean = gaveUp

    fun release() {
        synchronized(producerLock) {
            running = false
            gaveUp = false
            source.stop()
            producerJob?.cancel()
            producerJob = null
        }
    }
}
