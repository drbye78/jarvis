package com.jarvis.assistant.di

import android.content.Context
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.audio.AudioPipeline
import com.jarvis.assistant.audio.AudioRecordSource
import com.jarvis.assistant.audio.PorcupineDetector
import com.jarvis.assistant.audio.StreamingAudioTrackPlayer
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.config.ProviderSettings
import com.jarvis.assistant.data.AppDatabase
import com.jarvis.assistant.data.ConversationManager
import com.jarvis.assistant.llm.GigaChatClient
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.llm.OpenAiCompatClient
import com.jarvis.assistant.llm.TokenManager
import com.jarvis.assistant.session.SessionManager
import com.jarvis.assistant.session.SessionStateMachine
import com.jarvis.assistant.speech.asr.SberStreamingAsr
import com.jarvis.assistant.speech.tts.SaluteSpeechTts
import com.jarvis.assistant.speech.tts.TtsClient
import com.jarvis.assistant.speech.tts.TtsPlayer
import com.jarvis.assistant.tools.FunctionRouter
import com.jarvis.assistant.util.NetworkMonitor
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual DI composition root. The LLM client is selected from
 * [ProviderSettings] — GigaChat (default) or any OpenAI-compatible endpoint.
 */
class AppGraph(
    context: Context,
    val config: JarvisConfig = JarvisConfig(),
    val provider: ProviderSettings,
    /**
     * Sealed at construction: the session error handler cannot be forgotten
     * by a call site because every AppGraph wires it into the SessionManager.
     */
    private val onSessionError: suspend (String) -> Unit = {},
) {
    private val appContext = context.applicationContext

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // long enough for SSE streams
        .build()

    val saluteChannel: ManagedChannel = OkHttpChannelBuilder
        .forTarget("smartspeech.sber.ru:443")
        .useTransportSecurity()
        .build()

    val database: AppDatabase = AppDatabase.getInstance(appContext)
    val conversationManager = ConversationManager(database.messageDao(), config.historyMaxMessages)

    val networkMonitor = NetworkMonitor(appContext)

    val tokenManager = TokenManager(appContext, httpClient, config)

    val llmClient: LlmClient = when (provider.type) {
        ProviderSettings.Type.GIGACHAT -> GigaChatClient(
            tokenManager = tokenManager,
            httpClient = httpClient,
            endpoint = config.gigaChatEndpoint,
            defaultModel = config.gigaChatModel,
        )

        ProviderSettings.Type.OPENAI_COMPAT -> OpenAiCompatClient(
            httpClient = httpClient,
            baseUrl = provider.openAiBaseUrl,
            apiKey = apiKeyFor(),
            defaultModel = provider.openAiModel,
        )
    }

    private fun apiKeyFor(): String =
        com.jarvis.assistant.util.SecurePrefs.get(appContext)
            .getString("openai_api_key", "") ?: ""

    val asrClient = SberStreamingAsr(
        tokenManager = tokenManager,
        channel = saluteChannel,
        // m11: the gRPC deadline must OUTLIVE the local maxUtteranceMs cap
        // (90s) plus its grace window, or deadline-exceeded races/masks the
        // local no-speech path and misclassifies the outcome.
        deadlineMs = config.asrStreamDeadlineMs + 5_000,
    )

    val ttsClient: TtsClient = SaluteSpeechTts(tokenManager, saluteChannel)

    val audioPipeline = AudioPipeline(scope, AudioRecordSource())
    val wakeWordDetector = PorcupineDetector(
        frames = audioPipeline.frames,
        context = appContext,
        accessKey = BuildConfig.PICOVOICE_KEY,
        keywordPath = config.porcupineKeywordPath,
        sensitivity = provider.wakeSensitivity,
    )
    val player: TtsPlayer = StreamingAudioTrackPlayer(scope)

    val functionRouter = FunctionRouter(appContext, httpClient)

    val stateMachine = SessionStateMachine()

    val sessionManager = SessionManager(
        audioPipeline = audioPipeline,
        wakeWordDetector = wakeWordDetector,
        asrClient = asrClient,
        llm = llmClient,
        ttsClient = ttsClient,
        player = player,
        functionRouter = functionRouter,
        conversationManager = conversationManager,
        stateMachine = stateMachine,
        networkMonitor = networkMonitor,
        config = config,
        scope = scope,
    ).also { it.setOnError(onSessionError) }

    /**
     * m12: user mute intent. Owned by the SessionManager (so the semantics —
     * stop pipeline + cancel active session + survive power-receiver restarts
     * — stay JVM-testable); exposed here as the observation point for UI.
     */
    val muteState: StateFlow<Boolean> get() = sessionManager.muted

    fun start() {
        try {
            audioPipeline.start()
            sessionManager.startListening()
        } catch (e: Exception) {
            scope.cancel()
            throw e
        }
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
