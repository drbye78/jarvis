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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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
import kotlinx.coroutines.cancel
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
 * Initialisation is ASYNC and OFF the calling thread (H1): the native engine
 * build (especially Sherpa-ONNX, which loads a ~17 MB transducer model) used to
 * run synchronously inside the constructor and could block the main thread long
 * enough to trip an ANR on low-end devices (Kirin 710A class). The build now
 * runs on [engineBuildDispatcher] (default [Dispatchers.Default]); the detector
 * starts in [DetectorState.Bootstrapping] and transitions to [DetectorState.Ready]
 * (or [DetectorState.Failed]) when the engine is ready. The frame-processing
 * actor is only started once an engine is published, so no frame is ever
 * processed by a half-built engine.
 *
 * Fixes preserved:
 * - Silent-init-failure defect (M1): if the engine cannot initialise the
 *   detector emits [Detection.DetectorError] AND flips [state] to
 *   [DetectorState.Failed] — the StateFlow is readable synchronously even though
 *   nobody has subscribed to the SharedFlow yet. SessionManager checks it in
 *   startListening.
 * - Use-after-free teardown (C3): [release] cancels the scope, joins the actor
 *   with a bounded wait, and only then deletes the native engine under
 *   [processMutex], so an in-flight process() can never touch a freed engine.
 * - A build cancelled by [release]/lifecycle teardown (M2): the native build
 *   runs under [NonCancellable], so a cancelled caller cannot orphan a built
 *   native engine; a published/swapped engine is dropped (released) instead of
 *   leaked when the detector is already [DetectorState.Released].
 *
 * @param context only needed by the default native engine factory; tests
 *   injecting [engineFactory] may pass null.
 * @param engineBuildDispatcher dispatcher used for the (potentially heavy)
 *   native engine build AND the detector's coroutine scope. Tests inject
 *   [Dispatchers.Unconfined] to keep the synchronous init contract asserted by
 *   the unit tests.
 */
