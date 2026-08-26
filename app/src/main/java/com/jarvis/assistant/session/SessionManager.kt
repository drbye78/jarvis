package com.jarvis.assistant.session

import com.jarvis.assistant.audio.AudioPipeline
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.contracts.Detection
import com.jarvis.assistant.contracts.DetectorState
import com.jarvis.assistant.contracts.WakeWordDetector
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.model.AsrOutcome
import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.model.LlmChunk
import com.jarvis.assistant.model.Message
import com.jarvis.assistant.model.ToolCall
import com.jarvis.assistant.model.ToolDefinition
import com.jarvis.assistant.speech.asr.AsrEvent
import com.jarvis.assistant.speech.asr.AsrStream
import com.jarvis.assistant.speech.asr.StreamingAsrClient
import com.jarvis.assistant.speech.tts.TtsClient
import com.jarvis.assistant.speech.tts.TtsPlayer
import com.jarvis.assistant.tools.ToolExecutor
import com.jarvis.assistant.data.ConversationManager
import com.jarvis.assistant.util.NetworkMonitor
import com.jarvis.assistant.util.OnlineChecker
import com.jarvis.assistant.util.SentenceBuffer
import com.jarvis.assistant.util.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates a full voice turn with TRUE STREAMING ASR:
 *
 *   WakeWord -> open ASR stream -> live mic audio -> server EOU ->
 *   LLM (iterative tool loop) -> per-sentence TTS -> drain -> IDLE
 *
 * Key guarantees:
 * - **Barge-in**: wake word in ANY state cancels the session, flushes the
 *   player's queue (generation bump) and cancels ASR/TTS/LLM transports.
 * - **Tool loop**: iterative with a bounded number of passes; assistant
 *   tool_calls and tool results are persisted with tool_call_id linkage and
 *   serialized through the wire layer (snake_case) on every subsequent pass.
 *   An interrupted pass persists its COMPLETED subset atomically — never a
 *   dangling pair.
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

    /** m10: bounds how many sentence jobs hold a TTS synthesis/playback slot. */
    private val ttsSynthPermits = Semaphore(TTS_SYNTH_PREFETCH)

    /** S1: live ASR partials for the UI (UI wiring happens in a later phase). */
    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    /** m12: user mute intent; survives power-receiver restarts. */
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    @Volatile private var lastDetectionTime = 0L
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
            wakeWordDetector.detections().collect { detection ->
                when (detection) {
                    is Detection.DetectorError -> {
                        reportFailure(null, "Ошибка движка wake word: ${detection.message}")
                    }

                    Detection.WakeWord -> {
                        val now = System.currentTimeMillis()
                        if (now - lastDetectionTime < config.wakeWordCooldownMs) return@collect
                        lastDetectionTime = now
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
            runSession(id)
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

    // ------------------------------------------------------------------
    // Session lifecycle
    // ------------------------------------------------------------------

    private suspend fun CoroutineScope.runSession(id: Int) {
        try {
            stateMachine.onEvent(SessionEvent.WakeWordOrBargeIn) // -> LISTENING

            // 1) Open the streaming ASR session (with retries).
            val stream = openAsrWithRetry()
            if (stream == null) {
                stateMachine.onEvent(SessionEvent.AsrFailed())
                reportFailure(id, "Не удалось открыть распознавание речи.")
                finish(id)
                return
            }

            // 2) Feed live audio into the stream until the server reports EOU.
            val outcome = listenAndCollect(stream)
            stream.cancel() // done with the transport either way

            when (outcome) {
                is AsrOutcome.Final -> {
                    if (outcome.text.isBlank()) {
                        stateMachine.onEvent(SessionEvent.NoSpeech)
                        finish(id)
                        return
                    }
                    stateMachine.onEvent(SessionEvent.SpeechCaptured) // -> THINKING
                    Timber.i("ASR final: %s", outcome.text)
                    conversationManager.addMessage("user", outcome.text)
                    processLlm(id)
                }

                AsrOutcome.NoSpeech -> {
                    stateMachine.onEvent(SessionEvent.NoSpeech)
                    finish(id)
                }

                is AsrOutcome.Failed -> {
                    stateMachine.onEvent(SessionEvent.AsrFailed(outcome.cause))
                    reportFailure(id, "Ошибка распознавания речи.")
                    finish(id)
                }
            }
        } catch (_: CancellationException) {
            // Barge-in / shutdown. Only act if still current.
            finish(id)
        } catch (e: Exception) {
            Timber.e(e, "Session failed")
            reportFailure(id, "Произошла ошибка. Попробуйте ещё раз.")
            finish(id)
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
                    is AsrEvent.Partial -> _partialTranscript.value = event.text

                    is AsrEvent.Final -> {
                        _partialTranscript.value = "" // final replaces the partial
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

    /** Terminal transition, guarded against stale sessions. */
    private fun finish(id: Int) {
        if (id != sessionSeq.get()) return
        _partialTranscript.value = "" // session end clears any live partial
        stateMachine.onEvent(SessionEvent.LlmDone)
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
        stateMachine.onEvent(SessionEvent.LlmStarted)
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
            val toolAccum = mutableMapOf<Int, ToolCallAccum>()
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
                                    ToolCallAccum(chunk.index)
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
                    persistCompletedToolPass(assistantText, pending, completed)
                    throw e
                }

                persistCompletedToolPass(assistantText, pending, completed)
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
        assistantText: StringBuilder,
        pending: List<ToolCall>,
        completed: List<Pair<ToolCall, Message>>,
    ) {
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
        stateMachine.onEvent(SessionEvent.PlaybackStarted) // -> SPEAKING
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
            }
        }
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

    private class ToolCallAccum(val index: Int) {
        var name: String? = null
        var id: String? = null
        val args = StringBuilder()
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
