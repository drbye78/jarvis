package com.jarvis.assistant.session

import com.jarvis.assistant.audio.AudioPipeline
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.contracts.Detection
import com.jarvis.assistant.contracts.DetectorState
import com.jarvis.assistant.contracts.WakeWordDetector
import com.jarvis.assistant.contracts.BargeInPolicy
import com.jarvis.assistant.contracts.gatedBy
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.speech.asr.StreamingAsrClient
import com.jarvis.assistant.speech.tts.TtsClient
import com.jarvis.assistant.speech.tts.TtsPlayer
import com.jarvis.assistant.tools.ToolExecutor
import com.jarvis.assistant.data.ConversationManager
import com.jarvis.assistant.util.NetworkMonitor
import com.jarvis.assistant.util.OnlineChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates a full voice turn with TRUE STREAMING ASR:
 *
 *   WakeWord -> open ASR stream -> live mic audio -> server EOU ->
 *   LLM (iterative tool loop) -> per-sentence TTS -> drain -> IDLE
 *
 * The per-turn execution body (open ASR -> collect partials -> iterative LLM
 * tool loop -> per-sentence TTS w/ prefetch -> drain -> IDLE) lives in
 * [TurnRunner], which is constructed once here and reached via [startSession].
 * This class keeps the single source of truth for the state machine, the error
 * funnel ([reportFailure], M6), the live partial transcript, and mute intent,
 * and exposes them to [TurnRunner] through the four injected callbacks.
 *
 * Key guarantees:
 * - **Barge-in**: wake word in ANY state cancels the session, flushes the
 *   player's queue (generation bump) and cancels ASR/TTS/LLM transports.
 * - **Tool loop**: iterative with a bounded number of passes; assistant
 *   tool_calls and tool results are persisted with tool_call_id linkage and
 *   serialized through the wire layer (snake_case) on every subsequent pass.
 * - **Timeouts everywhere**: LLM total, TTS per sentence, ASR hard cap.
 * - A [sessionSeq] guard prevents a stale session's terminal transition OR
 *   late failure from clobbering the new session's state; all failures funnel
 *   through [reportFailure] (M6). cancelAll() performs an explicit guarded
 *   reset of the state machine to IDLE.
 * - Live ASR partials are published on [partialTranscript] (S1); mic muting
 *   is a user intent exposed via [setMuted]/[muted] (m12).
 */
