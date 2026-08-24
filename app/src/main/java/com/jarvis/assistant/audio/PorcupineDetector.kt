package com.jarvis.assistant.audio

import ai.picovoice.porcupine.Porcupine
import android.content.Context
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.contracts.Detection
import com.jarvis.assistant.contracts.WakeWordDetector
import com.jarvis.assistant.util.SampleAccumulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Single owner of the Porcupine wake-word engine.
 *
 * A SINGLE actor coroutine collects frames from the shared [frames] flow and
 * calls [PorcupineManager.process] under a [Mutex] (FIX for defect #3: removes
 * the concurrent-process race). Detections are emitted as [Detection.WakeWord];
 * [com.jarvis.assistant.SessionManager] reinterprets them as
 * [Detection.BargeIn] when the assistant is SPEAKING.
 */
class PorcupineDetector(
    private val frames: Flow<ShortArray>,
    private val context: Context,
    private val accessKey: String = BuildConfig.PICOVOICE_KEY,
    private val keywordPath: String = "jarvis_ru.ppn",
    private val sensitivity: Float = 0.6f
) : WakeWordDetector {

    private val detectionsFlow = MutableSharedFlow<Detection>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    private val processMutex = Mutex()
    private var porcupine: Porcupine? = null
    private var actorJob: Job? = null

    init {
        porcupine = try {
            Porcupine.Builder()
                .setAccessKey(accessKey)
                .setKeywordPath(keywordPath)
                .setSensitivity(sensitivity)
                .build(context)
        } catch (e: Exception) {
            Timber.e(e, "Porcupine init failed — wake word disabled")
            null
        }
        if (porcupine != null) {
            // SINGLE actor coroutine: the only place that calls process().
            // The mic pipeline emits 320-sample (20 ms) frames to satisfy Silero
            // VAD's FRAME_SIZE_20_MS, but Porcupine requires its native 512-sample
            // frame at 16 kHz, so we re-chunk using a reusable accumulator that
            // avoids per-frame array allocations.
            actorJob = CoroutineScope(Dispatchers.Default).launch {
                val accumulator = SampleAccumulator(512)
                frames.collect { frame ->
                    if (!isActive) return@collect
                    accumulator.append(frame)
                    var chunk = accumulator.take()
                    while (chunk != null) {
                        val result = processMutex.withLock {
                            porcupine?.process(chunk) ?: -1
                        }
                        if (result >= 0) {
                            detectionsFlow.emit(Detection.WakeWord)
                        }
                        chunk = accumulator.take()
                    }
                }
            }
        }
    }

    override fun detections(): Flow<Detection> = detectionsFlow

    override fun release() {
        actorJob?.cancel()
        actorJob = null
        try {
            porcupine?.delete()
        } finally {
            porcupine = null
        }
    }
}
