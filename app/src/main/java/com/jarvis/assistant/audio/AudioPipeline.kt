package com.jarvis.assistant.audio

import com.jarvis.assistant.contracts.AudioSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Owns an [AudioSource] and a SINGLE producer coroutine that is the only code
 * path allowed to touch the underlying [AudioRecord] (FIX #1).
 *
 * Every captured frame is (a) appended to a small [RingBuffer] so a
 * late-subscribing VAD collector can recover pre-subscription frames, and
 * (b) emitted on [frames] as a defensive copy.
 */
class AudioPipeline(
    private val scope: CoroutineScope,
    private val source: AudioSource
) {
    private val _frames = MutableSharedFlow<ShortArray>(
        extraBufferCapacity = 10,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    /** Public, read-only view of the captured frames. */
    val frames: Flow<ShortArray> = _frames

    val ringBuffer = AudioRingBuffer(8)

    @Volatile private var running = false
    private var producerJob: Job? = null

    init {
        // The producer is launched in the supplied scope but only reads while
        // [running] is true, so start()/stop() gate the actual capture.
        producerJob = scope.launch {
            while (isActive) {
                if (!running) {
                    kotlinx.coroutines.delay(10)
                    continue
                }
                try {
                    val frame = source.read()
                    if (frame.isNotEmpty()) {
                        ringBuffer.add(frame)
                        _frames.emit(frame.copyOf())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "AudioPipeline read error")
                    kotlinx.coroutines.delay(100)
                }
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        source.start()
    }

    fun stop() {
        if (!running) return
        running = false
        source.stop()
    }

    fun release() {
        running = false
        source.stop()
        producerJob?.cancel()
        producerJob = null
    }
}
