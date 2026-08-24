package com.jarvis.assistant

import android.content.Context
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.api.FunctionRouter
import com.jarvis.assistant.api.GigaChatClient
import com.jarvis.assistant.api.SaluteSpeechASR
import com.jarvis.assistant.api.SaluteSpeechTTS
import com.jarvis.assistant.api.TokenManager
import com.jarvis.assistant.audio.AudioPipeline
import com.jarvis.assistant.audio.AudioRecordSource
import com.jarvis.assistant.audio.PorcupineDetector
import com.jarvis.assistant.audio.StreamingAudioTrackPlayer
import com.jarvis.assistant.audio.VadAnalyzer
import com.jarvis.assistant.contracts.AsrClient
import com.jarvis.assistant.contracts.AudioSpec
import com.jarvis.assistant.contracts.LlmClient
import com.jarvis.assistant.contracts.SpeechDetector
import com.jarvis.assistant.contracts.TokenProvider
import com.jarvis.assistant.contracts.TtsPlayer
import com.jarvis.assistant.contracts.WakeWordDetector
import com.jarvis.assistant.data.AppDatabase
import com.jarvis.assistant.data.ConversationManager
import com.jarvis.assistant.session.SessionManager
import com.jarvis.assistant.session.SessionStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase = AppDatabase.getInstance(appContext)
    val conversationManager: ConversationManager = ConversationManager(database.messageDao())

    val tokenProvider: TokenProvider = TokenManager(appContext)
    val asrClient: AsrClient = SaluteSpeechASR(tokenProvider)
    val llmClient: LlmClient = GigaChatClient(tokenProvider)
    val ttsClient: SaluteSpeechTTS = SaluteSpeechTTS(tokenProvider)

    val audioPipeline: AudioPipeline = AudioPipeline(scope, AudioRecordSource())
    val wakeWordDetector: WakeWordDetector = PorcupineDetector(audioPipeline.frames, appContext, BuildConfig.PICOVOICE_KEY)
    val vad: SpeechDetector = VadAnalyzer(appContext)
    val player: TtsPlayer = StreamingAudioTrackPlayer(scope, AudioSpec.TTS)

    val functionRouter: FunctionRouter = FunctionRouter(appContext) { conversationManager.getHistoryForLLM() }

    val stateMachine: SessionStateMachine = SessionStateMachine()

    val sessionManager: SessionManager = SessionManager(
        audioPipeline = audioPipeline,
        wakeWordDetector = wakeWordDetector,
        vad = vad,
        asr = asrClient,
        llm = llmClient,
        ttsClient = ttsClient,
        player = player,
        functionRouter = functionRouter,
        conversationManager = conversationManager,
        stateMachine = stateMachine,
        scope = scope
    )

    fun start() {
        audioPipeline.start()
        sessionManager.startListening()
    }

    fun shutdown() {
        sessionManager.cancelAll()
        audioPipeline.release()
        wakeWordDetector.release()
        player.release()
        scope.cancel()
    }
}