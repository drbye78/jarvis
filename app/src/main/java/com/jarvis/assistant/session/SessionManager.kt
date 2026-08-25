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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * - **Timeouts everywhere**: LLM total, TTS per sentence, ASR hard cap.
 * - A [sessionSeq] guard prevents a stale session's terminal transition from
 *   clobbering the new session's state.
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

    @Volatile private var lastDetectionTime = 0L
    private var onErrorHandler: suspend (String) -> Unit = {}

    fun setOnError(handler: suspend (String) -> Unit) {
        onErrorHandler = handler
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
                onErrorHandler("Ошибка движка wake word: ${detectorState.reason}")
                stateMachine.onEvent(SessionEvent.ErrorOccurred)
            }
            return
        }
        detectionJob = scope.launch {
            wakeWordDetector.detections().collect { detection ->
                when (detection) {
                    is Detection.DetectorError -> {
                        onErrorHandler("Ошибка движка wake word: ${detection.message}")
                        stateMachine.onEvent(SessionEvent.ErrorOccurred)
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
        sessionJob = scope.launch {
            if (!networkMonitor.isCurrentlyOnline()) {
                stateMachine.onEvent(SessionEvent.WakeWordOrBargeIn)
                stateMachine.onEvent(SessionEvent.ErrorOccurred)
                onErrorHandler("Нет подключения к интернету. Проверьте сеть.")
                return@launch
            }
            runSession(id)
        }
    }

    fun cancelAll() {
        sessionJob?.cancel()
        sessionJob = null
        detectionJob?.cancel()
        detectionJob = null
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
                onErrorHandler("Не удалось открыть распознавание речи.")
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
                    onErrorHandler("Ошибка распознавания речи.")
                    finish(id)
                }
            }
        } catch (_: CancellationException) {
            // Barge-in / shutdown. Only act if still current.
            finish(id)
        } catch (e: Exception) {
            Timber.e(e, "Session failed")
            stateMachine.onEvent(SessionEvent.ErrorOccurred)
            onErrorHandler("Произошла ошибка. Попробуйте ещё раз.")
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
                    is AsrEvent.Partial -> { /* UI could show live text here */ }

                    is AsrEvent.Final ->
                        result.complete(
                            if (event.text.isBlank()) AsrOutcome.NoSpeech
                            else AsrOutcome.Final(event.text)
                        )

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
        stateMachine.onEvent(SessionEvent.LlmDone)
    }

    // ------------------------------------------------------------------
    // LLM turn: iterative tool loop + streaming sentence TTS
    // ------------------------------------------------------------------

    /**
     * LLM turn: iterative tool loop + streaming sentence TTS.
     *
     * Accepted tradeoff (C2): tools execute BEFORE anything is persisted, so
     * barge-in/cancellation mid-loop persists NOTHING for the turn — even if
     * a tool already fired real side effects (alarm set, volume changed).
     * History stays correct at the cost of conversational amnesia for that
     * interrupted turn; the alternative (persist-then-execute) poisons
     * history with dangling assistant/tool pairs that 400 every later request.
     */
    private suspend fun CoroutineScope.processLlm(id: Int) {
        stateMachine.onEvent(SessionEvent.LlmStarted)
        var pass = 0

        while (true) {
            pass++
            if (pass > config.maxToolPasses) {
                Timber.w("Tool loop exceeded %d passes, aborting turn", config.maxToolPasses)
                onErrorHandler("Слишком много шагов, останавливаюсь.")
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
                                sentenceBuffer.append(chunk.text).forEach { sentence ->
                                    launch { speakSentence(sentence) }
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
                                    launch { speakSentence(rest) }
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
                stateMachine.onEvent(SessionEvent.ErrorOccurred)
                onErrorHandler("Превышено время ожидания ответа.")
                finish(id)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "LLM stream failed")
                stateMachine.onEvent(SessionEvent.ErrorOccurred)
                onErrorHandler("Не удалось получить ответ от нейросети.")
                finish(id)
                return
            }

            // Tool calls? Execute ALL of them first, buffering results in
            // memory, then persist assistant+results atomically (C2): an
            // interruption mid-loop persists nothing, so history can never
            // hold a dangling pair. Tool failures are already converted to
            // JSON error results by the ToolRegistry, so buffering loses no
            // legitimate result.
            if (toolCallsPending.isNotEmpty()) {
                val pending = toolCallsPending.toList()
                toolCallsPending.clear()

                val toolResults = mutableListOf<Message>()
                for (call in pending) {
                    val toolResult = withContext(Dispatchers.IO) {
                        functionRouter.execute(call.function)
                    }
                    toolResults += Message(
                        role = "tool",
                        content = toolResult.result.ifBlank { "{}" },
                        toolCallId = call.id,
                        name = call.function.name,
                    )
                }

                conversationManager.addAssistantWithToolResults(
                    assistant = Message(
                        role = "assistant",
                        content = assistantText.toString(),
                        toolCalls = pending,
                    ),
                    results = toolResults,
                )
                continue // next LLM pass, now with tool results in history
            }

            // Plain answer: persist once, wait for every TTS sentence to drain.
            if (assistantText.isNotEmpty()) {
                conversationManager.addMessage(
                    Message(role = "assistant", content = assistantText.toString())
                )
            }

            val children = coroutineContext[Job]?.children?.toList().orEmpty()
            for (child in children) {
                // Drain speakSentence children with an overall safety timeout.
                withTimeoutOrNull(config.ttsDrainTimeoutMs) { child.join() }
            }
            finish(id) // -> IDLE
            return
        }

        finish(id)
    }

    /**
     * Speak one sentence: enqueue TTS flow on the player and await drain.
     * Player flush (barge-in) cancels the Deferred, which surfaces here as
     * CancellationException of the await — we treat it as "sentence dropped".
     */
    private suspend fun CoroutineScope.speakSentence(text: String) {
        stateMachine.onEvent(SessionEvent.PlaybackStarted) // -> SPEAKING
        val flow = ttsClient.synthesizeStream(text, config.ttsVoice)
        val done = player.play(flow)
        try {
            withTimeoutOrNull(config.ttsSentenceTimeoutMs) { done.await() }
                ?: Timber.w("TTS sentence timed out, continuing")
        } catch (e: CancellationException) {
            // Deferred cancelled by player.flush(): dropped sentence, fine.
            if (done.isCancelled) return
            throw e
        }
    }

    private class ToolCallAccum(val index: Int) {
        var name: String? = null
        var id: String? = null
        val args = StringBuilder()
    }

    companion object {
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
