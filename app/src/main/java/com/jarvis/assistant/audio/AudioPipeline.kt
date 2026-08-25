package com.jarvis.assistant.audio

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

/**
 * Owns an [AudioSource] and a SINGLE producer coroutine — the only code path
 * allowed to touch the underlying AudioRecord.
 *
 * Fix for the ring-buffer aliasing defect: [AudioRecordSource.read] returns
 * references to two reused internal buffers, so every frame is defensively
 * copied ONCE here, and the same snapshot is shared (read-only) between the
 * ring buffer and the SharedFlow. Previously the ring buffer retained aliased
 * buffers that were overwritten by the next reads, corrupting the
 * pre-subscription recovery audio fed to ASR.
 */
class AudioPipeline(
    private val scope: CoroutineScope,
    private val source: AudioSource,
) {
    private val _frames = MutableSharedFlow<ShortArray>(
        extraBufferCapacity = 10,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    val frames: Flow<ShortArray> = _frames

    val ringBuffer = AudioRingBuffer(8)

    @Volatile private var running = false
    private var producerJob: Job? = null

    init {
        producerJob = scope.launch {
            while (isActive) {
                if (!running) {
                    delay(10)
                    continue
                }
                try {
                    val frame = source.read()
                    if (frame.isNotEmpty()) {
                        // Single defensive copy shared by ring buffer and flow.
                        val snapshot = frame.copyOf()
                        ringBuffer.add(snapshot)
                        _frames.emit(snapshot)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "AudioPipeline read error")
                    delay(100)
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

    fun isRunning(): Boolean = running

    fun release() {
        running = false
        source.stop()
        producerJob?.cancel()
        producerJob = null
    }
}
