package com.jarvis.assistant.audio

import ai.picovoice.porcupine.Porcupine
import android.content.Context
import com.jarvis.assistant.contracts.Detection
import com.jarvis.assistant.contracts.DetectorState
import com.jarvis.assistant.contracts.WakeWordDetector
import com.jarvis.assistant.util.SampleAccumulator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Seam over the native Porcupine engine so JVM tests can inject a fake and
 * verify teardown ordering (release-race regression).
 */
interface PorcupineEngine {
    /** Returns the detected keyword index (>= 0) or -1 for no detection. */
    fun process(chunk: ShortArray): Int
    fun release()
}

/** Real engine wrapping the native Porcupine object. */
private class NativePorcupineEngine(private val porcupine: Porcupine) : PorcupineEngine {
    override fun process(chunk: ShortArray): Int = porcupine.process(chunk)
    override fun release() {
        porcupine.delete()
    }
}

/**
 * Single owner of the Porcupine wake-word engine.
 *
 * ONE actor coroutine collects frames from the shared pipeline flow and calls
 * [PorcupineEngine.process] under a [Mutex]. Mic frames are 320 samples;
 * Porcupine wants 512-sample frames at 16 kHz, so frames are re-chunked
 * through a reusable [SampleAccumulator].
 *
 * Fixes:
 * - Silent-init-failure defect (M1): when Porcupine cannot initialize (missing
 *   .ppn asset, invalid access key) the detector emits
 *   [Detection.DetectorError] AND flips [state] to [DetectorState.Failed] —
 *   the StateFlow is readable synchronously even though nobody has subscribed
 *   to the SharedFlow yet. SessionManager checks it in startListening.
 * - Use-after-free teardown (C3): [release] cancels the actor, joins it with a
 *   bounded wait, and only then deletes the native engine under [processMutex],
 *   so an in-flight process() can never touch a freed engine.
 *
 * @param context only needed by the default native engine factory; tests
 *   injecting [engineFactory] may pass null.
 */
class PorcupineDetector(
    private val frames: Flow<ShortArray>,
    context: Context?,
    private val keywordPath: String? = null,
    private val sensitivity: Float = 0.6f,
    // Live, reconfigurable model path (read by the default engine factory and
    // updated by [reconfigure] so the wake word can swap at runtime).
    private var keywordPathField: String? = keywordPath,
    private val engineFactory: (sensitivity: Float) -> PorcupineEngine = { s ->
        NativePorcupineEngine(
            Porcupine.Builder()
                .setAccessKey(com.jarvis.assistant.util.CredentialsStore.picovoiceKey)
                .apply {
                    if (keywordPathField != null) setKeywordPath(keywordPathField!!)
                    else setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                }
                .setSensitivity(s)
                .build(requireNotNull(context) { "Context required for native Porcupine init" })
        )
    },
) : WakeWordDetector {

    private val detectionsFlow = MutableSharedFlow<Detection>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    private val _state = MutableStateFlow<DetectorState>(DetectorState.Bootstrapping)
    override val state: StateFlow<DetectorState> = _state

    private val processMutex = Mutex()
    private var engine: PorcupineEngine? = null
    private var actorJob: Job? = null

    // Live, reconfigurable sensitivity (updated by [reconfigure]).
    private var sensitivityField: Float = sensitivity

    init {
        engine = try {
            engineFactory(sensitivity)
        } catch (e: Exception) {
            Timber.e(e, "Porcupine init failed — wake word disabled")
            null
        }

        if (engine == null) {
            val reason = when {
                !com.jarvis.assistant.util.CredentialsStore.isInitialized ||
                    com.jarvis.assistant.util.CredentialsStore.picovoiceKey.isBlank() ->
                    "Picovoice access key is missing (set it in Settings → Настройки)"
                else -> "Wake-word model failed to load. Check that jarvis_ru.ppn is in app assets."
            }
            _state.value = DetectorState.Failed(reason)
            // Belt and suspenders: also surface through the event flow.
            detectionsFlow.tryEmit(Detection.DetectorError(reason))
        } else {
            _state.value = DetectorState.Ready
            actorJob = CoroutineScope(Dispatchers.Default).launch {
                try {
                    val accumulator = SampleAccumulator(512)
                    frames.collect { frame ->
                        if (!isActive) return@collect
                        accumulator.append(frame)
                        var chunk = accumulator.take()
                        while (chunk != null) {
                            val result = processMutex.withLock {
                                engine?.process(chunk) ?: -1
                            }
                            if (result >= 0) {
                                detectionsFlow.emit(Detection.WakeWord)
                            }
                            chunk = accumulator.take()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e // normal teardown (release/barge-in), not a failure
                } catch (e: Exception) {
                    // A runtime engine crash must not escape to the uncaught
                    // handler (process death) nor leave state stale-Ready:
                    // mirror the init-failure behavior and stop processing.
                    Timber.e(e, "Porcupine process failed — wake word disabled")
                    val reason = "Wake-word engine failed at runtime: ${e.message}"
                    _state.value = DetectorState.Failed(reason)
                    detectionsFlow.tryEmit(Detection.DetectorError(reason))
                }
            }
        }
    }

    override fun detections(): Flow<Detection> = detectionsFlow

    /**
     * Change the active wake-word model and/or sensitivity live. The new engine
     * is built first and only swapped under [processMutex] once it is ready,
     * and the previous engine is released only after the new one is in place —
     * so no in-flight [PorcupineEngine.process] ever touches a half-replaced
     * or freed engine.
     */
    suspend fun reconfigure(keywordPath: String?, sensitivity: Float) {
        if (_state.value == DetectorState.Released) return
        // Build the native engine OFF the calling (possibly Main) thread.
        val newEngine = withContext(Dispatchers.Default) {
            try {
                buildEngine(keywordPath, sensitivity)
            } catch (e: Exception) {
                Timber.e(e, "Wake-word rebuild failed; keeping current engine")
                null
            }
        } ?: return
        // Swap under the mutex without blocking the UI thread.
        processMutex.withLock {
            val old = engine
            engine = newEngine
            try {
                old?.release()
            } catch (e: Exception) {
                Timber.e(e, "Failed to release old wake-word engine on reconfigure")
            }
        }
    }

    private fun buildEngine(keywordPath: String?, sensitivity: Float): PorcupineEngine {
        // Update the live model fields so the (possibly injected) engine
        // factory builds with the requested model + sensitivity.
        keywordPathField = keywordPath
        sensitivityField = sensitivity
        return engineFactory(sensitivity)
    }

    /** Public compatibility method — rebuilds the engine with a new sensitivity. */
    suspend fun setSensitivity(value: Float) = reconfigure(keywordPathField, value)

    override fun release() {
        // Snapshot BEFORE nulling: joining via the field after clearing it
        // would join nothing and leave release() unbounded on processMutex.
        val job = actorJob
        job?.cancel()
        actorJob = null
        runBlocking {
            // Bounded join: let an in-flight process() finish before freeing
            // the native engine (use-after-free otherwise).
            withTimeoutOrNull(1_000) { job?.join() }
            processMutex.withLock {
                try {
                    engine?.release()
                } finally {
                    engine = null
                }
            }
            _state.value = DetectorState.Released
        }
    }
}
