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
 *   turn's late event can never interleave out of order. P1-S #1 (locked
 *   decision 1): the seq a turn is validated AGAINST travels ON the event
 *   ([TurnEvent], captured by the emitting turn — see [applyTurnEvent]), and
 *   [reportFailure]/[finish] run their side effects only once the guard has
 *   actually accepted the event.
 * - Live ASR partials are published on [partialTranscript] (S1) and the turn
 *   pill on [turnActivity] — both ONLY through the id-stamped
 *   [publishPartial]/[publishActivity] guard, so a draining turn cannot rewrite
 *   the live session's transcript or label (P1-S #1, extended). Mic muting is
 *   a user intent exposed via [setMuted]/[muted] (m12).
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
     *
     * P1-S #2/#3: returns whether the event ACTUALLY reached the machine.
     * The guarded terminals (`reportFailure`, `finish`) run their side
     * effects (player flush, transcript/activity clears) only on `true`, so a
     * dropped stale event can no longer clobber the live session's state.
     */
    private fun applyMachineEvent(
        event: SessionEvent,
        validSeq: Int? = null,
        requireState: AssistantState? = null,
    ): Boolean {
        if (validSeq != null && validSeq != sessionSeq.get()) {
            Timber.w(
                "Dropping stale machine event %s (seq guard: %d != %d)",
                event.javaClass.simpleName,
                validSeq,
                sessionSeq.get(),
            )
            return false
        }
        if (requireState != null && stateMachine.currentState() != requireState) {
            return false
        }
        stateMachine.onEvent(event)
        return true
    }

    /**
     * P1-S #1 (locked decision 1): the TurnRunner → machine seam. A
     * [TurnEvent] carries the SEQ OF THE TURN THAT EMITTED it, so
     * [applyMachineEvent] compares the emitter's id against the CURRENT
     * session instead of comparing the current session with itself — the
     * tautology this replaces (`validSeq = sessionSeq.get()` read at apply
     * time always passed, so the guard never fired).
     *
     * Returns whether the event reached the machine. `internal` for the
     * white-box stale-seq regressions only (precedent:
     * [maybeOpenFollowUpWindow]) — production has exactly one caller, the
     * [turnRunner] wiring below.
     */
    internal fun applyTurnEvent(turnEvent: TurnEvent): Boolean =
        applyMachineEvent(turnEvent.event, validSeq = turnEvent.sessionSeq)

    /**
     * P1-S #1 (extended): the provenance guard applied to the two SHARED UI
     * STATE writes the turn engine owns — the live ASR partial transcript
     * ([partialTranscript]) and the activity pill ([turnActivity]). Before
     * this, both were handed to [TurnRunner] ungated, so a collector still
     * draining after a barge-in could overwrite the live session's transcript
     * (its final `""` included — the worst case: the fresh session's chip goes
     * blank mid-utterance) or relabel it «Думаю»/«Выполняю…» for a turn the
     * user already replaced.
     *
     * The check and the write are ONE unit inside [controlLock], exactly like
     * the [reportFailure]/[finish] terminals: every seq bump happens under that
     * monitor, so nothing can supersede between the two. Non-suspending by
     * construction (AGENTS.md monitor discipline) — these run on the mic/ASR
     * lane, so a dropped stale write is deliberately silent (a partial lands
     * many times a second; a log per race would be spam, and the state is
     * observable on the flows themselves).
     *
     * `internal` for the white-box provenance regressions only (precedent:
     * [applyTurnEvent], [maybeOpenFollowUpWindow]).
     */
    internal fun publishPartial(id: Int, text: String): Boolean = synchronized(controlLock) {
        if (id == sessionSeq.get()) {
            _partialTranscript.value = text
            true
        } else {
            false
        }
    }

    /** [publishPartial]'s twin for the activity pill; same guard, same reasons. */
    internal fun publishActivity(id: Int, activity: TurnActivity?): Boolean =
        synchronized(controlLock) {
            if (id == sessionSeq.get()) {
                _turnActivity.value = activity
                true
            } else {
                false
            }
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

    /** S1: live ASR partials for the UI (UI wiring happens in a later phase).
     * Written ONLY via [publishPartial] (turn-id guarded) and the guarded
     * terminals/`startSession` clears below. */
    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    /**
     * G3: what the turn engine is doing while THINKING (null = generic label).
     * TurnRunner pushes via [publishActivity] (turn-id guarded); every terminal
     * below (finish / reportFailure / startSession / cancelAll) clears so a
     * stale «Настраиваю громкость…» never outlives its turn. StateFlow writes
     * are atomic from any thread.
     */
    private val _turnActivity = MutableStateFlow<TurnActivity?>(null)
    val turnActivity: StateFlow<TurnActivity?> = _turnActivity.asStateFlow()

    /** m12: user mute intent; survives power-receiver restarts. */
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    /**
     * Per-turn execution engine (P7). Constructed once; [startSession] drives
     * it. The injected callbacks route state events, failures, terminal
     * transitions, partial updates and the activity pill back into this class
     * so the single source of truth stays here — and EVERY one of them is
     * turn-id guarded ([applyTurnEvent], [reportFailure], [finish],
     * [publishPartial], [publishActivity]).
     */
    private val turnRunner = TurnRunner(
        audioPipeline, asrClient, llm, ttsClient, player, functionRouter,
        conversationManager, config,
        // P3.2 + P1-S #1: turn-runner state events apply through the SAME
        // guarded path as every other event (a direct stateMachine::onEvent
        // here was the remaining race: a cancelled-but-still-draining turn
        // could land PlaybackStarted AFTER a newer session's reset).
        // The seq travels on the [TurnEvent] envelope, captured by the
        // EMITTING turn — reading sessionSeq here was tautological (it is by
        // definition the current seq), so the guard never fired and a
        // superseded turn's late event stomped the fresh session's state.
        // Synchronous — no launch hop, so guard and transition stay atomic.
        { turnEvent -> applyTurnEvent(turnEvent) },
        this::reportFailure, this::finish,
        // P1-S #1 (extended): the partial transcript and the activity pill go
        // through the SAME provenance guard as the machine events — the turn's
        // id is their first argument, so a superseded turn's late write is a
        // no-op instead of a stomp on the live session. (Lambdas, not method
        // references: the guard's Boolean answer is for tests, the caller
        // discards it.)
        { id, text -> publishPartial(id, text) },
        isCurrentSession = { it == sessionSeq.get() },
        focus = focus,
        systemPrompt = systemPrompt,
        onActivity = { id, activity -> publishActivity(id, activity) },
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
        // Cheap pre-check for the common stale case (a superseded turn's
        // failure). NOT the authority — see the guarded block below.
        if (id != null && id != sessionSeq.get()) {
            Timber.w("Dropping stale session %d failure: %s", id, message)
            return
        }
        // P1-S #2 (audit 2026-09-16): the guard must be AUTHORITATIVE and ride
        // ATOMICALLY with the effects. Previously the flush + clears ran
        // BEFORE the seq check, so a turn superseded in the check-vs-apply gap
        // silently flushed the NEW session's playback and wiped its live
        // partial transcript / activity label even when the ErrorOccurred
        // transition itself was dropped. Every seq bump (startSession,
        // cancelAll, stopActiveTurn, speakProactively) happens under
        // [controlLock], so the terminal is taken as ONE unit inside that
        // monitor: revalidate → apply → effects. Nothing can supersede between
        // the apply and the flush (same precedent: [startSession] flushes
        // inside the same monitor). Everything in the block is non-suspending
        // — monitor discipline (AGENTS.md) preserved.
        val applied = synchronized(controlLock) {
            if ((id == null || id == sessionSeq.get()) &&
                applyMachineEvent(SessionEvent.ErrorOccurred, validSeq = id)
            ) {
                player.flush()
                _partialTranscript.value = ""
                _turnActivity.value = null
                true
            } else {
                false
            }
        }
        if (!applied) return
        Timber.e("Session failure: %s", message)
        // Audit fix: re-check before the error voice — it is a SEPARATE engine
        // (system TTS) that player.flush() cannot touch and [onErrorHandler]
        // SUSPENDS, so it cannot ride inside the monitor. A failure whose
        // session was superseded after the terminal landed must not speak into
        // the new session.
        if (id != null && id != sessionSeq.get()) return
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
            // P3.2: validSeq=id — the guard drops the LISTENING jump if this
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
        // P1-S #1 (reality check on the old P3.2 comment): there is NO event
        // lane and no consumer thread — [applyMachineEvent] guards and applies
        // SYNCHRONOUSLY on this thread. The seq it carries is this
        // invalidation's own (post-bump), so the reset lands unless a LATER
        // supersede bumps past it first; if one does, that supersede owns the
        // machine and drives it itself (startSession's LISTENING / the next
        // cancelAll's Cancelled), so dropping here is the correct outcome.
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
        // P1-S #8: the matched keyword is USER SPEECH CONTENT → DEBUG-only
        // (FileLoggingTree persists INFO+ to disk; AGENTS.md logging rule).
        Timber.d("Voice stop ('%s') in state=%s — cancelling the turn", detection.keyword, state)
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
            // Audit fix: a COMPLETED job left in the field is NOT an active
            // turn — `sessionJob != null` was true after every clean turn,
            // so a stop then bumped the seq pointlessly and applied a global
            // Cancelled OVER an open follow-up window (whose collector kept
            // running, able to start a session the user just stopped).
            hadActive = sessionJob?.isActive == true
            if (hadActive) {
                val bumped = sessionSeq.incrementAndGet()
                sessionJob?.cancel()
                sessionJob = null
                player.flush() // generation bump: current + queued sentences die
                focus?.onTtsFlushed()
                _partialTranscript.value = ""
                _turnActivity.value = null
                // P3.2: guarded at APPLY time by the same synchronous path as
                // every other event (the old check on the caller's thread +
                // launch left a gap where a concurrent startSession could bump
                // the seq first).
                applyMachineEvent(SessionEvent.Cancelled, validSeq = bumped)
            } else if (windowJob != null) {
                // No active turn, but the follow-up window is open: a stop
                // still closes it (FollowUpWindowExpired → IDLE), the way
                // cancelAll tears the window down.
                closeFollowUpWindow(silent = false) // reentrant: controlLock is a monitor
            }
        }
    }

    /** Terminal transition, guarded against stale sessions. */
    private suspend fun finish(id: Int, spoke: Boolean) {
        // P1-S #3 (audit 2026-09-16): same shape as reportFailure. The seq
        // revalidation, the LlmDone apply and the shared-state clears are ONE
        // unit inside [controlLock] (every seq bump takes that monitor), so a
        // turn superseded in the old check-vs-apply gap can no longer wipe a
        // fresh session's partial transcript / activity label. Nothing in the
        // block suspends. maybeOpenFollowUpWindow SUSPENDS, so it stays
        // outside the monitor (AGENTS.md: never hold a monitor across
        // suspension) and re-validates the seq itself.
        val applied = synchronized(controlLock) {
            if (id == sessionSeq.get() && applyMachineEvent(SessionEvent.LlmDone, validSeq = id)) {
                _partialTranscript.value = "" // session end clears any live partial
                _turnActivity.value = null // and the live activity label
                true
            } else {
                false
            }
        }
        if (!applied) return
        maybeOpenFollowUpWindow(spoke, id = id)
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
            // Audit fix: a muted assistant has a STOPPED pipeline — a proactive
            // mini-session would speak into silence and force-open a follow-up
            // window whose VAD collector can never legitimately fire. Refuse.
            if (_muted.value) return false
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
        // P3.2: session-id guard at apply time (the machine itself enforces
        // IDLE for this event either way).
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
        } catch (e: Exception) {
            // Audit fix: TTS failures surface as e.g. gRPC
            // StatusRuntimeException from done.await() — an uncaught one
            // escaped the coroutine, wedging the machine in SPEAKING and
            // leaving sessionIdleFlow stuck false. Mirror speakSentence's
            // sibling pattern: funnel into the guarded ErrorOccurred.
            Timber.w(e, "Proactive: synthesis/playback failure")
            focus?.onTtsFlushed()
            if (id == sessionSeq.get()) {
                applyMachineEvent(SessionEvent.ErrorOccurred, validSeq = id) // → IDLE
            }
            return
        }
        if (id != sessionSeq.get()) return // superseded while speaking
        // P3.2: validSeq=id — SPEAKING → IDLE under the session-id guard.
        applyMachineEvent(SessionEvent.LlmDone, validSeq = id) // SPEAKING → IDLE
        // The follow-up window is the accept/reject loop's entry — opened
        // regardless of the standalone follow-up pref (see KDOC).
        maybeOpenFollowUpWindow(spoke = true, id = id, forceOpen = true)
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

    /**
     * Open the follow-up window after a turn ended.
     *
     * Audit fix: the WHOLE open is guarded by the CURRENT seq — a stale
     * turn's terminal interleaving with a fresh [startSession] previously
     * applied [SessionEvent.FollowUpWindowOpened] unconditionally and
     * launched a VAD collector that raced the live ASR (its onset fired
     * [startSession] and cancelled the user's utterance) or wedged the
     * machine in FOLLOW_UP_WINDOW.
     *
     * `internal` for the white-box seq-guard regression tests only.
     *
     * @param id the ending turn's session id; `null` = unguarded (never
     *   used in production — both callers own a real id).
     */
    internal suspend fun maybeOpenFollowUpWindow(
        spoke: Boolean,
        id: Int? = null,
        forceOpen: Boolean = false,
    ) {
        if (id != null && id != sessionSeq.get()) return
        if (!followUpEnabled && !forceOpen) return
        when (followUp.onTurnEnded(spoke, enabled = true)) {
            FollowUpWindowController.Effect.OpenWindow -> {
                Timber.i("Follow-up window open")
                // Apply-time seq guard: a supersede that lands between the
                // check above and this apply drops the event.
                applyMachineEvent(SessionEvent.FollowUpWindowOpened, validSeq = id)
                // Remediation F2: the open's seq rides down to the collector
                // so both the in-lock launch gate and the VAD-onset path can
                // re-validate it against a concurrent barge-in.
                startFollowUpCollector(validSeq = id)
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
    // The ThrowsCount suppression is deliberate (precedent: processLlm's
    // complexity suppressions): the collector's terminal exits ARE distinct
    // exceptions — superseded onset (F2 re-check), follow-up turn started,
    // and window expiry — each carrying its own cancellation identity, and
    // splitting them would scatter the terminal semantics.
    @Suppress("ThrowsCount")
    private fun startFollowUpCollector(validSeq: Int? = null) {
        // Audit fix: the collector may only run when the open actually
        // APPLIED — if the FollowUpWindowOpened event was dropped (stale seq)
        // or rejected by the machine (a fresh session is LISTENING), a VAD
        // collector here would race the live session's ASR lane. The state
        // check rides with the launch on the same thread as the open above.
        if (stateMachine.currentState() != AssistantState.FOLLOW_UP_WINDOW) {
            Timber.i("Follow-up window open dropped — machine not in FOLLOW_UP_WINDOW")
            return
        }
        // #5: windowJob hand-off under the same lock as every other job field.
        synchronized(controlLock) {
            // Remediation F2: the pre-lock state check is a cheap pre-filter —
            // the AUTHORITATIVE gate is this in-lock re-check, atomic with the
            // windowJob hand-off. A barge-in (startSession/cancelAll) that
            // bumped the seq between the state check and here previously left
            // a stale collector behind. Plain `get()` — no suspension in the
            // monitor-guarded block.
            if (validSeq != null && validSeq != sessionSeq.get()) {
                Timber.i(
                    "Follow-up window open dropped — superseded in flight (seq %d != %d)",
                    validSeq,
                    sessionSeq.get(),
                )
                return
            }
            if (stateMachine.currentState() != AssistantState.FOLLOW_UP_WINDOW) {
                // Covers seq-less closers (setFollowUpWindow(false), stop
                // with an open window) that raced this launch.
                Timber.i("Follow-up window open dropped — machine left FOLLOW_UP_WINDOW in flight")
                return
            }
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
                                // Remediation F2: re-validate AT ONSET — the
                                // collector may still be draining its last
                                // frames when a barge-in bumps the seq (the
                                // windowJob.cancel() cancellation is async).
                                // Firing startSession here would cancel the
                                // user's live ASR utterance. An expired/superseded
                                // machine state is equally disqualifying —
                                // applyMachineEvent would reject the transition
                                // but startSession would still fire.
                                if ((validSeq != null && validSeq != sessionSeq.get()) ||
                                    stateMachine.currentState() != AssistantState.FOLLOW_UP_WINDOW
                                ) {
                                    Timber.i("Follow-up onset dropped — window superseded or closed")
                                    throw CancellationException("follow-up window superseded")
                                }
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
            // #17 + P3.2: the state check rides WITH the transition in the
            // same synchronous call (requireState is evaluated at apply time —
            // no stale caller-thread read, no launch-dispatch-order
            // dependence).
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
