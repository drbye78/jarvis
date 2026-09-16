package com.jarvis.assistant.session

import com.jarvis.assistant.audio.AudioPipeline
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.data.ConversationManager
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.llm.LlmHttpException
import com.jarvis.assistant.llm.ToolCallAccumulator
import com.jarvis.assistant.model.AsrOutcome
import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.model.LlmChunk
import com.jarvis.assistant.model.Message
import com.jarvis.assistant.model.ToolCall
import com.jarvis.assistant.speech.asr.AsrEvent
import com.jarvis.assistant.speech.asr.AsrStream
import com.jarvis.assistant.speech.asr.StreamingAsrClient
import com.jarvis.assistant.speech.tts.TtsClient
import com.jarvis.assistant.speech.tts.TtsPlayer
import com.jarvis.assistant.tools.ToolExecutor
import com.jarvis.assistant.util.SentenceBuffer
import com.jarvis.assistant.util.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * P1-S #1 (audit 2026-09-16, locked decision 1): a machine event together with
 * the SESSION SEQ of the turn that EMITTED it.
 *
 * [TurnRunner] holds no session identity of its own, but it IS the only party
 * that knows which turn produced an event. [SessionManager] cannot recover
 * that later: reading `sessionSeq` at apply time is tautological — the value
 * it reads is by definition the CURRENT seq, so a superseded turn's late
 * event always passed the guard and stomped the fresh session's state
 * (e.g. a draining turn's `PlaybackStarted` yanking a new session out of
 * LISTENING). Carrying the emitter's id on the event is what makes the
 * apply-time drop in [SessionManager.applyMachineEvent] real.
 *
 * Deliberately NOT part of [SessionEvent]: the machine's transition table is
 * keyed on pure events and must stay provenance-free; the seq is a delivery
 * envelope, validated before the event ever reaches the machine.
 */
data class TurnEvent(val sessionSeq: Int, val event: SessionEvent)

/**
 * Per-turn execution engine extracted verbatim from [SessionManager] (P7).
 *
 * Holds NO session identity of its own — every terminal transition, failure
 * and shared-UI-state write is routed back to [SessionManager] through the
 * injected callbacks ([onStateEvent], [reportFailure], [finish], [setPartial],
 * [onActivity]) so the single source of truth (state machine, error funnel,
 * partial transcript, activity pill) stays in [SessionManager]. EVERY one of
 * them is stamped with the emitting turn's id (P1-S #1, locked decision 1):
 * each [onStateEvent] emission travels in a [TurnEvent], and the partial /
 * activity writers take the id as their first argument, so a turn the user
 * already superseded can never stomp the live session's state.
 *
 * [run] must be invoked from a [CoroutineScope] that is a CHILD of the session
 * job. It deliberately NEVER creates its own `CoroutineScope(...)` or wraps
 * work in `coroutineScope {}` — all concurrency reuses the caller's
 * coroutineContext so barge-in / shutdown cancellations propagate exactly as
 * they did inside [SessionManager].
 */
