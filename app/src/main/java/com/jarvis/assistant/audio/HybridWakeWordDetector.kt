package com.jarvis.assistant.audio

import ai.picovoice.porcupine.Porcupine
import android.content.Context
import com.jarvis.assistant.contracts.Detection
import com.jarvis.assistant.contracts.DetectorState
import com.jarvis.assistant.contracts.WakeWordDetector
import com.jarvis.assistant.contracts.WakeWordRequest
import com.jarvis.assistant.util.CredentialsStore
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** Real engine wrapping the native Porcupine object. */
private class PorcupineWakeWordEngine(
    keywordPath: String?,
    sensitivity: Float,
    context: Context?,
) : WakeWordEngine {
    private val porcupine: Porcupine = Porcupine.Builder()
        .setAccessKey(CredentialsStore.picovoiceKey)
        .apply {
            if (keywordPath != null) setKeywordPath(keywordPath)
            else setKeyword(Porcupine.BuiltInKeyword.JARVIS)
        }
        .setSensitivity(sensitivity)
        .build(requireNotNull(context) { "Context required for native Porcupine init" })

    override fun process(chunk: ShortArray): Int = porcupine.process(chunk)
    override fun release() {
        porcupine.delete()
    }
}

/**
 * Single owner of the HYBRID wake-word engine: either Picovoice Porcupine or
 * Sherpa-ONNX Keyword Spotting, selected by [WakeWordRequest.engine].
 *
 * ONE actor coroutine collects frames from the shared pipeline flow and calls
 * [WakeWordEngine.process] under a [Mutex]. Mic frames are 320 samples; both
 * engines want 512-sample frames at 16 kHz, so frames are re-chunked through a
 * reusable [SampleAccumulator].
 *
 * Fixes (preserved from the Porcupine-only predecessor):
 * - Silent-init-failure defect (M1): when the engine cannot initialize the
 *   detector emits [Detection.DetectorError] AND flips [state] to
 *   [DetectorState.Failed] — the StateFlow is readable synchronously even
 *   though nobody has subscribed to the SharedFlow yet. SessionManager checks
 *   it in startListening.
 * - Use-after-free teardown (C3): [release] cancels the actor, joins it with a
 *   bounded wait, and only then deletes the native engine under [processMutex],
 *   so an in-flight process() can never touch a freed engine.
 *
 * @param context only needed by the default native engine factory; tests
 *   injecting [engineFactory] may pass null.
 */
class HybridWakeWordDetector(
    private val frames: Flow<ShortArray>,
    context: Context?,
    initialReq: WakeWordRequest,
    engineFactory: ((WakeWordRequest) -> WakeWordEngine)? = null,
) : WakeWordDetector {

    private val realContext = context

    /** Injectable seam (tests); defaults to building the real engine. */
    private val engineFactory: (WakeWordRequest) -> WakeWordEngine = engineFactory
        ?: { req -> buildEngine(req) }

    private val detectionsFlow = MutableSharedFlow<Detection>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    private val _state = MutableStateFlow<DetectorState>(DetectorState.Bootstrapping)
    override val state: StateFlow<DetectorState> = _state

    private val processMutex = Mutex()
    // L4: serialize concurrent reconfigures (engine switch + sensitivity toggles)
    // so native engine builds don't pile up on top of each other.
    private val reconfigureMutex = Mutex()
    private var engine: WakeWordEngine? = null
    private var actorJob: Job? = null

    // Live, reconfigurable request (updated by [reconfigure]).
    private var currentReq: WakeWordRequest = initialReq

    init {
        engine = try {
            engineFactory(initialReq)
        } catch (e: Exception) {
            Timber.e(e, "Wake-word init failed — wake word disabled")
            null
        }

        if (engine == null) {
            val reason = when {
                initialReq.engine == "sherpa" ->
                    "Sherpa model failed to load (bundled assets missing)"
                !CredentialsStore.isInitialized || CredentialsStore.picovoiceKey.isBlank() ->
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
                    Timber.e(e, "Wake-word process failed — wake word disabled")
                    val reason = "Wake-word engine failed at runtime: ${e.message}"
                    _state.value = DetectorState.Failed(reason)
                    detectionsFlow.tryEmit(Detection.DetectorError(reason))
                }
            }
        }
    }

    override fun detections(): Flow<Detection> = detectionsFlow

    /**
     * Change the active wake-word engine/model and/or sensitivity live. The new
     * engine is built first and only swapped under [processMutex] once it is
     * ready, and the previous engine is released only after the new one is in
     * place — so no in-flight [WakeWordEngine.process] ever touches a
     * half-replaced or freed engine.
     */
    override suspend fun reconfigure(req: WakeWordRequest) {
        if (_state.value == DetectorState.Released) return
        // L4: serialize concurrent reconfigures (engine switch + sensitivity
        // toggles) so native builds don't pile up.
        reconfigureMutex.withLock {
            // Build the native engine OFF the calling (possibly Main) thread,
            // and shield it from caller cancellation (M2): a cancelled
            // lifecycleScope must not orphan a built native engine. Combined
            // with the M1 check below, the engine is always either swapped in
            // or released.
            val newEngine = withContext(NonCancellable + Dispatchers.Default) {
                try {
                    engineFactory(req)
                } catch (e: Exception) {
                    Timber.e(e, "Wake-word rebuild failed; keeping current engine")
                    null
                }
            } ?: return@withLock
            // Swap under the mutex without blocking the UI thread.
            var droppedWhileReleased = false
            processMutex.withLock {
                // M1: if we were released while the (non-cancellable) build was
                // running, drop the freshly built engine instead of publishing a
                // half-replaced detector. The old engine was already released by
                // release().
                if (_state.value == DetectorState.Released) {
                    runCatching { newEngine.release() }
                    droppedWhileReleased = true
                } else {
                    val old = engine
                    engine = newEngine
                    try {
                        old?.release()
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to release old wake-word engine on reconfigure")
                    }
                }
            }
            if (droppedWhileReleased) return@withLock
            currentReq = req
        }
    }

    /**
     * Build the concrete engine for a request. A missing [WakeWordRequest.sherpaModelDir]
     * throws for the Sherpa path; the default [engineFactory] surfaces that as
     * a [DetectorState.Failed] (see init), so callers never see a half-built
     * detector.
     */
    private fun buildEngine(req: WakeWordRequest): WakeWordEngine {
        return if (req.engine == "sherpa") {
            // Sherpa loads the bundled model from assets via RELATIVE paths
            // (Mode A); no model directory is supplied.
            SherpaKwsEngine(
                context = realContext,
                keyword = req.sherpaKeyword,
                sensitivity = req.sensitivity,
            )
        } else {
            PorcupineWakeWordEngine(req.keywordPath, req.sensitivity, realContext)
        }
    }

    /** Public compatibility method — rebuilds the engine with a new sensitivity. */
    override suspend fun setSensitivity(value: Float) =
        reconfigure(currentReq.copy(sensitivity = value))

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
