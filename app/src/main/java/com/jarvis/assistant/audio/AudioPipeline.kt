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
 * Fix for the ring-buffer aliasing defect: [AudioRecordSource.read] returns
 * references to two reused internal buffers, so every frame is defensively
 * copied ONCE here, and the same snapshot is shared (read-only) between the
 * ring buffer and the SharedFlow. Previously the ring buffer retained aliased
 * buffers that were overwritten by the next reads, corrupting the
 * pre-subscription recovery audio fed to ASR.
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
    private var producerJob: Job? = null

    init {
        ensureProducer()
    }

    private fun ensureProducer() {
        if (producerJob?.isActive == true) return
        producerJob = scope.launch { runProducer() }
    }

    private suspend fun runProducer() {
        var loggedEvictions = 0L
        while (coroutineContext.isActive) {
            if (!running) {
                delay(10)
                continue
            }
            try {
                val raw = source.read()
                if (raw.isNotEmpty()) {
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
                Timber.w(e, "AudioPipeline read error")
                delay(100)
            }
        }
    }

    fun start() {
        if (!running) {
            running = true
            source.start()
        }
        // Revive a producer that exited cleanly on an unavailable source.
        ensureProducer()
    }

    fun stop() {
        if (!running) return
        running = false
        source.stop()
    }

    fun isRunning(): Boolean = running

    fun release() {
        running = false
        source.stop()
        producerJob?.cancel()
        producerJob = null
    }
}
