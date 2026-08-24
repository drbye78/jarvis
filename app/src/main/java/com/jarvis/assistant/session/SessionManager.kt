package com.jarvis.assistant.session

import com.jarvis.assistant.audio.AudioPipeline
import com.jarvis.assistant.api.FunctionRouter
import com.jarvis.assistant.api.SaluteSpeechTTS
import com.jarvis.assistant.contracts.AsrResult
import com.jarvis.assistant.contracts.AsrClient
import com.jarvis.assistant.contracts.FunctionCall
import com.jarvis.assistant.contracts.LlmChunk
import com.jarvis.assistant.contracts.LlmClient
import com.jarvis.assistant.contracts.Message
import com.jarvis.assistant.contracts.SpeechDetector
import com.jarvis.assistant.contracts.ToolCall
import com.jarvis.assistant.contracts.TtsPlayer
import com.jarvis.assistant.contracts.WakeWordDetector
import com.jarvis.assistant.data.ConversationManager
import com.jarvis.assistant.util.SentenceSplitter.endsWithSentence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates a full voice turn: capture -> ASR -> LLM (with iterative tool
 * calls) -> TTS, plus wake-word / barge-in handling and media ducking.
 *
 * ## Defect fixes
 * - **#1 (scope leak):** every `launch` lives inside a `suspend fun
 *   CoroutineScope` (so it resolves to the [sessionJob] scope) or inside a
 *   `scope.launch { }` lambda. Children are therefore auto-cancelled when the
 *   session job is cancelled (barge-in / shutdown).
 * - **#3 (Porcupine race):** a SINGLE Porcupine actor is owned by
 *   [PorcupineDetector]; this manager only *interprets* its detections by the
 *   current [AssistantState] via one long-lived collector.
 * - **#5 (TTS not awaited):** [speakChunk] awaits the player's [Deferred] so a
 *   sentence is only considered "done" once it has drained to the speaker.
 * - **#6 (duplicate user message):** caller assembles the full message list
 *   (system prompt + history + user turn) before calling [LlmClient.chatStream].
 * - **#7 (tool-call protocol):** assistant tool_calls + tool results are
 *   persisted with proper `tool_call_id` linkage; assistant text is accumulated
 *   and persisted as ONE message at turn end.
 * - **#9 (ASR typed result):** distinguishes Success/NoSpeech/Failure.
 * - **Barge-in:** a detection while SPEAKING calls [startSession], which flushes
 *   the player (cancelling the in-flight TTS flow) and starts a fresh turn. A
 *   [sessionSeq] guard prevents a cancelled session's terminal transition from
 *   clobbering the new session's state.
 */
class SessionManager(
    private val audioPipeline: AudioPipeline,
    private val wakeWordDetector: WakeWordDetector,
    private val vad: SpeechDetector,
    private val asr: AsrClient,
    private val llm: LlmClient,
    private val ttsClient: SaluteSpeechTTS,
    private val player: TtsPlayer,
    private val functionRouter: FunctionRouter,
    private val conversationManager: ConversationManager,
    private val stateMachine: SessionStateMachine,
    private val scope: CoroutineScope
) {

    private var sessionJob: Job? = null
    private var detectionJob: Job? = null
    private val sessionSeq = AtomicInteger(0)

    private val cooldownMs = 600L
    @Volatile private var lastDetectionTime = 0L

    private var onErrorHandler: suspend (String) -> Unit = {}

    fun setOnError(handler: suspend (String) -> Unit) { onErrorHandler = handler }

    // ------------------------------------------------------------------
    // Public control surface
    // ------------------------------------------------------------------

    /**
     * Start (or restart) the wake-word detection collector. Idempotent — calling
     * again cancels the previous collector and starts a fresh one.
     * A 600 ms cooldown prevents the wake word's trailing audio from triggering
     * an immediate re-start, applying across all states (IDLE, LISTENING,
     * THINKING, SPEAKING).
     */
    fun startListening() {
        detectionJob?.cancel()
        detectionJob = scope.launch {
            wakeWordDetector.detections().collect {
                val now = System.currentTimeMillis()
                if (now - lastDetectionTime < cooldownMs) return@collect
                lastDetectionTime = now
                stateMachine.onEvent(SessionEvent.WakeWordOrBargeIn)
                startSession()
            }
        }
    }

    /** Begin (or restart) a listening session. */
    fun startSession() {
        sessionJob?.cancel()
        runCatching { player.flush() } // stops any in-flight TTS (barge-in)
        val id = sessionSeq.incrementAndGet()
        stateMachine.onEvent(SessionEvent.WakeWordOrBargeIn)
        sessionJob = scope.launch { runSession(id) }
    }

    /** Cancel everything (used by Service.onDestroy). */
    fun cancelAll() {
        sessionJob?.cancel()
        sessionJob = null
        detectionJob?.cancel()
        detectionJob = null
    }

    // ------------------------------------------------------------------
    // Session lifecycle
    // ------------------------------------------------------------------

    /**
     * FIX #1: declared as `suspend fun CoroutineScope` so the implicit receiver
     * is the launched coroutine's scope ([sessionJob]). Any `launch` inside
     * therefore becomes a child of [sessionJob] and is cancelled with it.
     */
    private suspend fun CoroutineScope.runSession(id: Int) {
        try {
            // a) Collect speech. Empty PCM => nothing said.
            val pcm = vad.collectSpeech(audioPipeline.frames, audioPipeline.ringBuffer)
            if (pcm.isEmpty()) {
                stateMachine.onEvent(SessionEvent.NoSpeech)
                finish(id)
                return
            }

            // b) Recognize.
            stateMachine.onEvent(SessionEvent.SpeechCaptured)
            var result: AsrResult
            var attempts = 0
            while (true) {
                val r = asr.recognizeStreaming(pcm)
                if (r is AsrResult.Failure && attempts < 2) {
                    attempts++
                    Timber.w(r.cause, "ASR attempt $attempts failed, retrying")
                    kotlinx.coroutines.delay(500L * attempts)
                    continue
                }
                result = r
                break
            }
            when (result) {
                is AsrResult.Success -> {
                    stateMachine.onEvent(SessionEvent.AsrSuccess)
                    if (result.text.isBlank()) {
                        stateMachine.onEvent(SessionEvent.NoSpeech)
                        finish(id)
                        return
                    }
                    conversationManager.addMessage("user", result.text)
                    processLlm(id)
                }
                is AsrResult.NoSpeech -> {
                    stateMachine.onEvent(SessionEvent.NoSpeech)
                    finish(id)
                    return
                }
                is AsrResult.Failure -> {
                    stateMachine.onEvent(SessionEvent.AsrFailed(result.cause))
                    finish(id)
                    return
                }
            }
            // processLlm drives the terminal IDLE transition.
        } catch (_: CancellationException) {
            // Barge-in / shutdown: graceful stop. Only act if still current.
            finish(id)
        } catch (e: Exception) {
            Timber.e(e, "Session failed")
            stateMachine.onEvent(SessionEvent.ErrorOccurred(e))
            onErrorHandler("Произошла ошибка. Попробуйте ещё раз.")
            finish(id)
        }
    }

    /** Terminal transition, guarded so a stale session cannot clobber state. */
    private fun finish(id: Int) {
        if (id != sessionSeq.get()) return
        stateMachine.onEvent(SessionEvent.SessionFinished)
    }

    /**
     * Iterative (NOT recursive) tool-call loop. On each pass:
     * 1. Assemble complete message list (system prompt + history).
     * 2. Stream LLM response, flushing sentences to TTS (per sentence).
     * 3. If tool calls are present: persist assistant message with tool_calls,
     *    execute each tool, persist tool result with tool_call_id, loop.
     * 4. If no tool calls: persist ONE accumulated assistant text message,
     *    wait for all TTS to drain, finish.
     */
    private suspend fun CoroutineScope.processLlm(id: Int) {
        stateMachine.onEvent(SessionEvent.LlmStarted)
        val toolCallsPending = mutableListOf<ToolCall>()

        while (true) {
            val history = conversationManager.getHistoryForLLM()
            val tools = functionRouter.getAvailableTools()

            val messages = buildList {
                add(Message(role = "system", content = SYSTEM_PROMPT))
                addAll(history)
            }

            val sentenceBuffer = StringBuilder()
            val assistantTextBuilder = StringBuilder()
            val toolAccum = mutableMapOf<Int, ToolCallAccum>()

            try {
                withTimeout(30_000L) {
                    llm.chatStream(messages, tools).collect { chunk ->
                        when (chunk) {
                            is LlmChunk.Text -> {
                                sentenceBuffer.append(chunk.text)
                                assistantTextBuilder.append(chunk.text)
                                if (sentenceBuffer.toString().endsWithSentence()) {
                                    val s = sentenceBuffer.toString()
                                    sentenceBuffer.clear()
                                    launch { speakChunk(s) } // child of sessionJob (FIX #1)
                                }
                            }

                            is LlmChunk.FunctionCallDelta -> {
                                val a = toolAccum.getOrPut(chunk.index) {
                                    ToolCallAccum(chunk.index, null, StringBuilder(), null)
                                }
                                if (chunk.name != null) a.name = chunk.name
                                a.args.append(chunk.argsDelta)
                            }

                            is LlmChunk.FunctionCallComplete -> {
                                toolCallsPending.add(chunk.call)
                            }

                            is LlmChunk.Done -> {
                                if (sentenceBuffer.isNotEmpty()) {
                                    val s = sentenceBuffer.toString()
                                    sentenceBuffer.clear()
                                    launch { speakChunk(s) }
                                }
                                // Fallback: clients that emit deltas but no Complete.
                                if (toolCallsPending.isEmpty() && toolAccum.isNotEmpty()) {
                                    toolAccum.toSortedMap().forEach { (_, a) ->
                                        if (a.name != null) {
                                            val tcId = a.id ?: java.util.UUID.randomUUID().toString()
                                            toolCallsPending.add(
                                                ToolCall(
                                                    id = tcId,
                                                    function = FunctionCall(a.name!!, a.args.toString())
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Timber.w(e, "LLM stream timed out")
                stateMachine.onEvent(SessionEvent.ErrorOccurred(e))
                onErrorHandler("Превышено время ожидания ответа.")
                finish(id)
                return
            }

            if (toolCallsPending.isNotEmpty()) {
                // Persist ONE assistant message with all tool calls (Issue 7).
                conversationManager.addMessage(
                    Message(
                        role = "assistant",
                        content = assistantTextBuilder.toString(),
                        toolCalls = toolCallsPending.toList()
                    )
                )

                // Execute each tool, persist result with tool_call_id.
                for (call in toolCallsPending) {
                    val result = withContext(Dispatchers.IO) {
                        functionRouter.execute(call.function)
                    }
                    conversationManager.addMessage(
                        Message(
                            role = "tool",
                            content = result.result,
                            toolCallId = call.id,
                            name = call.function.name
                        )
                    )
                }
                toolCallsPending.clear()
                continue
            }

            // No more tool calls: persist ONE accumulated assistant text message.
            if (assistantTextBuilder.isNotEmpty()) {
                conversationManager.addMessage(
                    Message(role = "assistant", content = assistantTextBuilder.toString())
                )
            }

            // Wait for all TTS children to drain, then idle.
            coroutineContext[Job]?.children?.filter { it.isActive }?.forEach { it.join() }
            stateMachine.onEvent(SessionEvent.LlmDone)
            finish(id)
            return
        }
    }

    /**
     * FIX #1/#5: child of [sessionJob] (declared `suspend fun CoroutineScope`).
     * Awaits the player's [Deferred] so the sentence is only "done" once fully
     * played (FIX #5).
     *
     * Note: persistence is handled by the caller (processLlm), not per-sentence.
     */
    private suspend fun CoroutineScope.speakChunk(text: String) {
        stateMachine.onEvent(SessionEvent.PlaybackStarted)
        val ttsFlow = ttsClient.synthesizeStream(text, "Mila")
        val done = player.play(ttsFlow)
        done.await() // wait for drain (FIX #5)
    }

    private data class ToolCallAccum(
        val index: Int,
        var name: String?,
        val args: StringBuilder,
        var id: String?
    )

    companion object {
        private val SYSTEM_PROMPT = """
            You are Jarvis, a concise voice assistant for Android.
            Answer briefly and conversationally. When a user request maps to a
            tool, call it instead of answering from memory. Prefer calling tools
            for alarms, device control, and weather.
        """.trimIndent()
    }
}