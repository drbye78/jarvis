package com.jarvis.assistant.session

import com.jarvis.assistant.audio.AudioPipeline
import com.jarvis.assistant.audio.aec.EnergyVad
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.contracts.BargeInPolicy
import com.jarvis.assistant.contracts.Detection
import com.jarvis.assistant.contracts.DetectorState
import com.jarvis.assistant.contracts.WakeWordDetector
import com.jarvis.assistant.contracts.gatedBy
import com.jarvis.assistant.data.ConversationManager
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.model.AssistantState
import com.jarvis.assistant.model.Message
import com.jarvis.assistant.speech.asr.StreamingAsrClient
import com.jarvis.assistant.speech.tts.TtsClient
import com.jarvis.assistant.speech.tts.TtsPlayer
import com.jarvis.assistant.tools.ToolExecutor
import com.jarvis.assistant.util.OnlineChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
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
 *   reset of the state machine to IDLE. REMEDIATION_PLAN P3.2: every machine
 *   event applies through [applyMachineEvent] — the seq/state guard and the
 *   transition are atomic on one thread (no launch hop), so a superseded
 *   turn's late event can never interleave out of order.
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
    /** Phase 5 (M6): duck-gate for assistant TTS; null = no ducking. */
    private val focus: com.jarvis.assistant.audio.AssistantAudioFocus? = null,
    /**
     * Phase 5 (M7 mitigation): called at session start when
     * [JarvisConfig.pauseMusicOnWake] is on. Best-effort pause of external
     * audio — no auto-resume (the user says «продолжи»).
     */
    private val externalMusicPauser: (suspend () -> Unit)? = null,
    /** Follow-up window: feature toggle + window length (user-controllable). */
    // @Volatile (audit #29): written from the Settings binder thread, read by
    // the session coroutine inside maybeOpenFollowUpWindow.
    @Volatile private var followUpEnabled: Boolean = false,
    followUpWindowMs: Long = FollowUpWindowController.DEFAULT_WINDOW_MS,
    /** Runtime spoken phrases (i18n); defaults to the RU literals. */
    private val phrases: SpeechPhrases = SpeechPhrases.Default,
    /** G1: per-pass system prompt provider (time-aware in production). */
    private val systemPrompt: SystemPromptProvider = TimeAwareSystemPrompt(),
    /** Y6: TTS voice resolved per sentence (Settings-appliable live). */
    private val voiceSource: () -> String = { config.ttsVoice },
    /**
     * FIXPLAN B: live voice-stop toggle (read on every state change, so a
     * Settings switch applies from the next turn without a restart).
     */
    private val voiceStopEnabled: () -> Boolean = { config.voiceStopEnabled },
    /**
     * COGNITIVE_PLAN 1.2/1.6/1.7: the cognitive turn hooks (memory gather +
     * ingest). Null = pre-cognitive behaviour (tests/baseline); the graph
     * wires the real coordinator.
     */
    private val cognitive: CognitiveTurnHooks? = null,
) {

    private var sessionJob: Job? = null
    private var detectionJob: Job? = null
    private var windowJob: Job? = null
    private val sessionSeq = AtomicInteger(0)

    // ------------------------------------------------------------------
    // REMEDIATION_PLAN P3.2: race-free machine-event application
    //
    // The audit flagged that terminal machine events were `scope.launch`ed
    // from multiple threads (cancelAll / stopActiveTurn / closeFollowUpWindow),
    // so the ORDER in which they reached the (mutex-serialized) state machine
    // depended on dispatcher dispatch order — the sessionSeq GUARD was
    // checked on the caller's thread BEFORE the launch, so a concurrent
    // startSession could bump the seq in the check-vs-launch gap and a
    // stale global-reset Cancelled could still stomp the fresh session.
    //
    // Fix: guard and transition happen ATOMICALLY on the caller's thread —
    // [applyMachineEvent] validates the seq/state guard and applies the
    // event in the same synchronous call, with NO launch hop in between.
    // With no gap, a supersede can no longer slip between "guard passed"
    // and "event applied": a superseded turn's late event (e.g. a draining
    // turn's PlaybackStarted) carries the OLD seq and is dropped here.
    // Cross-thread concurrency is serialized by the state machine's own
    // mutex; the guards make every stale write a no-op, so the machine can
    // only ever record documented, current-session transitions.
    //
    // This deliberately replaces an earlier async FIFO-channel design: a
    // lane consumer made previously-synchronous transitions (startSession →
    // LISTENING) asynchronous and broke call-site and test contracts, while
    // synchronous guarded applies close the same race without that cost.
    // Non-suspending by construction — safe inside [controlLock] guarded
    // blocks (the monitor discipline of AGENTS.md is preserved).
    /**
     * Apply [event] to the machine with apply-time guards:
     * - [validSeq]: drop unless [sessionSeq] still equals it (stale-session
     *   suppression — e.g. a Cancelled cancelled by a later supersede);
     * - [requireState]: drop unless the machine is currently in this state
     *   (the closeFollowUpWindow CONDITIONAL expired event — the state check
     *   must ride with the transition atomically).
     */
    private fun applyMachineEvent(
        event: SessionEvent,
        validSeq: Int? = null,
        requireState: AssistantState? = null,
    ) {
        if (validSeq != null && validSeq != sessionSeq.get()) {
            Timber.w(
                "Dropping stale machine event %s (seq guard: %d != %d)",
                event.javaClass.simpleName,
                validSeq,
                sessionSeq.get(),
            )
            return
        }
        if (requireState != null && stateMachine.currentState() != requireState) {
            return
        }
        stateMachine.onEvent(event)
    }

    /**
     * COGNITIVE_PLAN 1.6: whether the CURRENT session was opened from the
     * follow-up window (follow-up turns answer "continue the previous
     * thought" — the composer may render summaries differently in Phase 2).
     * Written under [controlLock] in [startSession], read by the turn lane.
     */
    @Volatile
    private var currentTurnFromFollowUp: Boolean = false

    /**
     * Serializes every mutation of [sessionJob] / [detectionJob] /
     * [windowJob] (audit #5: binder-thread `startSession` vs `cancelAll`
     * could cancel a JUST-launched job or leak an uncancelled one through a
     * plain read-modify-write race). A plain monitor is enough: the guarded
     * blocks contain NO suspension points (monitors must never be held
     * across suspension). Reentrant, so locked helpers may nest.
     */
    private val controlLock = Any()

    /** Follow-up window decision core (pure, injectable clock). */
    private val followUp = FollowUpWindowController(
        windowMs = followUpWindowMs,
        nowMs = System::currentTimeMillis,
    )

    /** VAD for the follow-up window lane (reused across windows). */
    private val followUpVad = EnergyVad()

    /** UI: remaining fraction of the open follow-up window (0 when closed). */
    private val _followUpProgress = MutableStateFlow(0f)
    val followUpProgress: StateFlow<Float> = _followUpProgress.asStateFlow()

    /**
     * Frames at window open whose onset is IGNORED — the TTS tail may still
     * be audible (and the VAD floor cold); 200 ms keeps both from firing a
     * phantom follow-up turn.
     *
     * @Volatile (audit #29): written by the session coroutine that opens the
     * window, read by the window collector coroutine.
     */
    @Volatile private var followUpLeadIn = 0

    /** S1: live ASR partials for the UI (UI wiring happens in a later phase). */
    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    /**
     * G3: what the turn engine is doing while THINKING (null = generic label).
     * TurnRunner pushes; every terminal below (finish / reportFailure /
     * startSession / cancelAll) clears so a stale «Настраиваю громкость…»
     * never outlives its turn. StateFlow writes are atomic from any thread.
     */
    private val _turnActivity = MutableStateFlow<TurnActivity?>(null)
    val turnActivity: StateFlow<TurnActivity?> = _turnActivity.asStateFlow()

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
        // P3.2: turn-runner state events apply through the SAME guarded
        // path as every other event (a direct stateMachine::onEvent here
        // was the remaining race: a cancelled-but-still-draining turn
        // could land PlaybackStarted AFTER a newer session's reset).
        // validSeq is captured at EMISSION time: if a supersede bumped
        // the seq before this call applies the event, the event is
        // dropped instead of stomping the fresh session. Synchronous —
        // no launch hop, so guard and transition stay atomic.
        { event -> applyMachineEvent(event, validSeq = sessionSeq.get()) },
        this::reportFailure, this::finish,
        { _partialTranscript.value = it },
        isCurrentSession = { it == sessionSeq.get() },
        focus = focus,
        systemPrompt = systemPrompt,
        onActivity = { _turnActivity.value = it },
        voiceSource = voiceSource,
        // COGNITIVE_PLAN 1.6/1.7: per-turn memory gather + ingest hook.
        cognitive = cognitive,
        isFollowUpTurn = { currentTurnFromFollowUp },
    )

    // @Volatile: registered once at construction, read from session
    // coroutines on other dispatchers.
    @Volatile private var onErrorHandler: suspend (String) -> Unit = {}

    init {
        // FIXPLAN B: arm the stop-phrase lane exactly while the assistant
        // THINKS or SPEAKS. For a Sherpa primary this is a no-op (the stop
        // phrase rides in the same keywords file); for a Porcupine primary
        // it gates the dedicated lane's frame feed — zero idle CPU. The
        // [voiceStopEnabled] source is re-read per state change so the
        // Settings toggle applies without a restart.
        scope.launch {
            stateMachine.state.collect { state ->
                val active = state == AssistantState.THINKING || state == AssistantState.SPEAKING
                wakeWordDetector.setStopLaneEnabled(active && voiceStopEnabled())
            }
        }
    }

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
     *
     * REMEDIATION_PLAN P0.3: `message` must be a FIXED classification phrase
     * (a [SpeechPhrases] literal, or a fixed engine-failure reason string) —
     * never user content (ASR text, tool output, exception payloads). It is
     * both logged at WARN/ERROR here — and FileLoggingTree persists INFO+ to
     * disk in release — and SPOKEN via [onErrorHandler]. Every current call
     * site (TurnRunner's phrases.* funnel and this class's wake-word routing)
     * passes fixed phrases, verified by audit; this contract keeps future
     * call sites honest so raw content can never re-enter this funnel.
     */
    suspend fun reportFailure(id: Int?, message: String) {
        if (id != null && id != sessionSeq.get()) {
            Timber.w("Dropping stale session %d failure: %s", id, message)
            return
        }
        Timber.e("Session failure: %s", message)
        player.flush()
        _partialTranscript.value = ""
        _turnActivity.value = null
        // P3.2: routed through the lane — a failure's reset cannot interleave
        // out of order with a concurrent cancelAll/stopActiveTurn reset.
        applyMachineEvent(SessionEvent.ErrorOccurred)
        onErrorHandler(message)
    }

    // ------------------------------------------------------------------
    // Public control surface
    // ------------------------------------------------------------------

    /** Start the wake-word collector. Idempotent. */
    fun startListening() {
        // M1: an init failure is emitted into a SharedFlow nobody subscribes
        // to yet, so it would be dropped silently. Read the detector state
        // synchronously and route a dead engine into the error path instead
        // of running deaf.
        val detectorState = wakeWordDetector.state.value
        if (detectorState is DetectorState.Failed) {
            scope.launch {
                // No session identity: lifecycle-scoped failure (id = null).
                reportFailure(null, phrases.wakeWordEngineError(detectorState.reason))
            }
            return
        }
        // #5: the cancel + relaunch hand-off is atomic — a concurrent
        // cancelAll between them must not leave a stray collector running.
        synchronized(controlLock) {
            detectionJob?.cancel()
            detectionJob = scope.launch {
                wakeWordDetector.detections()
                    .gatedBy(BargeInPolicy.from(config), stateMachine.state)
                    .collect { detection ->
                        when (detection) {
                            is Detection.DetectorError -> {
                                reportFailure(null, phrases.wakeWordEngineError(detection.message))
                            }

                            is Detection.StopPhrase -> handleStopPhrase(detection)

                            Detection.WakeWord -> {
                                // The wake word supersedes any open follow-up window.
                                closeFollowUpWindow(silent = true)
                                startSession()
                            }
                        }
                    }
            }
        }
    }

    /**
     * Begin (or restart) a listening session — also the barge-in entry point.
     *
     * @param fromFollowUp COGNITIVE_PLAN 1.6: true when opened by the
     *   follow-up window's speech onset (tagged in [PromptContext]).
     */
    fun startSession(fromFollowUp: Boolean = false) {
        currentTurnFromFollowUp = fromFollowUp
        // M1 (hardened): SUPERSEDE FIRST. The old order (cancel → flush →
        // increment) left a micro-window where the interrupted session's
        // guarded writes (persistCompletedToolPass / finish / reportFailure)
        // could still read the OLD seq and legally land after the user had
        // barged in. Incrementing first invalidates the stale session's
        // guards immediately; anything it writes from this point is dropped
        // deterministically.
        //
        // #5: the whole supersede sequence (seq bump, job cancel, flush,
        // relaunch, sessionJob assignment) runs under [controlLock] so a
        // concurrent cancelAll/startSession can never tear the hand-off —
        // e.g. cancel a JUST-launched job, or null a job reference before
        // the launch is even assigned (leaking an uncancelled coroutine).
        val id: Int
        synchronized(controlLock) {
            id = sessionSeq.incrementAndGet()
            _partialTranscript.value = "" // fresh utterance, drop any stale partial
            _turnActivity.value = null // fresh turn, drop any stale activity
            windowJob?.cancel()
            windowJob = null
            sessionJob?.cancel()
            player.flush() // generation bump: current + queued sentences die
            focus?.onTtsFlushed() // M6: barge-in ends the duck immediately
            sessionJob = scope.launch { runSession(id) }
        }
    }

    /** Body of one session — launched under [controlLock] by [startSession]. */
    private suspend fun CoroutineScope.runSession(id: Int) {
        // Phase 5 (M7 mitigation): a clean listening window when the user
        // opted in — external audio pauses while we listen; NO auto-resume.
        if (config.pauseMusicOnWake) {
            externalMusicPauser?.let { pauser ->
                runCatching { pauser() }
                    .onFailure { Timber.w(it, "pauseMusicOnWake failed (ignored)") }
            }
        }
        if (!networkMonitor.isCurrentlyOnline()) {
            // P3.2: validSeq=id — the lane drops the LISTENING jump if this
            // session was superseded before the event applied.
            applyMachineEvent(SessionEvent.WakeWordOrBargeIn, validSeq = id)
            reportFailure(id, phrases.offline)
            return
        }
        with(turnRunner) {
            runTurn(id)
        }
    }

    fun cancelAll() {
        // Invalidate FIRST (M1 philosophy, audit #5): bumping the seq atomically
        // with the teardown drops every guarded write an in-flight turn might
        // still produce (finish / reportFailure / persistCompletedToolPass) —
        // including the CancellationException path's finish(), which previously
        // raced cancelAll and could open a follow-up window AFTER the user had
        // stopped the assistant.
        val seqAfterInvalidate: Int
        synchronized(controlLock) {
            seqAfterInvalidate = sessionSeq.incrementAndGet()
            sessionJob?.cancel()
            sessionJob = null
            detectionJob?.cancel()
            detectionJob = null
            closeFollowUpWindow(silent = true) // reentrant: controlLock is a monitor
            _partialTranscript.value = ""
            _turnActivity.value = null
        }
        // P3.2: guarded at APPLY time by the lane consumer (see above).
        // trySend is non-suspending; submitting in submission order means
        // events submitted by a later startSession's runSession cannot
        // overtake this Cancelled, and vice versa.
        applyMachineEvent(SessionEvent.Cancelled, validSeq = seqAfterInvalidate)
    }

    // ------------------------------------------------------------------
    // Voice stop (FIXPLAN B): «стоп» / "stop" without the wake word
    // ------------------------------------------------------------------

    /**
     * Route a stop-phrase detection. The gate passes stop detections in EVERY
     * state — state-conditional semantics live HERE: only an active turn
     * (THINKING/SPEAKING) is cancelled. A «стоп» inside a normal command
     * (LISTENING) or during the wake-word-free follow-up window must NOT nuke
     * anything — it is part of the user's utterance.
     */
    private fun handleStopPhrase(detection: Detection.StopPhrase) {
        // COGNITIVE_PLAN 0.2: re-check the LIVE pref before acting. The stop
        // lane's armed/disarmed state can lag a Settings toggle (the state
        // collector re-arms on STATE changes only, and a Sherpa rebuild races
        // the toggle) — an armed-but-just-disabled lane must not cancel a
        // turn the user no longer wants interruptible.
        if (!voiceStopEnabled()) {
            Timber.d("Stop phrase '%s' ignored — voice stop is disabled", detection.keyword)
            return
        }
        val state = stateMachine.currentState()
        if (state != AssistantState.THINKING && state != AssistantState.SPEAKING) {
            Timber.d("Stop phrase '%s' ignored in state=%s", detection.keyword, state)
            return
        }
        Timber.i("Voice stop ('%s') in state=%s — cancelling the turn", detection.keyword, state)
        stopActiveTurn()
    }

    /**
     * Cancel the ACTIVE TURN (session job + queued/current TTS) and return to
     * IDLE, while the wake-word collector STAYS alive — the defining
     * difference from [cancelAll]. The seq bump before the cancel drops every
     * guarded write of the interrupted turn (the same supersede-first
     * discipline as [startSession]), so a dying sentence can never open a
     * follow-up window after the user said stop.
     */
    fun stopActiveTurn() {
        val hadActive: Boolean
        synchronized(controlLock) {
            hadActive = sessionJob != null
            if (hadActive) {
                val bumped = sessionSeq.incrementAndGet()
                sessionJob?.cancel()
                sessionJob = null
                player.flush() // generation bump: current + queued sentences die
                focus?.onTtsFlushed()
                _partialTranscript.value = ""
                _turnActivity.value = null
                // P3.2: guarded at APPLY time by the lane consumer (the old
                // check on the caller's thread + launch left a gap where a
                // concurrent startSession could bump the seq first).
                applyMachineEvent(SessionEvent.Cancelled, validSeq = bumped)
            }
        }
    }

    /** Terminal transition, guarded against stale sessions. */
    private suspend fun finish(id: Int, spoke: Boolean) {
        if (id != sessionSeq.get()) return
        _partialTranscript.value = "" // session end clears any live partial
        _turnActivity.value = null // and the live activity label
        // P3.2: validSeq guard moves INTO the lane — the seq can no longer
        // bump between the check above and the event applying.
        applyMachineEvent(SessionEvent.LlmDone, validSeq = id)
        maybeOpenFollowUpWindow(spoke)
    }

    // ------------------------------------------------------------------
    // COGNITIVE_PLAN 2.4: proactive delivery (a guarded mini-session)
    // ------------------------------------------------------------------

    /**
     * Speak a proactive suggestion — a GUARDED mini-session, never a free
     * lane (plan §8.4):
     *
     * 1. re-check IDLE under [controlLock] (the arbiter checked earlier, but
     *    the state may have moved — the machine has the final word);
     * 2. `sessionSeq` bump (the same supersede-first discipline as
     *    [startSession]) so a concurrent barge-in cannot interleave;
     * 3. IDLE → SPEAKING via [SessionEvent.ProactiveSpeechStarted] — which
     *    also ARMS THE STOP LANE (the state collector arms it while
     *    SPEAKING), so «стоп» interrupts a proactive utterance exactly like
     *    a normal answer;
     * 4. persist the suggestion FIRST (role=assistant, `name=proactive`
     *    marker) so a follow-up turn's LLM context already contains it;
     * 5. synthesize + play with the exact `TtsSpeechFeedback` focus
     *    bracketing (ttsClient → player.play(flow), focus on started/finish);
     * 6. on drain: LlmDone → IDLE, then open the follow-up window (forced:
     *    the window IS the accept/reject mechanism of the feature the user
     *    opted into — the standalone follow-up pref is not consulted).
     *
     * @return false when the machine was not IDLE (caller logs the refusal).
     */
    fun speakProactively(text: String): Boolean {
        if (text.isBlank()) return false
        synchronized(controlLock) {
            if (stateMachine.currentState() != AssistantState.IDLE) return false
            val id = sessionSeq.incrementAndGet()
            _partialTranscript.value = ""
            _turnActivity.value = null
            windowJob?.cancel()
            windowJob = null
            sessionJob = scope.launch { runProactive(id, text) }
        }
        return true
    }

    /** Body of the proactive mini-session — launched under [controlLock]. */
    private suspend fun CoroutineScope.runProactive(id: Int, text: String) {
        // P3.2: routed through the lane with the session-id guard (the
        // machine itself enforces IDLE at apply time either way).
        applyMachineEvent(SessionEvent.ProactiveSpeechStarted, validSeq = id)
        // Persist BEFORE synthesis: a barge-in during playback must still
        // leave the suggestion in the LLM context (the follow-up "да" has to
        // know what was proposed).
        if (id == sessionSeq.get()) {
            try {
                conversationManager.addMessage(
                    Message(role = "assistant", content = text, name = "proactive"),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Proactive: suggestion persistence failed (speaking anyway)")
            }
        }
        try {
            val flow = ttsClient.synthesizeStream(text, voiceSource())
            focus?.onTtsSentenceStarted()
            val done = player.play(flow)
            try {
                done.await()
            } finally {
                focus?.onTtsSentenceFinished()
            }
        } catch (e: CancellationException) {
            // «стоп» / barge-in / shutdown killed the utterance mid-flight.
            focus?.onTtsFlushed()
            throw e
        } catch (e: IOException) {
            Timber.w(e, "Proactive: synthesis/playback IO failure")
            focus?.onTtsFlushed()
            if (id == sessionSeq.get()) {
                applyMachineEvent(SessionEvent.ErrorOccurred, validSeq = id) // → IDLE
            }
            return
        } catch (e: IllegalStateException) {
            Timber.w(e, "Proactive: player/tts in a bad state")
            focus?.onTtsFlushed()
            if (id == sessionSeq.get()) {
                applyMachineEvent(SessionEvent.ErrorOccurred, validSeq = id) // → IDLE
            }
            return
        }
        if (id != sessionSeq.get()) return // superseded while speaking
        // P3.2: validSeq=id — SPEAKING → IDLE through the lane.
        applyMachineEvent(SessionEvent.LlmDone, validSeq = id) // SPEAKING → IDLE
        // The follow-up window is the accept/reject loop's entry — opened
        // regardless of the standalone follow-up pref (see KDoc).
        maybeOpenFollowUpWindow(spoke = true, forceOpen = true)
    }

    // ------------------------------------------------------------------
    // Follow-up window (wake-word-free continuation)
    // ------------------------------------------------------------------

    /**
     * Live runtime control for the Settings «Продолжение диалога» card.
     * Disabling mid-window closes it immediately.
     */
    fun setFollowUpWindow(enabled: Boolean, windowMs: Long) {
        followUpEnabled = enabled
        followUp.setWindowMs(windowMs)
        if (!enabled) closeFollowUpWindow(silent = false)
    }

    private suspend fun maybeOpenFollowUpWindow(spoke: Boolean, forceOpen: Boolean = false) {
        if (!followUpEnabled && !forceOpen) return
        when (followUp.onTurnEnded(spoke, enabled = true)) {
            FollowUpWindowController.Effect.OpenWindow -> {
                Timber.i("Follow-up window open")
                applyMachineEvent(SessionEvent.FollowUpWindowOpened)
                startFollowUpCollector()
            }
            FollowUpWindowController.Effect.StartFollowUpTurn,
            FollowUpWindowController.Effect.ExpireWindow -> Unit // not emitted here
            null -> Unit
        }
    }

    /**
     * The window collector: consumes the mic lane (already AEC-cleaned when
     * SOFTWARE mode is on), feeds the VAD, drives the countdown progress and
     * fires the wake-word-free turn on speech onset. Expires on silence.
     * Exits by CancellationException on ALL terminals (trigger / expiry /
     * supersede) — one catch, no dangling subscriber.
     */
    private fun startFollowUpCollector() {
        // #5: windowJob hand-off under the same lock as every other job field.
        synchronized(controlLock) {
            windowJob?.cancel()
            followUpLeadIn = LEAD_IN_SLOTS
            followUpVad.reset()
            _followUpProgress.value = 1f
            windowJob = scope.launch {
                try {
                    audioPipeline.frames.collect { frame ->
                        if (followUpLeadIn > 0) {
                            followUpLeadIn--
                            // Still feeding the VAD so the noise floor adapts.
                            followUpVad.process(frame)
                            if (followUpLeadIn == 0) {
                                // The lead-in may have swallowed a genuine onset
                                // (speech already in progress when the window
                                // opened). Forget the edge, keep the floor:
                                // continuous speech re-fires within 2 frames.
                                followUpVad.forceSilent()
                            }
                        } else {
                            followUpVad.process(frame)
                            if (followUpVad.onset) {
                                Timber.i("Follow-up speech detected — starting turn")
                                applyMachineEvent(SessionEvent.FollowUpSpeechDetected)
                                followUp.onVadActive()
                                // COGNITIVE_PLAN 1.6: tag the turn origin.
                                startSession(fromFollowUp = true) // cancels this collector via windowJob
                                throw CancellationException("follow-up turn started")
                            }
                        }
                        _followUpProgress.value = followUp.remainingFraction()
                        if (followUp.transition() != null) {
                            Timber.i("Follow-up window expired")
                            applyMachineEvent(SessionEvent.FollowUpWindowExpired, requireState = AssistantState.FOLLOW_UP_WINDOW)
                            _followUpProgress.value = 0f
                            throw CancellationException("follow-up window expired")
                        }
                    }
                } catch (e: CancellationException) {
                    // Terminal of this window (trigger / expiry / superseded by
                    // wake word, cancelAll or shutdown). Nothing to clean here —
                    // the callers already drove the state machine.
                }
            }
        }
    }

    /**
     * Close any open window. [silent]=true keeps the state machine untouched
     * (the caller is about to drive it somewhere else, e.g. LISTENING).
     */
    private fun closeFollowUpWindow(silent: Boolean) {
        synchronized(controlLock) {
            followUp.onCancelled()
            windowJob?.cancel()
            windowJob = null
            _followUpProgress.value = 0f
        }
        if (!silent) {
            // #17 + P3.2: the state check rides WITH the transition on the
            // lane's serialized timeline (requireState is evaluated by the
            // consumer at apply time — no stale caller-thread read, no
            // launch-dispatch-order dependence).
            applyMachineEvent(
                SessionEvent.FollowUpWindowExpired,
                requireState = AssistantState.FOLLOW_UP_WINDOW,
            )
        }
    }

    private companion object {
        /** Ignored-onset frames at window open (TTS tail + VAD warm-up). */
        const val LEAD_IN_SLOTS = 10 // 200 ms
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
