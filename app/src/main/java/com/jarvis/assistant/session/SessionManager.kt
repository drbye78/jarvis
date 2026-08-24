package com.jarvis.assistant.session

import com.jarvis.assistant.audio.AudioPipeline
import com.jarvis.assistant.audio.PorcupineDetector
import com.jarvis.assistant.audio.VadAnalyzer
import com.jarvis.assistant.api.FunctionRouter
import com.jarvis.assistant.api.SaluteSpeechTTS
import com.jarvis.assistant.contracts.AssistantState
import com.jarvis.assistant.contracts.AsrClient
import com.jarvis.assistant.contracts.FunctionCall
import com.jarvis.assistant.contracts.LlmChunk
import com.jarvis.assistant.contracts.LlmClient
import com.jarvis.assistant.contracts.TtsPlayer
import com.jarvis.assistant.data.ConversationManager
import com.jarvis.assistant.util.SentenceSplitter.endsWithSentence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * - **Barge-in:** a detection while SPEAKING calls [startSession], which flushes
 *   the player (cancelling the in-flight TTS flow) and starts a fresh turn. A
 *   [sessionSeq] guard prevents a cancelled session's terminal transition from
 *   clobbering the new session's state.
 */
class SessionManager(
    private val audioPipeline: AudioPipeline,
    private val porcupine: PorcupineDetector,
    private val vad: VadAnalyzer,
    private val asr: AsrClient,
    private val llm: LlmClient,
    private val ttsClient: SaluteSpeechTTS,
    private val player: TtsPlayer,
    private val functionRouter: FunctionRouter,
    private val conversationManager: ConversationManager,
    private val scope: CoroutineScope,
    private val onStateChange: (AssistantState) -> Unit,
    private val onError: (Exception) -> Unit,
    private val duck: () -> Unit = {},
    private val unduck: () -> Unit = {}
) {

    @Volatile
    private var _state: AssistantState = AssistantState.IDLE
    val currentState: AssistantState get() = _state

    private var sessionJob: Job? = null
    private var detectionJob: Job? = null
    private val sessionSeq = AtomicInteger(0)

    private data class ToolCallAccum(
        val index: Int,
        var name: String?,
        val args: StringBuilder
    )

    init {
        // One long-lived collector interprets Porcupine detections by state.
        // FIX #3: a single actor owns Porcupine; here we only react.
        detectionJob = scope.launch {
            porcupine.detections().collect {
                when (_state) {
                    // Barge-in OR a fresh wake word both start a new turn.
                    AssistantState.SPEAKING,
                    AssistantState.IDLE,
                    AssistantState.LISTENING -> startSession()
                    else -> { /* THINKING: ignore */ }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Public control surface
    // ------------------------------------------------------------------

    /** Begin (or restart) a listening session. */
    fun startSession() {
        sessionJob?.cancel()
        runCatching { player.flush() } // stops any in-flight TTS (barge-in)
        val id = sessionSeq.incrementAndGet()
        setState(AssistantState.LISTENING)
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

    private fun setState(state: AssistantState) {
        _state = state
        onStateChange(state)
    }

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
                finish(id)
                return
            }

            // b) Recognize.
            setState(AssistantState.THINKING)
            val text = asr.recognizeStreaming(pcm)
            if (text.isBlank()) {
                finish(id)
                return
            }

            // c) Persist user turn and run the LLM (iterative tool loop).
            conversationManager.addMessage("user", text)
            processLlm(text, id)
            // processLlm drives the terminal IDLE + unduck transition.
        } catch (_: CancellationException) {
            // Barge-in / shutdown: graceful stop. Only act if still current.
            finish(id)
        } catch (e: Exception) {
            if (id == sessionSeq.get()) onError(e)
            finish(id)
        }
    }

    /** Terminal transition, guarded so a stale session cannot clobber state. */
    private fun finish(id: Int) {
        if (id != sessionSeq.get()) return
        setState(AssistantState.IDLE)
        unduck()
    }

    /**
     * Iterative (NOT recursive) tool-call loop. Each LLM pass may emit text
     * (flushed to TTS per sentence) and/or function calls. When function calls
     * are present we execute them, feed the results back as a new user turn, and
     * loop. When no calls remain we wait for all TTS children to finish, then
     * go IDLE and unduck.
     */
    private suspend fun CoroutineScope.processLlm(userText: String, id: Int) {
        val pendingCalls = mutableListOf<FunctionCall>()
        var currentUserText = userText

        while (true) {
            val history = conversationManager.getHistoryForLLM()
            val tools = functionRouter.getAvailableTools()

            val sentenceBuffer = StringBuilder()
            val toolAccum = mutableMapOf<Int, ToolCallAccum>()

            llm.chatStream(currentUserText, history, tools).collect { chunk ->
                when (chunk) {
                    is LlmChunk.Text -> {
                        sentenceBuffer.append(chunk.text)
                        if (sentenceBuffer.toString().endsWithSentence()) {
                            val s = sentenceBuffer.toString()
                            sentenceBuffer.clear()
                            launch { speakChunk(s) } // child of sessionJob (FIX #1)
                        }
                    }

                    is LlmChunk.FunctionCallDelta -> {
                        val a = toolAccum.getOrPut(chunk.index) {
                            ToolCallAccum(chunk.index, null, StringBuilder())
                        }
                        if (chunk.name != null) a.name = chunk.name
                        a.args.append(chunk.argsDelta)
                    }

                    is LlmChunk.FunctionCallComplete -> {
                        pendingCalls.add(chunk.call)
                    }

                    is LlmChunk.Done -> {
                        if (sentenceBuffer.isNotEmpty()) {
                            val s = sentenceBuffer.toString()
                            sentenceBuffer.clear()
                            launch { speakChunk(s) }
                        }
                        // Fallback: clients that emit deltas but no Complete.
                        if (pendingCalls.isEmpty() && toolAccum.isNotEmpty()) {
                            toolAccum.toSortedMap().forEach { (_, a) ->
                                if (a.name != null) {
                                    pendingCalls.add(FunctionCall(a.name!!, a.args.toString()))
                                }
                            }
                        }
                    }
                }
            }

            if (pendingCalls.isNotEmpty()) {
                val results = mutableListOf<String>()
                for (call in pendingCalls) {
                    val result = withContext(Dispatchers.IO) { functionRouter.execute(call) }
                    conversationManager.addMessage("function", result.result)
                    results.add(result.result)
                }
                currentUserText =
                    "Результат: " + results.joinToString("; ") { it } + ". Сообщи пользователю."
                pendingCalls.clear()
                continue
            }

            // No more tool calls: wait for all TTS children to drain, then idle.
            coroutineContext[Job]?.children?.filter { it.isActive }?.forEach { it.join() }
            finish(id)
            return
        }
    }

    /**
     * FIX #1/#5: child of [sessionJob] (declared `suspend fun CoroutineScope`).
     * Awaits the player's [Deferred] so the sentence is only "done" once fully
     * played (FIX #5). Ducking is engaged on first speech.
     */
    private suspend fun CoroutineScope.speakChunk(text: String) {
        setState(AssistantState.SPEAKING)
        duck()
        conversationManager.addMessage("assistant", text)
        val ttsFlow = ttsClient.synthesizeStream(text, "Mila", "1.1")
        val done = player.play(ttsFlow)
        done.await() // wait for drain (FIX #5)
    }
}
