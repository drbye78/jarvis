package com.jarvis.assistant.session

import com.jarvis.assistant.audio.AudioPipeline
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.llm.LlmClient
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
    private val onStateEvent: (SessionEvent) -> Unit,
    private val reportFailure: suspend (id: Int?, msg: String) -> Unit,
    private val finish: (id: Int) -> Unit,
    private val setPartial: (String) -> Unit,
    private val isCurrentSession: (id: Int) -> Boolean,
) {
    /** m10: bounds how many sentence jobs hold a TTS synthesis/playback slot. */
    private val ttsSynthPermits = Semaphore(TTS_SYNTH_PREFETCH)

    suspend fun CoroutineScope.runTurn(sessionId: Int) {
        try {
            onStateEvent(SessionEvent.WakeWordOrBargeIn) // -> LISTENING

            // 1) Open the streaming ASR session (with retries).
            val stream = openAsrWithRetry()
            if (stream == null) {
                onStateEvent(SessionEvent.AsrFailed())
                reportFailure(sessionId, "Не удалось открыть распознавание речи.")
                finish(sessionId)
                return
            }

            // 2) Feed live audio into the stream until the server reports EOU.
            val outcome = listenAndCollect(stream)
            stream.cancel() // done with the transport either way

            when (outcome) {
                is AsrOutcome.Final -> {
                    if (outcome.text.isBlank()) {
                        onStateEvent(SessionEvent.NoSpeech)
                        finish(sessionId)
                        return
                    }
                    onStateEvent(SessionEvent.SpeechCaptured) // -> THINKING
                    Timber.i("ASR final: %s", outcome.text)
                    conversationManager.addMessage("user", outcome.text)
                    processLlm(sessionId)
                }

                AsrOutcome.NoSpeech -> {
                    onStateEvent(SessionEvent.NoSpeech)
                    finish(sessionId)
                }

                is AsrOutcome.Failed -> {
                    onStateEvent(SessionEvent.AsrFailed(outcome.cause))
                    reportFailure(sessionId, "Ошибка распознавания речи.")
                    finish(sessionId)
                }
            }
        } catch (_: CancellationException) {
            // Barge-in / shutdown. Only act if still current.
            finish(sessionId)
        } catch (e: Exception) {
            Timber.e(e, "Session failed")
            reportFailure(sessionId, "Произошла ошибка. Попробуйте ещё раз.")
            finish(sessionId)
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
                delay(500L * attempts)
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
                delay(3000)
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
     */
    private suspend fun CoroutineScope.processLlm(id: Int) {
        onStateEvent(SessionEvent.LlmStarted)
        var pass = 0

        while (true) {
            pass++
            if (pass > config.maxToolPasses) {
                Timber.w("Tool loop exceeded %d passes, aborting turn", config.maxToolPasses)
                reportFailure(id, "Слишком много шагов, останавливаюсь.")
                break
            }

            val history = conversationManager.getHistoryForLLM()
            val tools = functionRouter.getToolDefinitions()
            val request = ChatRequest(
                messages = listOf(Message.system(SYSTEM_PROMPT)) + history,
                tools = tools,
                model = null, // profile default
                temperature = config.gigaChatTemperature,
                maxTokens = config.gigaChatMaxTokens,
            )

            val sentenceBuffer = SentenceBuffer()
            val assistantText = StringBuilder()
            val toolAccum = mutableMapOf<Int, ToolCallAccumulator>()
            val toolCallsPending = mutableListOf<ToolCall>()

            try {
                withTimeout(config.llmTimeoutMs) {
                    llm.chatStream(request).collect { chunk ->
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
                                    this@processLlm.launch { speakSentence(sentence) }
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
                                    this@processLlm.launch { speakSentence(rest) }
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
            } catch (e: TimeoutCancellationException) {
                Timber.w(e, "LLM stream timed out")
                reportFailure(id, "Превышено время ожидания ответа.")
                finish(id)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "LLM stream failed")
                reportFailure(id, "Не удалось получить ответ от нейросети.")
                finish(id)
                return
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
                        val toolResult = withContext(Dispatchers.IO) {
                            functionRouter.execute(call.function)
                        }
                        completed += call to Message(
                            role = "tool",
                            content = toolResult.result.ifBlank { "{}" },
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
            finish(id) // -> IDLE even when the drain budget expired
            return
        }

        finish(id)
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
    private suspend fun CoroutineScope.speakSentence(text: String) {
        onStateEvent(SessionEvent.PlaybackStarted) // -> SPEAKING
        ttsSynthPermits.withPermit {
            val flow = ttsClient.synthesizeStream(text, config.ttsVoice)
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

        private val SYSTEM_PROMPT = """
            Ты — Джарвис, голосовой ассистент на планшете Android.
            Отвечай кратко и разговорно, ВСЕГДА на русском языке.
            Если запрос пользователя соответствует одному из доступных
            инструментов (будильник, таймер, погода, управление устройством,
            яркость, громкость и т.д.) — вызывай инструмент вместо ответа
            из памяти. Не упоминай технические детали и JSON.
        """.trimIndent()
    }
}
