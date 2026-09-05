package com.jarvis.assistant.session

import com.jarvis.assistant.audio.AudioPipeline
import com.jarvis.assistant.config.JarvisConfig
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
import com.jarvis.assistant.data.ConversationManager
import com.jarvis.assistant.util.SentenceBuffer
import com.jarvis.assistant.util.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlin.coroutines.coroutineContext
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
 * Per-turn execution engine extracted verbatim from [SessionManager] (P7).
 *
 * Holds NO session identity of its own — every terminal transition and
 * failure is routed back to [SessionManager] through the four injected
 * callbacks ([onStateEvent], [reportFailure], [finish], [setPartial]) so the
 * single source of truth (state machine, error funnel, partial transcript)
 * stays in [SessionManager].
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
    private val onStateEvent: suspend (SessionEvent) -> Unit,
    private val reportFailure: suspend (id: Int?, msg: String) -> Unit,
    private val finish: suspend (id: Int, spoke: Boolean) -> Unit,
    private val setPartial: (String) -> Unit,
    private val isCurrentSession: (id: Int) -> Boolean,
    /** Runtime spoken phrases (i18n); defaults to the RU literals. */
    private val phrases: SpeechPhrases = SpeechPhrases.Default,
    /** Phase 5 (M6): duck external music while sentences play; null = off. */
    private val focus: com.jarvis.assistant.audio.AssistantAudioFocus? = null,
    /** G1: composed per-pass system prompt (identity + time + policies). */
    private val systemPrompt: SystemPromptProvider = TimeAwareSystemPrompt(),
    /** G3: what the turn engine is doing while THINKING (status pill). */
    private val onActivity: (TurnActivity?) -> Unit = {},
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
    }

    suspend fun CoroutineScope.runTurn(sessionId: Int) {
        val turn = TurnState()
        try {
            onStateEvent(SessionEvent.WakeWordOrBargeIn) // -> LISTENING

            // 1) Open the streaming ASR session (with retries).
            val stream = openAsrWithRetry()
            if (stream == null) {
                onStateEvent(SessionEvent.AsrFailed())
                // #18/#19: reportFailure IS the terminal for error turns
                // (ErrorOccurred -> IDLE + error voice). A trailing finish()
                // would emit LlmDone from IDLE — rejected by the machine
                // (log noise) and, with spoke=true, open a follow-up window
                // after an ERROR turn. Error turns end exactly once, here.
                reportFailure(sessionId, phrases.asrOpenFailed)
                return
            }

            // 2) Feed live audio into the stream until the server reports EOU.
            val outcome = listenAndCollect(stream)
            stream.cancel() // done with the transport either way

            when (outcome) {
                is AsrOutcome.Final -> {
                    if (outcome.text.isBlank()) {
                        // NoSpeech itself drives LISTENING -> IDLE; that is the
                        // single terminal for this turn (a follow-up finish()
                        // would emit a rejected LlmDone from IDLE).
                        onStateEvent(SessionEvent.NoSpeech)
                        return
                    }
                    onStateEvent(SessionEvent.SpeechCaptured) // -> THINKING
                    Timber.i("ASR final: %s", outcome.text)
                    // COGNITIVE_PLAN 1.7: persist, then fire-and-forget ingest
                    // keyed by the row id (exactly-once per message).
                    val messageId = conversationManager.addMessage("user", outcome.text)
                    cognitive?.ingest(outcome.text, messageId, TurnOrigin.VOICE)
                    // COGNITIVE_PLAN 2.4: the reject half of the accept/reject
                    // loop — a follow-up utterance right after a proactive
                    // suggestion may be an explicit «нет» (the coordinator
                    // decides; this is a fire-and-forget signal).
                    if (isFollowUpTurn()) {
                        cognitive?.onFollowUpUtterance(outcome.text)
                    }

                    // COGNITIVE_PLAN 1.6: one PromptContext per turn; the
                    // memory gather starts NOW so its (≤40 ms) cost hides
                    // inside the LLM call's time-to-first-token (§7.2).
                    val promptContext = buildPromptContext(outcome.text)
                    processLlm(sessionId, turn, promptContext)
                }

                AsrOutcome.NoSpeech -> {
                    onStateEvent(SessionEvent.NoSpeech)
                }

                is AsrOutcome.Failed -> {
                    onStateEvent(SessionEvent.AsrFailed(outcome.cause))
                    // reportFailure is the terminal (see the ASR-open path).
                    reportFailure(sessionId, phrases.asrFailed)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Timber.w(e, "Session timed out")
            reportFailure(sessionId, phrases.turnTimeout)
        } catch (e: java.io.IOException) {
            Timber.e(e, "Network error in session")
            reportFailure(sessionId, phrases.networkError)
        } catch (_: CancellationException) {
            // Barge-in / shutdown — finish first (follow-up eligibility needs
            // the spoke flag), then rethrow to preserve structured concurrency.
            // The seq guard makes this a no-op whenever the cancellation came
            // through startSession/cancelAll (both bump the seq first), so it
            // can never open a window the user does not expect.
            finish(sessionId, turn.spoke.get())
            throw CancellationException()
        } catch (e: Exception) {
            Timber.e(e, "Session failed")
            reportFailure(sessionId, phrases.genericError)
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
     */
    private suspend fun CoroutineScope.listenAndCollect(stream: AsrStream): AsrOutcome {
        val result = CompletableDeferred<AsrOutcome>()

        // Collector for ASR events.
        val eventCollector = launch {
            stream.events.collect { event ->
                when (event) {
                    is AsrEvent.Partial -> setPartial(event.text)

                    is AsrEvent.Final -> {
                        setPartial("") // final replaces the partial
                        result.complete(
                            if (event.text.isBlank()) AsrOutcome.NoSpeech
                            else AsrOutcome.Final(event.text)
                        )
                    }

                    is AsrEvent.Failed -> result.complete(AsrOutcome.Failed(event.cause))
                }
            }
            // Events flow closed without a terminal event.
            result.complete(AsrOutcome.Failed(RuntimeException("ASR events closed")))
        }

        // Feeder: pre-roll from ring buffer, then live frames.
        val feeder = launch {
            audioPipeline.ringBuffer.drain().forEach { stream.send(it.toByteArray()) }
            audioPipeline.frames.collect { frame ->
                stream.send(frame.toByteArray())
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
        val now = java.util.Calendar.getInstance()
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
            hour = now.get(java.util.Calendar.HOUR_OF_DAY),
            dayOfWeek = now.get(java.util.Calendar.DAY_OF_WEEK),
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
        onStateEvent(SessionEvent.LlmStarted)
        var pass = 0

        while (true) {
            pass++
            if (pass > config.maxToolPasses) {
                Timber.w("Tool loop exceeded %d passes, aborting turn", config.maxToolPasses)
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
            onActivity(TurnActivity.Thinking)

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
                                        this@processLlm.launch { speakSentence(sentence, turn) }
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
                                        this@processLlm.launch { speakSentence(rest, turn) }
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
                        onActivity(TurnActivity.ToolRunning(call.function.name))
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
                conversationManager.addMessage(
                    Message(role = "assistant", content = assistantText.toString())
                )
            }

            // m9: ONE overall deadline for the whole drain. Each child used to
            // get its own ttsDrainTimeoutMs — worst case N×60s parked in
            // SPEAKING. Children progress concurrently, so joining them under
            // a single budget caps total park time at ttsDrainTimeoutMs, and
            // finish still transitions to IDLE when the budget expires.
            val children = coroutineContext[Job]?.children?.toList().orEmpty()
            val drained = withTimeoutOrNull(config.ttsDrainTimeoutMs) {
                children.forEach { it.join() }
            }
            if (drained == null) {
                Timber.w("TTS drain budget expired; cancelling %d stragglers", children.count { it.isActive })
                children.forEach { it.cancel() }
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
        // interleave after the new turn's writes.
        if (!isCurrentSession(id)) return
        if (completed.isEmpty()) return
        val completedIds = completed.mapTo(HashSet()) { it.first.id }
        withContext(NonCancellable) {
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
    private suspend fun CoroutineScope.speakSentence(text: String, turn: TurnState) {
        onStateEvent(SessionEvent.PlaybackStarted) // -> SPEAKING
        turn.spoke.set(true) // follow-up window eligibility
        ttsSynthPermits.withPermit {
            // Y6: resolve the voice per sentence — a Settings change applies
            // to the very next synthesis, no service restart.
            val flow = ttsClient.synthesizeStream(text, voiceSource())
            // Phase 5 (M6): the first sentence of a generation requests
            // duck focus; the last drained sentence abandons it. Barge-in
            // flush abandons via SessionManager's onTtsFlushed hook.
            focus?.onTtsSentenceStarted()
            val done = player.play(flow)
            try {
                withTimeoutOrNull(config.ttsSentenceTimeoutMs) { done.await() }
                    ?: run { Timber.w("TTS sentence timed out, continuing") }
            } catch (e: CancellationException) {
                // Deferred cancelled by player.flush(): dropped sentence, fine.
                if (done.isCancelled) return@withPermit
                throw e
            } catch (e: Exception) {
                // N1: a real TTS failure (gRPC error, token expiry, AudioTrack
                // short write) must NOT escape and crash the scope. Drop the
                // sentence instead of letting it kill the process.
                Timber.e(e, "TTS sentence failed, dropping: $text")
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
    }
}