class SessionManager(
    private val audioPipeline: AudioPipeline,
    private val wakeWordDetector: WakeWordDetector,
    private val asrClient: StreamingAsrClient,
    private val llm: LlmClient,
    private val ttsClient: TtsClient,
    private val player: TtsPlayer,
    private val functionRouter: ToolExecutor,
    private val conversationManager: ConversationManager,
    private val stateMachine: SessionStateMachine,
    private val networkMonitor: OnlineChecker,
    private val config: JarvisConfig,
    private val scope: CoroutineScope,
) {

    private var sessionJob: Job? = null
    private var detectionJob: Job? = null
    private val sessionSeq = AtomicInteger(0)

    /** S1: live ASR partials for the UI (UI wiring happens in a later phase). */
    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    /** m12: user mute intent; survives power-receiver restarts. */
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    /**
     * Per-turn execution engine (P7). Constructed once; [startSession] drives
     * it. The four callbacks route state events, failures, terminal
     * transitions and partial updates back into this class so the single
     * source of truth stays here.
     */
    private val turnRunner = TurnRunner(
        audioPipeline, asrClient, llm, ttsClient, player, functionRouter,
        conversationManager, config,
            stateMachine::onEvent, this::reportFailure, this::finish,
            { _partialTranscript.value = it },
            isCurrentSession = { it == sessionSeq.get() },
        )

    private var onErrorHandler: suspend (String) -> Unit = {}

    fun setOnError(handler: suspend (String) -> Unit) {
        onErrorHandler = handler
    }

    /**
     * M6: THE single funnel for every user-visible failure. A failure carrying
     * a session id is LOGGED AND DROPPED when that session has been superseded
     * — the shared state machine and the error voice belong to the newest
     * session only, and a stale session's late failure must not yank them.
     * Pass id=null for lifecycle-scoped failures (wake-word engine) that have
     * no session identity of their own.
     */
    internal suspend fun reportFailure(id: Int?, message: String) {
        if (id != null && id != sessionSeq.get()) {
            Timber.w("Dropping stale session %d failure: %s", id, message)
            return
        }
        Timber.e("Session failure: %s", message)
        _partialTranscript.value = ""
        stateMachine.onEvent(SessionEvent.ErrorOccurred)
        onErrorHandler(message)
    }

    // ------------------------------------------------------------------
    // Public control surface
    // ------------------------------------------------------------------

    /** Start the wake-word collector. Idempotent. */
    fun startListening() {
        detectionJob?.cancel()
        // M1: an init failure is emitted into a SharedFlow nobody subscribes
        // to yet, so it would be dropped silently. Read the detector state
        // synchronously and route a dead engine into the error path instead
        // of running deaf.
        val detectorState = wakeWordDetector.state.value
        if (detectorState is DetectorState.Failed) {
            scope.launch {
                // No session identity: lifecycle-scoped failure (id = null).
                reportFailure(null, "Ошибка движка wake word: ${detectorState.reason}")
            }
            return
        }
        detectionJob = scope.launch {
            wakeWordDetector.detections()
                .gatedBy(BargeInPolicy.from(config), stateMachine.state)
                .collect { detection ->
                    when (detection) {
                        is Detection.DetectorError -> {
                            reportFailure(null, "Ошибка движка wake word: ${detection.message}")
                        }

                        Detection.WakeWord -> {
                            startSession()
                        }
                    }
                }
        }
    }

    /** Begin (or restart) a listening session — also the barge-in entry point. */
    fun startSession() {
        sessionJob?.cancel()
        player.flush() // generation bump: current + queued sentences die
        val id = sessionSeq.incrementAndGet()
        _partialTranscript.value = "" // fresh utterance, drop any stale partial
        sessionJob = scope.launch {
            if (!networkMonitor.isCurrentlyOnline()) {
                stateMachine.onEvent(SessionEvent.WakeWordOrBargeIn)
                reportFailure(id, "Нет подключения к интернету. Проверьте сеть.")
                return@launch
            }
            with(turnRunner) {
                runTurn(id)
            }
        }
    }

    fun cancelAll() {
        val seqBefore = sessionSeq.get()
        sessionJob?.cancel()
        sessionJob = null
        detectionJob?.cancel()
        detectionJob = null
        _partialTranscript.value = ""
        // M6: cancellation itself emits no terminal event, so without this the
        // machine stays wedged in THINKING/SPEAKING forever. Guarded: if a new
        // session started concurrently (seq moved on), do not stomp its fresh
        // LISTENING state back to IDLE.
        if (sessionSeq.get() == seqBefore) {
            stateMachine.onEvent(SessionEvent.Cancelled)
        }
    }

    /** Terminal transition, guarded against stale sessions. */
    private fun finish(id: Int) {
        if (id != sessionSeq.get()) return
        _partialTranscript.value = "" // session end clears any live partial
        stateMachine.onEvent(SessionEvent.LlmDone)
    }

    // ------------------------------------------------------------------
    // Mute (m12)
    // ------------------------------------------------------------------

    /**
     * Mic mute is a USER intent with session-level consequences: muting stops
     * the audio pipeline AND cancels any active session (a muted assistant
     * must not keep answering). Unmuting restores both. Idempotent. The
     * service's binder exposes this so UI can call it in a later phase.
     */
    fun setMuted(muted: Boolean) {
        _muted.value = muted
        if (muted) {
            audioPipeline.stop()
            cancelAll()
        } else {
            audioPipeline.start()
            startListening() // restore wake-word collection killed by cancelAll
        }
    }

    /**
     * Called by the service's power receiver on ACTION_POWER_CONNECTED:
     * restart the mic pipeline UNLESS the user muted it — a receiver restart
     * must never silently undo a user's mute.
     */
    fun onPowerConnected() {
        if (!_muted.value) audioPipeline.start()
    }
}
