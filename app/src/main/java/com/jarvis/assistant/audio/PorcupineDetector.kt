package com.jarvis.assistant.audio

import ai.picovoice.porcupine.Porcupine
import android.content.Context
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.contracts.Detection
import com.jarvis.assistant.contracts.WakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        porcupine = Porcupine.Builder()
            .setAccessKey(accessKey)
            .setKeywordPath(keywordPath)
            .setSensitivity(sensitivity)
            .build(context)

        // SINGLE actor coroutine: the only place that calls process().
        // The mic pipeline emits 320-sample (20 ms) frames to satisfy Silero
        // VAD's FRAME_SIZE_20_MS, but Porcupine requires its native 512-sample
        // frame at 16 kHz, so we buffer and re-chunk here before process().
        actorJob = CoroutineScope(Dispatchers.Default).launch {
            val porcupineFrameLength = 512
            var buffer = ShortArray(0)
            frames.collect { frame ->
                if (!isActive) return@collect
                buffer = buffer + frame
                while (buffer.size >= porcupineFrameLength) {
                    val chunk = buffer.copyOfRange(0, porcupineFrameLength)
                    buffer = buffer.copyOfRange(porcupineFrameLength, buffer.size)
                    val result = processMutex.withLock {
                        porcupine?.process(chunk) ?: -1
                    }
                    if (result >= 0) {
                        detectionsFlow.emit(Detection.WakeWord)
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