class HybridWakeWordDetector(
    private val frames: Flow<ShortArray>,
    context: Context?,
    initialReq: WakeWordRequest,
    engineFactory: ((WakeWordRequest) -> WakeWordEngine)? = null,
    private val engineBuildDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : WakeWordDetector {

    private val realContext = context

    /** Injectable seam (tests); defaults to building the real engine. */
    private val engineFactory: (WakeWordRequest) -> WakeWordEngine = engineFactory
        ?: { req -> buildEngine(req) }

    private val scope = CoroutineScope(
        SupervisorJob() + engineBuildDispatcher + CoroutineExceptionHandler { _, e ->
            Timber.e(e, "Uncaught exception in HybridWakeWordDetector scope")
        },
    )

    private val detectionsFlow = MutableSharedFlow<Detection>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    private val _state = MutableStateFlow<DetectorState>(DetectorState.Bootstrapping)
    override val state: StateFlow<DetectorState> = _state

    private val processMutex = Mutex()
    // L4: serialize concurrent engine builds (initial build + reconfigure) so
    // native engine builds don't pile up on top of each other.
    private val reconfigureMutex = Mutex()
    private var engine: WakeWordEngine? = null
    private var actorJob: Job? = null

    // Live, reconfigurable request (updated by [reconfigure]).
    private var currentReq: WakeWordRequest = initialReq

    init {
        // H1: do NOT build on the calling thread. Kick off the async build;
        // the detector is observable as Bootstrapping until it completes.
        _state.value = DetectorState.Bootstrapping
        scope.launch { buildAndSwap(initialReq) }
    }

    override fun detections(): Flow<Detection> = detectionsFlow

    /**
     * Build [req]'s engine off the calling thread and publish it atomically.
     * Safe to call from the initial [init] launch or from [reconfigure] (which
     * may itself run in a cancellable lifecycle scope — the build is shielded by
     * [NonCancellable] so a cancelled caller cannot orphan a native engine).
     */
    private suspend fun buildAndSwap(req: WakeWordRequest) {
        // M2 + L4: build under NonCancellable AND hold reconfigureMutex so two
        // heavy native builds (initial vs reconfigure, or a slider drag) cannot
        // run concurrently and double the peak native RAM on a low-end device.
        val built = reconfigureMutex.withLock {
            withContext(NonCancellable + engineBuildDispatcher) {
                try {
                    engineFactory(req)
                } catch (e: Exception) {
                    Timber.e(e, "Wake-word engine build failed — wake word disabled")
                    null
                }
            }
        }

        if (built == null) {
            // M1: surface the failure readably + via the event flow — but ONLY
            // when there is no engine currently serving detections. A failed
            // reconfigure while a working engine exists must keep that engine
            // (and the actor) alive instead of going deaf.
            withContext(NonCancellable) {
                if (_state.value != DetectorState.Released && engine == null) {
                    val reason = when {
                        req.engine == "sherpa" ->
                            "Sherpa model failed to load (bundled assets missing)"
                        !CredentialsStore.isInitialized || CredentialsStore.picovoiceKey.isBlank() ->
                            "Picovoice access key is missing (set it in Settings → Настройки)"
                        else -> "Wake-word model failed to load. Check that jarvis_ru.ppn is in app assets."
                    }
                    _state.value = DetectorState.Failed(reason)
                    // Belt and suspenders: also surface through the event flow.
                    detectionsFlow.tryEmit(Detection.DetectorError(reason))
                }
            }
            return
        }

        // Swap (or drop) the engine atomically. NonCancellable so a cancelled
        // scope still releases a built-but-unpublishable engine instead of
        // leaking it (M2).
        val published = withContext(NonCancellable) {
            reconfigureMutex.withLock {
                processMutex.withLock {
                    if (_state.value == DetectorState.Released) {
                        false
                    } else {
                        val old = engine
                        engine = built
                        currentReq = req
                        _state.value = DetectorState.Ready
                        // DEFECT 1: release the displaced engine so a reconfigure /
                        // sensitivity change never orphans a native engine.
                        runCatching { old?.release() }
                        true
                    }
                }
            }
        }

        if (!published) {
            runCatching { built.release() }
            return
        }

        // Start the frame-processing actor once there is a live engine. This
        // also covers recovery from a failed initial build (actorJob still
        // null) and from a runtime crash (the crashed Job is completed, not
        // active, so we restart it) — the detector is never left deaf while
        // Ready.
        if (actorJob?.isActive != true) {
            actorJob = scope.launch { runActorLoop() }
        }
    }

    /**
     * Change the active wake-word engine/model and/or sensitivity live. The new
     * engine is built first (off the calling thread) and only swapped under
     * [processMutex] once it is ready, and the previous engine is released only
     * after the new one is in place — so no in-flight [WakeWordEngine.process]
     * ever touches a half-replaced or freed engine.
     */
    override suspend fun reconfigure(req: WakeWordRequest) {
        if (_state.value == DetectorState.Released) return
        buildAndSwap(req)
    }

    /**
     * Build the concrete engine for a request. A missing [WakeWordRequest.sherpaModelDir]
     * throws for the Sherpa path; the default [engineFactory] surfaces that as
     * a [DetectorState.Failed] (see [buildAndSwap]), so callers never see a
     * half-built detector.
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

    /**
     * Frame-processing actor. Re-chunks 320-sample mic frames into 512-sample
     * chunks and forwards them to the current engine. Runs for the detector's
     * whole lifetime once an engine is published; tolerates a runtime engine
     * crash by surfacing [DetectorState.Failed] + [Detection.DetectorError]
     * instead of dying or going stale-Ready.
     */
    private suspend fun CoroutineScope.runActorLoop() {
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
            // A runtime engine crash must not escape to the uncaught handler
            // (process death) nor leave state stale-Ready.
            Timber.e(e, "Wake-word process failed — wake word disabled")
            val reason = "Wake-word engine failed at runtime: ${e.message}"
            _state.value = DetectorState.Failed(reason)
            detectionsFlow.tryEmit(Detection.DetectorError(reason))
        }
    }

    override fun release() {
        // Snapshot BEFORE nulling: joining via the field after clearing it
        // would join nothing and leave release() unbounded on processMutex.
        val job = actorJob
        scope.cancel() // cancels init + actor; the in-flight build is NonCancellable
        // and either publishes-then-drops (Released) or is released on failure.
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
