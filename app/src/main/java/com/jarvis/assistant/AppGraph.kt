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
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.data.AppDatabase
import com.jarvis.assistant.data.ConversationManager
import com.jarvis.assistant.util.NetworkMonitor
import com.jarvis.assistant.session.SessionManager
import com.jarvis.assistant.session.SessionStateMachine
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppGraph(context: Context, private val config: JarvisConfig = JarvisConfig()) {
    private val appContext = context.applicationContext

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Shared HTTP client (connection pooling, thread reuse)
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Shared gRPC channel for Salute Speech (ASR + TTS share host:port)
    val saluteChannel: ManagedChannel = OkHttpChannelBuilder
        .forTarget("smartspeech.sber.ru:443")
        .useTransportSecurity()
        .build()

    val database: AppDatabase = AppDatabase.getInstance(appContext)
    val conversationManager: ConversationManager = ConversationManager(database.messageDao())

    val networkMonitor: NetworkMonitor = NetworkMonitor(appContext)

    val tokenProvider: TokenProvider = TokenManager(appContext, httpClient, config)
    val asrClient: AsrClient = SaluteSpeechASR(tokenProvider, saluteChannel, config)
    val llmClient: LlmClient = GigaChatClient(tokenProvider, httpClient, config)
    val ttsClient: SaluteSpeechTTS = SaluteSpeechTTS(tokenProvider, saluteChannel)

    val audioPipeline: AudioPipeline = AudioPipeline(scope, AudioRecordSource())
    val wakeWordDetector: WakeWordDetector = PorcupineDetector(
        audioPipeline.frames, appContext, BuildConfig.PICOVOICE_KEY,
        keywordPath = config.porcupineKeywordPath,
        sensitivity = config.porcupineSensitivity
    )
    val vad: SpeechDetector = VadAnalyzer(appContext, config)
    val player: TtsPlayer = StreamingAudioTrackPlayer(scope, AudioSpec.TTS)

    val functionRouter: FunctionRouter = FunctionRouter(appContext, httpClient) { conversationManager.getHistoryForLLM() }

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
        networkMonitor = networkMonitor,
        config = config,
        scope = scope
    )

    fun start() {
        try {
            audioPipeline.start()
            sessionManager.startListening()
        } catch (e: Exception) {
            scope.cancel()
            throw e
        }
    }

    fun wireErrorHandler(handler: suspend (String) -> Unit) {
        sessionManager.setOnError(handler)
    }

    fun shutdown() {
        sessionManager.cancelAll()
        audioPipeline.release()
        wakeWordDetector.release()
        player.release()
        scope.cancel()
        runCatching { saluteChannel.shutdown().awaitTermination(2, TimeUnit.SECONDS) }
    }
}