class TurnRunner(
    private val audioPipeline: AudioPipeline,
    private val asrClient: StreamingAsrClient,
    private val llm: LlmClient,
    private val ttsClient: TtsClient,
    private val player: TtsPlayer,
    private val functionRouter: ToolExecutor,
    private val conversationManager: ConversationManager,
    private val config: JarvisConfig,
    private val onStateEvent: suspend (TurnEvent) -> Unit,
    private val reportFailure: suspend (id: Int?, msg: String) -> Unit,
    private val finish: suspend (id: Int, spoke: Boolean) -> Unit,
    /**
     * P1-S #1 (extended): the live partial writer is ALSO provenance-guarded —
     * the id is the emitting turn's, so [SessionManager] can drop a stale
     * turn's partial (including its final `""`) instead of letting it stomp the
     * session the user actually owns now.
     */
    private val setPartial: (id: Int, text: String) -> Unit,
    private val isCurrentSession: (id: Int) -> Boolean,
    /** Runtime spoken phrases (i18n); defaults to the RU literals. */
    private val phrases: SpeechPhrases = SpeechPhrases.Default,
    /** Phase 5 (M6): duck external music while sentences play; null = off. */
    private val focus: com.jarvis.assistant.audio.AssistantAudioFocus? = null,
    /** G1: composed per-pass system prompt (identity + time + policies). */
    private val systemPrompt: SystemPromptProvider = TimeAwareSystemPrompt(),
    /** G3: what the turn engine is doing while THINKING (status pill).
     * P1-S #1 (extended): carries the emitting turn's id, so a stale turn can
     * neither set nor clear a live session's pill. */
    private val onActivity: (id: Int, activity: TurnActivity?) -> Unit = { _, _ -> },
    /** Y6: TTS voice resolved per sentence so Settings changes apply live. */
    private val voiceSource: () -> String = { config.ttsVoice },
    /**
     * COGNITIVE_PLAN 1.2/1.6/1.7: memory gather + ingest hooks; null =
     * pre-cognitive behaviour (byte-identical prompts, zero extra calls).
     */
    private val cognitive: CognitiveTurnHooks? = null,
    /** COGNITIVE_PLAN 1.6: true when this session came from the follow-up. */
    private val isFollowUpTurn: () -> Boolean = { false },
) {
    /** m10: bounds how many sentence jobs hold a TTS synthesis/playback slot. */
    private val ttsSynthPermits = Semaphore(TTS_SYNTH_PREFETCH)

    /**
     * Per-turn mutable state (audit A7). One instance per [runTurn] — the
     * previous class-level `spokeThisTurn` was shared across superseding
     * sessions, so a straggler sentence child of a barged-in turn could flip
     * the flag for the NEXT turn's follow-up-window eligibility. A fresh
     * object per turn makes the race structurally impossible.
     * The flag stays atomic: sentence coroutines run as session-scope
     * children while [finish] reads it from the turn body.
     */
    private class TurnState {
        val spoke = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Audit fix: explicit registry of TTS sentence jobs. The drain used
         * to join/cancel ALL session-scope children (eventCollector/feeder/
         * hardCap leftovers, cognitive deferreds, …) — now only these are
         * joined. Appended at LAUNCH time from the collect loop, so a job is
         * registered before any of its code runs; the queue is thread-safe
         * (sentences fan out concurrently from the LLM collector).
         */
        val sentenceJobs = java.util.concurrent.ConcurrentLinkedQueue<Job>()
    }

    /**
     * Execute one turn. [sessionId] is the session/turn seq this turn was
     * launched with (P1-S #1, locked decision 1): it is the turn's identity
     * for its whole life, stamped on EVERY event handed to [onStateEvent] and
     * passed to [reportFailure]/[finish], so [SessionManager] can drop a
     * straggler write from a turn the user already superseded. It is captured
     * ONCE here — never re-read from the manager at emission time, which is
     * what made the old guard a tautology.
     */
    // The NestedBlockDepth suppression follows processLlm's precedent: the
    // function IS the turn skeleton (open ASR → collect → dispatch by
    // outcome), and the audit fix added the transport-teardown try/finally
    // that tips the depth counter — the structure is deliberate.
    @Suppress("NestedBlockDepth")
    suspend fun CoroutineScope.runTurn(sessionId: Int) {
        val turn = TurnState()
        try {
            onStateEvent(TurnEvent(sessionId, SessionEvent.WakeWordOrBargeIn)) // -> LISTENING

            // 1) Open the streaming ASR session (with retries).
            val stream = openAsrWithRetry()
            if (stream == null) {
                onStateEvent(TurnEvent(sessionId, SessionEvent.AsrFailed()))
                // #18/#19: reportFailure IS the terminal for error turns
                // (ErrorOccurred -> IDLE + error voice). A trailing finish()
                // would emit LlmDone from IDLE — rejected by the machine
                // (log noise) and, with spoke=true, open a follow-up window
                // after an ERROR turn. Error turns end exactly once, here.
                reportFailure(sessionId, phrases.asrOpenFailed)
                return
            }

            // 2) Feed live audio into the stream until the server reports EOU.
            // Audit fix: the bidi transport must be torn down on EVERY exit —
            // the old call-site cancel covered the normal return only, so a
            // barge-in/cancelAll mid-utterance abandoned the RPC until its
            // deadline. finally covers abort paths too.
            val outcome = try {
                listenAndCollect(sessionId, stream)
            } finally {
                runCatching { stream.cancel() }
            }

            when (outcome) {
                is AsrOutcome.Final -> {
                    if (outcome.text.isBlank()) {
                        // NoSpeech itself drives LISTENING -> IDLE; that is the
                        // single terminal for this turn (a follow-up finish()
                        // would emit a rejected LlmDone from IDLE).
                        onStateEvent(TurnEvent(sessionId, SessionEvent.NoSpeech))
                        return
                    }
                    onStateEvent(TurnEvent(sessionId, SessionEvent.SpeechCaptured)) // -> THINKING
                    // P0.1 (REMEDIATION_PLAN): the utterance is user content —
                    // FileLoggingTree persists INFO+ to disk in release, so the
                    // raw text may appear only at DEBUG (AGENTS.md: no fact
                    // content outside DEBUG). INFO keeps a content-free summary.
                    Timber.i("ASR final (len=%d)", outcome.text.length)
                    Timber.d("ASR final: %s", outcome.text)
                    // COGNITIVE_PLAN 1.7: persist, then fire-and-forget ingest
                    // keyed by the row id (exactly-once per message).
                    // Audit fix: check-then-write UNDER NonCancellable — a seq
                    // bump between a bare check and the write let a stale user
                    // row land after the superseding turn's rows.
                    val messageId = persistUserMessage(sessionId, outcome.text)
                    if (messageId != null) {
                        cognitive?.ingest(outcome.text, messageId, TurnOrigin.VOICE)
                        // COGNITIVE_PLAN 2.4: the reject half of the accept/reject
                        // loop — a follow-up utterance right after a proactive
                        // suggestion may be an explicit «нет» (the coordinator
                        // decides; this is a fire-and-forget signal).
                        if (isFollowUpTurn()) {
                            cognitive?.onFollowUpUtterance(outcome.text)
                        }
                    }

                    // COGNITIVE_PLAN 1.6: one PromptContext per turn; the
                    // memory gather starts NOW so its (≤40 ms) cost hides
                    // inside the LLM call's time-to-first-token (§7.2).
                    val promptContext = buildPromptContext(outcome.text)
                    processLlm(sessionId, turn, promptContext)
                }

                AsrOutcome.NoSpeech -> {
                    onStateEvent(TurnEvent(sessionId, SessionEvent.NoSpeech))
                }

                is AsrOutcome.Failed -> {
                    onStateEvent(TurnEvent(sessionId, SessionEvent.AsrFailed(outcome.cause)))
                    // reportFailure is the terminal (see the ASR-open path).
                    reportFailure(sessionId, phrases.asrFailed)
                }
            }
        } catch (e: java.io.IOException) {
            Timber.e(e, "Network error in session")
            // Defensive: sentences launched before the failure are stopped so
            // they cannot enqueue after reportFailure's flush (usually the
            // list is empty here — the drain finished them on normal paths).
            cancelSpeechChildren(turn)
            reportFailure(sessionId, phrases.networkError)
        } catch (e: CancellationException) {
            // Barge-in / shutdown — finish first (follow-up eligibility needs
            // the spoke flag), then rethrow to preserve structured concurrency.
            // The seq guard makes this a no-op whenever the cancellation came
            // through startSession/cancelAll (both bump the seq first), so it
            // can never open a window the user does not expect.
            //
            // Audit fix: let the sentence children SETTLE before reading the
            // spoke flag — their cancellation handlers still run and set
            // spoke=true when the audio had already reached the player; join
            // returns immediately (the parent cancel already cancelled them).
            // Remediation F3: the sweep is BOUNDED — a cancellation-
            // unresponsive child (wedged transport) must not park the dying
            // coroutine forever; 2 s is generous for cancellation handlers.
            withContext(NonCancellable) {
                withTimeoutOrNull(CANCEL_JOIN_BUDGET_MS) {
                    turn.sentenceJobs.forEach { runCatching { it.join() } }
                }
            }
            finish(sessionId, turn.spoke.get())
            // Audit fix: rethrow the ORIGINAL exception — a fresh
            // CancellationException discarded the cause/mode of the real one.
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Session failed")
            // Defensive: same sentence-stop discipline as the IO path.
            cancelSpeechChildren(turn)
            reportFailure(sessionId, phrases.genericError)
        }
    }

    /**
     * Audit fix: stop every sentence this turn launched (cancel + bounded
     * join). Used on failure paths BEFORE [reportFailure] so no orphaned
     * sentence can enqueue a Play after the funnel's player.flush() — the
     * error voice is a separate engine the flush cannot touch.
     */
    private suspend fun cancelSpeechChildren(turn: TurnState) {
        val jobs = turn.sentenceJobs.toList()
        if (jobs.isEmpty()) return
        jobs.forEach { it.cancel() }
        // Remediation F4: a SHORT dedicated join budget — the jobs are
        // already cancelled, so this only waits out a wedged transport.
        // Reusing config.ttsDrainTimeoutMs (60 s) delayed the error voice by
        // up to a minute before the user ever heard it.
        withTimeoutOrNull(SPEECH_CANCEL_JOIN_BUDGET_MS) {
            jobs.forEach { it.join() }
        }
    }

    /**
     * Audit fix: the user-message write is a check-then-write UNDER
     * [NonCancellable] — a seq bump between a bare check and the write let a
     * stale user row land after the superseding turn's rows. Returns the row
     * id, or null when the session was superseded (ingest is skipped too).
     */
    private suspend fun persistUserMessage(sessionId: Int, text: String): Long? =
        withContext(NonCancellable) {
            if (!isCurrentSession(sessionId)) {
                null
            } else {
                conversationManager.addMessage("user", text)
            }
        }

    private suspend fun openAsrWithRetry(): AsrStream? {
        var attempts = 0
        while (true) {
            try {
                return asrClient.open()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempts >= config.asrMaxRetries) {
                    Timber.e(e, "ASR open failed after $attempts retries")
                    return null
                }
                attempts++
                Timber.w(e, "ASR open attempt $attempts failed, retrying")
                delay(ASR_RETRY_BACKOFF_BASE_MS * attempts)
            }
        }
    }

    /**
     * Pumps live mic audio into the ASR stream until the server reports
     * end-of-utterance (or the local hard cap fires).
     *
     * [id] is the turn's session seq (P1-S #1): every partial it publishes —
     * including the final's clearing `""` — is stamped with it, so a collector
     * still draining after a barge-in cannot overwrite the new session's live
     * transcript.
     */
    private suspend fun CoroutineScope.listenAndCollect(
        id: Int,
        stream: AsrStream,
    ): AsrOutcome {
        val result = CompletableDeferred<AsrOutcome>()

        // Collector for ASR events.
        val eventCollector = launch {
            stream.events.collect { event ->
                when (event) {
                    is AsrEvent.Partial -> setPartial(id, event.text)

                    is AsrEvent.Final -> {
                        setPartial(id, "") // final replaces the partial
                        result.complete(
                            if (event.text.isBlank()) {
                                AsrOutcome.NoSpeech
                            } else {
                                AsrOutcome.Final(event.text)
                            }
                        )
                    }

                    is AsrEvent.Failed -> result.complete(AsrOutcome.Failed(event.cause))
                }
            }
            // Events flow closed without a terminal event.
            result.complete(AsrOutcome.Failed(RuntimeException("ASR events closed")))
        }

        // Feeder: pre-roll from ring buffer, then live frames.
        // Audit fix (first-syllable gap): subscribe to the LIVE frames FIRST.
        // The old order (drain() then collect) had a suspension gap between
        // the two — frames emitted with no subscriber are lost (replay=0),
        // clipping up to ~60 ms of the utterance head. Live frames arriving
        // while the ring drains are buffered in-order and concatenated AFTER
        // it (ring content is strictly older than anything pumped post-
        // subscription, so the chronological order is preserved).
        val feeder = launch {
            val live = Channel<ShortArray>(Channel.UNLIMITED)
            val pump = launch {
                try {
                    audioPipeline.frames.collect { live.trySend(it) }
                } finally {
                    live.close()
                }
            }
            try {
                audioPipeline.ringBuffer.drain().forEach { stream.send(it.toByteArray()) }
                for (frame in live) stream.send(frame.toByteArray())
            } finally {
                pump.cancel()
            }
        }

        // Hard cap: no matter what, an utterance cannot exceed this.
        val hardCap = launch {
            delay(config.maxUtteranceMs)
            if (!result.isCompleted) {
                stream.finish()
                // Grace window for the server to flush its final transcript.
                delay(ASR_FINAL_GRACE_MS)
                if (!result.isCompleted) {
                    result.complete(AsrOutcome.NoSpeech)
                }
            }
        }

        val outcome = try {
            result.await()
        } finally {
            feeder.cancel()
            eventCollector.cancel()
            hardCap.cancel()
        }
        return outcome
    }

    // ------------------------------------------------------------------
    // LLM turn: iterative tool loop + streaming sentence TTS
    // ------------------------------------------------------------------

    /**
     * COGNITIVE_PLAN 1.6: per-turn prompt context. Built ONCE per turn; the
     * memory provider is a deferred STARTED here (the moment ASR finalizes —
     * plan §7.2) and awaited by the composer on first use, so its ≤40 ms
     * cost hides inside the LLM call's time-to-first-token. The deferred is
     * idempotent across the tool passes (one DB snapshot per turn).
     */
    private fun CoroutineScope.buildPromptContext(utterance: String): PromptContext {
        val hooks = cognitive
        val memory: suspend () -> String = if (hooks == null) {
            suspend { "" }
        } else {
            val gatherDeferred = async { hooks.gather(utterance) }
            suspend { gatherDeferred.await() }
        }
        // COGNITIVE_PLAN 2.5: the summary block is a cheap presence-gated DB
        // read (§7.1 "gated by presence of summaries; cheap") — no separate
        // prefetch lane needed; still resolved once per turn via async.
        val summary: suspend () -> String = if (hooks == null) {
            suspend { "" }
        } else {
            val summaryDeferred = async { hooks.gatherSummary(utterance, isFollowUpTurn()) }
            suspend { summaryDeferred.await() }
        }
        return PromptContext(
            utterance = utterance,
            isFollowUp = isFollowUpTurn(),
            memory = memory,
            summary = summary,
        )
    }

    /**
     * LLM turn: iterative tool loop + streaming sentence TTS.
     *
     * Interruption semantics (supersedes the v4-P1 "persist nothing" tradeoff):
     * tools still execute BEFORE persistence so history can never hold a
     * dangling assistant/tool_calls pair, but if the session is cancelled
     * mid-pass (barge-in, shutdown), the COMPLETED subset — the assistant row
     * paired ONLY with results of tools that actually finished — is persisted
     * atomically under [NonCancellable]. Tools that fired real side effects no
     * longer vanish from the conversation; tools that never finished are
     * simply absent (no phantom results, never a dangling pair).
     *
     * COGNITIVE_PLAN 1.6: every pass re-renders the prompt from the SAME
     * [context] — fresh clock per pass (via the composer), one memory
     * snapshot per turn.
     *
     * The complexity/nesting suppressions are deliberate: this method IS the
     * tool-loop state machine (retry ladder, interruption persistence,
     * streaming TTS fan-out). Splitting it would scatter the interruption
     * semantics that must stay atomic — the same tradeoff P7 made when it
     * was extracted verbatim from SessionManager.
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private suspend fun CoroutineScope.processLlm(id: Int, turn: TurnState, context: PromptContext) {
        onStateEvent(TurnEvent(id, SessionEvent.LlmStarted))
        var pass = 0

        while (true) {
            pass++
            if (pass > config.maxToolPasses) {
                Timber.w("Tool loop exceeded %d passes, aborting turn", config.maxToolPasses)
                // Audit fix: earlier passes may have launched sentences — stop
                // them before the funnel (same post-partial discipline).
                cancelSpeechChildren(turn)
                // reportFailure is the terminal — no trailing finish() (the
                // machine is already IDLE; LlmDone from IDLE is rejected and
                // would open a follow-up window after a failed turn).
                reportFailure(id, phrases.tooManyToolSteps)
                return
            }

            val history = conversationManager.getHistoryForLLM()
            val tools = functionRouter.getToolDefinitions()
            val request = ChatRequest(
                messages = listOf(Message.system(systemPrompt.build(context))) + history,
                tools = tools,
                model = null, // profile default
                temperature = config.gigaChatTemperature,
                maxTokens = config.gigaChatMaxTokens,
            )

            val sentenceBuffer = SentenceBuffer()
            val assistantText = StringBuilder()
            val toolAccum = mutableMapOf<Int, ToolCallAccumulator>()
            val toolCallsPending = mutableListOf<ToolCall>()

            // G3: THINKING begins — the pill leaves the generic label only
            // when a finer-grained tool label takes over below.
            onActivity(id, TurnActivity.Thinking)

            // G4: transient-failure retry. Safe ONLY while the stream emitted
            // nothing: a retried stream that had already produced chunks would
            // duplicate spoken sentences. Zero-output timeouts / IOExceptions /
            // 5xx-429 are transient; 4xx is fatal; partial output is never retried.
            val emittedAnything = java.util.concurrent.atomic.AtomicBoolean(false)
            var llmAttempts = 0
            var collected = false
            while (!collected) {
                try {
                    withTimeout(config.llmTimeoutMs) {
                        llm.chatStream(request).collect { chunk ->
                            emittedAnything.set(true)
                            when (chunk) {
                                is LlmChunk.Text -> {
                                    assistantText.append(chunk.text)
                                    // Launch on the SESSION scope, NOT the enclosing
                                    // withTimeout(llm) scope: withTimeout's block is
                                    // `suspend CoroutineScope.() -> T`, so an unqualified
                                    // launch here makes sentences CHILDREN OF THE TIMEOUT
                                    // job, which then cannot complete while audio plays
                                    // (structured-concurrency completion waits for
                                    // children) — the drain below would never run and
                                    // the LLM timeout would kill mid-playback audio.
                                    sentenceBuffer.append(chunk.text).forEach { sentence ->
                                        // Audit fix: register the sentence job at
                                        // LAUNCH time (before any of its code runs)
                                        // — the drain/cleanup joins exactly these.
                                        turn.sentenceJobs.add(
                                            this@processLlm.launch {
                                                speakSentence(id, sentence, turn)
                                            }
                                        )
                                    }
                                }

                                is LlmChunk.FunctionCallDelta -> {
                                    val a = toolAccum.getOrPut(chunk.index) {
                                        ToolCallAccumulator(chunk.index)
                                    }
                                    if (chunk.name != null) a.name = chunk.name
                                    a.args.append(chunk.argsDelta)
                                }

                                is LlmChunk.FunctionCallComplete -> {
                                    toolCallsPending.add(chunk.call)
                                }

                                LlmChunk.Done -> {
                                    sentenceBuffer.flushRemaining()?.let { rest ->
                                        turn.sentenceJobs.add(
                                            this@processLlm.launch { speakSentence(id, rest, turn) }
                                        )
                                    }
                                    // Fallback for providers without Complete events.
                                    if (toolCallsPending.isEmpty() && toolAccum.isNotEmpty()) {
                                        toolAccum.toSortedMap().forEach { (_, a) ->
                                            val name = a.name ?: return@forEach
                                            toolCallsPending.add(
                                                ToolCall(
                                                    id = a.id ?: java.util.UUID.randomUUID().toString(),
                                                    function = FunctionCall(name, a.args.toString()),
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    collected = true
                } catch (e: TimeoutCancellationException) {
                    if (shouldRetryLlm(e, emittedAnything.get(), llmAttempts)) {
                        llmAttempts++
                        Timber.w(e, "LLM attempt %d timed out with no output, retrying", llmAttempts)
                        delay(config.llmRetryBackoffMs * llmAttempts)
                        continue
                    }
                    Timber.w(e, "LLM stream timed out after $llmAttempts retries")
                    // Audit fix: partial output already launched sentence
                    // children — cancel + join them BEFORE the error funnel,
                    // so no sentence can enqueue a Play after reportFailure's
                    // flush and play over the error voice (the error voice is
                    // a separate engine the flush cannot touch).
                    cancelSpeechChildren(turn)
                    reportFailure(id, phrases.llmTimeout)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (shouldRetryLlm(e, emittedAnything.get(), llmAttempts)) {
                        llmAttempts++
                        Timber.w(e, "LLM attempt %d failed with no output, retrying", llmAttempts)
                        delay(config.llmRetryBackoffMs * llmAttempts)
                        continue
                    }
                    Timber.e(e, "LLM stream failed after $llmAttempts retries")
                    // Audit fix: same post-partial cleanup as the timeout path.
                    cancelSpeechChildren(turn)
                    reportFailure(id, phrases.llmFailed)
                    return
                }
            }

            // Tool calls? Execute ALL of them first, buffering results in
            // memory, then persist assistant+results atomically (C2). If the
            // session is cancelled mid-pass, the COMPLETED subset is still
            // persisted atomically (never a dangling pair) before propagating.
            // Tool failures are already converted to JSON error results by the
            // ToolRegistry, so buffering loses no legitimate result.
            if (toolCallsPending.isNotEmpty()) {
                val pending = toolCallsPending.toList()
                toolCallsPending.clear()

                val completed = mutableListOf<Pair<ToolCall, Message>>()
                try {
                    for (call in pending) {
                        // G3: the pill shows WHAT is running while the user waits.
                        onActivity(id, TurnActivity.ToolRunning(call.function.name))
                        val toolResult = withContext(Dispatchers.IO) {
                            functionRouter.executeResult(call.function)
                        }
                        completed += call to Message(
                            role = "tool",
                            content = toolResult.content.ifBlank { "{}" },
                            toolCallId = call.id,
                            name = call.function.name,
                        )
                    }
                } catch (e: CancellationException) {
                    // Interruption mid-pass: persist what DID finish, then rethrow.
                    persistCompletedToolPass(id, assistantText, pending, completed)
                    throw e
                }

                persistCompletedToolPass(id, assistantText, pending, completed)
                continue // next LLM pass, now with tool results in history
            }

            // Plain answer: persist once, wait for every TTS sentence to drain.
            if (assistantText.isNotEmpty()) {
                withContext(NonCancellable) {
                    // Audit fix: re-check INSIDE the NonCancellable block —
                    // same check-then-write discipline as
                    // [persistCompletedToolPass] below.
                    if (isCurrentSession(id)) {
                        conversationManager.addMessage(
                            Message(role = "assistant", content = assistantText.toString())
                        )
                    }
                }
            }

            // m9: ONE overall deadline for the whole drain. Each child used to
            // get its own ttsDrainTimeoutMs — worst case N×60s parked in
            // SPEAKING. Children progress concurrently, so joining them under
            // a single budget caps total park time at ttsDrainTimeoutMs, and
            // finish still transitions to IDLE when the budget expires.
            //
            // Audit fix: join ONLY the tracked sentence jobs — the old
            // `coroutineContext[Job]?.children` sweep also joined unrelated
            // session-scope children (eventCollector/feeder/hardCap leftovers,
            // cognitive deferreds, anything else a hook launched), stalling
            // the drain on work that has nothing to do with audio.
            val sentences = turn.sentenceJobs.toList()
            val drained = withTimeoutOrNull(config.ttsDrainTimeoutMs) {
                sentences.forEach { it.join() }
            }
            if (drained == null) {
                Timber.w("TTS drain budget expired; cancelling %d stragglers", sentences.count { it.isActive })
                sentences.forEach { it.cancel() }
            }
            finish(id, turn.spoke.get()) // -> IDLE (or follow-up window)
            return // the plain-answer turn ends here — no further LLM pass
        }
    }

    /**
     * Atomic persistence of ONE tool pass (C2 + interruption subset): the
     * assistant row carries tool_calls ONLY for the tools that produced
     * results, paired 1:1 with those results — never a dangling half-pair.
     * Runs under [NonCancellable] so a barge-in racing this write cannot tear
     * the pair apart. Persists nothing when no tool finished.
     */
    private suspend fun persistCompletedToolPass(
        id: Int,
        assistantText: StringBuilder,
        pending: List<ToolCall>,
        completed: List<Pair<ToolCall, Message>>,
    ) {
        // M1: a superseded (barge-in'd) turn must not poison history. If a newer
        // session is already running, drop this stale persist rather than let it
        // interleave after the new turn's writes. (Outer check = fast path.)
        if (!isCurrentSession(id)) return
        if (completed.isEmpty()) return
        val completedIds = completed.mapTo(HashSet()) { it.first.id }
        withContext(NonCancellable) {
            // Audit fix: RE-CHECK inside the NonCancellable block — a seq bump
            // between the outer check and the write previously let a stale
            // tool row land after the superseding turn's rows.
            if (!isCurrentSession(id)) return@withContext
            conversationManager.addAssistantWithToolResults(
                assistant = Message(
                    role = "assistant",
                    content = assistantText.toString(),
                    toolCalls = pending.filter { it.id in completedIds },
                ),
                results = completed.map { it.second },
            )
        }
    }

    /**
     * G4 retry predicate: only transient causes, only zero-output streams,
     * only within the configured attempt budget.
     */
    private fun shouldRetryLlm(e: Exception, emittedAnything: Boolean, attemptsMade: Int): Boolean {
        if (emittedAnything) return false // never re-emit partial output
        if (attemptsMade >= config.llmMaxRetries) return false
        return when (e) {
            is LlmHttpException -> e.isTransient
            is java.io.IOException -> true
            is TimeoutCancellationException -> true // hung upstream, zero tokens
            else -> false // 4xx, protocol errors, unknown — fail fast
        }
    }

    /**
     * Speak one sentence: enqueue TTS flow on the player and await drain.
     * Player flush (barge-in) cancels the Deferred, which surfaces here as
     * CancellationException of the await — we treat it as "sentence dropped".
     *
     * m10: the [ttsSynthPermits] permit spans the fetch + this sentence's
     * playback slot, so at most [TTS_SYNTH_PREFETCH] sentences hold a TTS
     * stream/queued PCM at once — a long answer no longer opens a gRPC
     * synthesis stream for EVERY completed sentence up front. Playback itself
     * stays serialized by the player actor; this only caps the prefetch.
     */
    private suspend fun CoroutineScope.speakSentence(sessionId: Int, text: String, turn: TurnState) {
        onStateEvent(TurnEvent(sessionId, SessionEvent.PlaybackStarted)) // -> SPEAKING
        ttsSynthPermits.withPermit {
            // Y6: resolve the voice per sentence — a Settings change applies
            // to the very next synthesis, no service restart.
            val flow = ttsClient.synthesizeStream(text, voiceSource())
            // Phase 5 (M6): the first sentence of a generation requests
            // duck focus; the last drained sentence abandons it. Barge-in
            // flush abandons via SessionManager's onTtsFlushed hook.
            focus?.onTtsSentenceStarted()
            var done: Deferred<Unit>? = null
            var enqueued = false // set the instant play() was called
            try {
                done = player.play(flow)
                enqueued = true
                val completed = withTimeoutOrNull(config.ttsSentenceTimeoutMs) { done.await() }
                if (completed == null) {
                    // Audit fix: the timeout must STOP the playback, not
                    // abandon it — cancel the deferred so the player halts
                    // the sentence at the budget instead of running past it.
                    done.cancel()
                    // Audit fix: a timed-out sentence is treated as never
                    // spoken — the budget may have fired before ANY audio
                    // (hung synthesis), and the playback is cut short anyway.
                    // (Previously the spoke flag was set at method ENTRY, so
                    // a turn whose every sentence failed still opened a
                    // follow-up window for audio the user never heard.)
                    Timber.w("TTS sentence timed out, cancelled (len=%d)", text.length)
                } else {
                    // The sentence reached (and fully drained to) playback.
                    turn.spoke.set(true)
                }
            } catch (e: CancellationException) {
                // Cancellation AFTER play() means the audio was already
                // playing (or queued to play) — the user heard this sentence,
                // so it keeps follow-up eligibility. A cancellation that lands
                // BEFORE play (permit wait, synthesis) leaves spoke untouched:
                // nothing was ever heard. The flush-drop path below stays the
                // DOCUMENTED CancellationException swallow (SessionManager's
                // player-flush drop contract).
                if (enqueued) turn.spoke.set(true)
                val d = done
                if (d != null && d.isCancelled) return@withPermit
                throw e
            } catch (e: Exception) {
                // N1: a real TTS failure (gRPC error, token expiry, AudioTrack
                // short write) must NOT escape and crash the scope. Drop the
                // sentence instead of letting it kill the process.
                // P0.2 (REMEDIATION_PLAN): the sentence may echo user facts —
                // length only at ERROR (persisted at INFO+ in release); the
                // content stays DEBUG-only.
                Timber.e(e, "TTS sentence failed, dropping (len=%d)", text.length)
                Timber.d(e, "TTS sentence failed, dropping: %s", text)
            } finally {
                focus?.onTtsSentenceFinished()
            }
        }
    }

    private companion object {
        /**
         * m10: concurrent TTS synthesis prefetch bound. Hardcoded instead of a
         * JarvisConfig knob because config/ is owned by another lane this
         * phase; promote to config later if tuning is ever needed.
         */
        private const val TTS_SYNTH_PREFETCH = 2

        /** PROJECT-AUDIT: named retry constants (was a bare delay(500 * n)). */
        private const val ASR_RETRY_BACKOFF_BASE_MS = 500L

        /** PROJECT-AUDIT: named grace window (was a bare delay(3000)). */
        private const val ASR_FINAL_GRACE_MS = 3_000L

        /**
         * Remediation F3: budget for the cancellation-path sentence-join
         * sweep ([runTurn]'s CancellationException catch). The children are
         * already cancelled by the parent; the bound only matters for a
         * wedged transport that ignores cancellation.
         */
        private const val CANCEL_JOIN_BUDGET_MS = 2_000L

        /**
         * Remediation F4: budget for [cancelSpeechChildren]'s join sweep on
         * the ERROR paths — deliberately short so the error voice is not
         * delayed behind wedged sentence transports (was the 60 s
         * [JarvisConfig.ttsDrainTimeoutMs], making the user wait a minute).
         */
        private const val SPEECH_CANCEL_JOIN_BUDGET_MS = 2_000L
    }
}
