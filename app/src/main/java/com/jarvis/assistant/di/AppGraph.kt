package com.jarvis.assistant.di

import android.content.Context
import com.jarvis.assistant.audio.AudioPipeline
import androidx.room.withTransaction
import com.jarvis.assistant.audio.AudioRecordSource
import com.jarvis.assistant.audio.HybridWakeWordDetector
import com.jarvis.assistant.audio.StreamingAudioTrackPlayer
import com.jarvis.assistant.audio.aec.AecMode
import com.jarvis.assistant.audio.aec.AecProbe
import com.jarvis.assistant.audio.aec.LinearResampler
import com.jarvis.assistant.audio.aec.NlmsEchoCanceller
import com.jarvis.assistant.audio.aec.NoopEchoCanceller
import com.jarvis.assistant.audio.aec.PlaybackCaptureFarEndSource
import com.jarvis.assistant.contracts.WakeWordDetector
import com.jarvis.assistant.contracts.WakeWordRequest
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import timber.log.Timber
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

    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            // N1: defense-in-depth. Any uncaught coroutine exception (a TTS
            // sentence that slipped past local handling, or a state-collector
            // failure on an odd OEM ROM) must not crash the process on an
            // always-listening appliance. Log it; failure paths already report.
            Timber.e(e, "Uncaught coroutine exception in AppGraph scope")
        },
    )

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // long enough for SSE streams
        // Audit #15: total-call safety net. 120 s exceeds every legitimate
        // user of this client — the session layer caps LLM streams at
        // config.llmTimeoutMs = 45 s, TTS has a per-sentence deadline, the
        // credential probes use 5–15 s — so this only fires on a genuinely
        // stuck call whose socket-level timeouts were being kept alive by
        // trickling data. It must ALWAYS exceed llmTimeoutMs, or it would
        // truncate legitimately slow streams.
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    val saluteChannel: ManagedChannel = OkHttpChannelBuilder
        .forTarget(config.saluteGrpcEndpoint)
        .useTransportSecurity()
        .build()

    val database: AppDatabase = AppDatabase.getInstance(appContext)
    val conversationManager = ConversationManager(
        database.messageDao(),
        config.historyMaxMessages,
        config.historyMaxChars,
        // COGNITIVE_PLAN 1.9: keep the recent dialogue on disk (LLM window
        // stays historyMaxMessages) so the opt-in backfill has material.
        config.historyRetentionMessages,
        // COGNITIVE_PLAN 2.5: summarize-before-prune — the summarizer reads
        // the doomed range BEFORE the retention delete lands (its cloud call
        // is fire-and-forget on the cognitive scope). The lambda resolves
        // the coordinator lazily, so the conversation lane stays
        // pre-cognitive at graph construction.
        beforePrune = { cutoff -> cognitiveCoordinator.onBeforePrune(cutoff) },
    )

    val networkMonitor = NetworkMonitor(appContext)

    val appPrefs = com.jarvis.assistant.util.AppPrefs(appContext)

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
        // A3: the OpenAI-compatible key lives in the Keystore vault, same as
        // the Sber credentials (AppPrefs.openAiApiKey routes to the same slot).
        appPrefs.openAiApiKey

    val asrClient = SberStreamingAsr(
        tokenManager = tokenManager,
        channel = saluteChannel,
        // m11: the gRPC deadline must OUTLIVE the local maxUtteranceMs cap
        // (90s) plus its grace window, or deadline-exceeded races/masks the
        // local no-speech path and misclassifies the outcome.
        deadlineMs = config.asrStreamDeadlineMs + 5_000,
    )

    val ttsClient: TtsClient = SaluteSpeechTts(tokenManager, saluteChannel)

    // ------------------------------------------------------------------
    // AEC (Phase A + Phase B), all opt-in via Settings (default OFF).
    // ------------------------------------------------------------------

    /** User-selected AEC mode from prefs; drives the mic profile / DSP. */
    val aecMode: AecMode = AecMode.fromPref(appPrefs.aecMode)

    /** Static probe outcome for the Settings row (no active record needed). */
    val aecHwProbeAvailable: Boolean = AecProbe.staticAvailable()

    /** SOFTWARE mode: the built-in canceller owns all far-end state. */
    val echoCanceller: NlmsEchoCanceller? =
        if (aecMode == AecMode.SOFTWARE) NlmsEchoCanceller() else null

    /** 24 kHz TTS → 16 kHz far-end resampler for the own-TTS electrical tap. */
    private val ttsResampler = LinearResampler(24_000, 16_000)

    /**
     * Own-TTS far-end tap: every PCM chunk the player writes to the speaker
     * is resampled and pushed onto the canceller's far-end grid. Runs on the
     * player's actor thread; the FarEndMixer is synchronized.
     */
    private fun ttsFarEndTap(pcm: ByteArray) {
        val canceller = echoCanceller ?: return
        // 16-bit mono LE → shorts.
        val shorts = ShortArray(pcm.size / 2) { i ->
            (((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8))).toShort()
        }
        val resampled = ttsResampler.process(shorts)
        if (resampled.isNotEmpty()) {
            canceller.onFarEndFrame(NlmsEchoCanceller.LANE_TTS, resampled)
        }
    }

    val audioPipeline = AudioPipeline(
        scope,
        AudioRecordSource(profile = com.jarvis.assistant.audio.aec.MicProfile.forMode(aecMode)),
        echoCanceller = echoCanceller,
    )

    /**
     * Phase B optional lane: capture of other apps' music (API 29+) with a
     * consented MediaProjection — the wake-word-through-music reference.
     * Started by the service binder after the user grants consent in
     * Settings; only meaningful in SOFTWARE mode.
     */
    val playbackCapture: PlaybackCaptureFarEndSource = PlaybackCaptureFarEndSource(
        context = appContext,
        canceller = echoCanceller ?: NoopEchoCanceller,
        scope = scope,
    )

    val wakeKeywordPath = wakeKeywordPathFor(appPrefs.wakeWordModel)

    /**
     * HYBRID wake-word detector. The initial engine is selected from persisted
     * prefs (engine + model). Sherpa uses the bundled model (extracted from
     * assets on first run) unless the user supplied a custom directory.
     */
    val wakeWordDetector: WakeWordDetector = HybridWakeWordDetector(
        frames = audioPipeline.frames,
        context = appContext,
        initialReq = initialWakeRequest(),
        // FIXPLAN C: custom keywords / extracted & user models resolve here,
        // off the main thread, inside the detector's build path.
        sherpaEngineBuilder = { req -> buildSherpaEngine(req) },
    )
    val player: TtsPlayer = StreamingAudioTrackPlayer(
        scope,
        farEndTap = if (aecMode == AecMode.SOFTWARE) ::ttsFarEndTap else null,
    )

    // Phase 5 (M6): assistant TTS ducks external players; spoken progress
    // phrases («Секунду…») reuse the same serialized player.
    val audioFocus = com.jarvis.assistant.audio.AssistantAudioFocus(
        com.jarvis.assistant.audio.AndroidAudioFocusAdapter(appContext),
    )

    /**
     * Y6: the TTS voice resolved LIVE from prefs (Settings «Голос» card),
     * falling back to the config default when the pref is blank. Read per
     * sentence by the turn lane and per phrase by [speechFeedback], so a
     * Settings change applies with no service restart.
     */
    val voiceSource: () -> String = { appPrefs.ttsVoice.ifBlank { config.ttsVoice } }

    val speechFeedback = com.jarvis.assistant.audio.TtsSpeechFeedback(
        scope, ttsClient, player, voiceSource, audioFocus, appContext,
    )

    val functionRouter = FunctionRouter(
        appContext,
        httpClient,
        speechFeedback,
        // A4: tool errors are spoken — resolve them from the device locale.
        toolStrings = com.jarvis.assistant.tools.AndroidToolStrings(appContext),
        // A6: weather geocoding answers in the device language.
        weatherLanguageTag = java.util.Locale.getDefault().language.ifBlank { "ru" },
        // 0.7: ONE AppPrefs instance graph-wide (the router built its own).
        appPrefs = appPrefs,
        // COGNITIVE_PLAN 1.5: remember_fact / recall_facts / forget_fact.
        cognitiveTools = { cognitiveCoordinator.tools() },
        // COGNITIVE_PLAN 2.1: command telemetry — every tool execution writes
        // one command_events row (slot fingerprint only, no utterances).
        executionObserver = { call, result, latencyMs ->
            cognitiveCoordinator.observeCommandExecution(
                tool = call.name,
                argsJson = call.arguments,
                ok = !result.isError,
                latencyMs = latencyMs,
            )
        },
    )

    /**
     * COGNITIVE_PLAN 0.7/1.2: reactive settings. Every wake-word, voice-stop
     * and follow-up pref as a StateFlow; the CognitiveCoordinator's switches
     * are consumed from here, never re-snapshotted at graph build time.
     */
    val prefsFlow by lazy { com.jarvis.assistant.util.PrefsFlow(appPrefs) }

    /**
     * COGNITIVE_PLAN 2.3 gate 3 bridge: the arbiter needs the session state
     * machine's IDLE-ness without a coordinator→session dependency. The
     * graph owns both ends and keeps this flow in sync (collector started
     * below, right after [sessionManager] exists).
     */
    private val sessionIdleFlow = kotlinx.coroutines.flow.MutableStateFlow(true)

    /**
     * COGNITIVE_PLAN 1.2: the Cognitive Core. Lazy so graph construction
     * stays off the cognitive path (startup budget §9.4 ≤ 30 ms); the daos
     * trigger the v3→v4 migration on first touch, off the hot path.
     */
    val cognitiveCoordinator: com.jarvis.assistant.cognitive.CognitiveCoordinator by lazy {
        com.jarvis.assistant.cognitive.CognitiveCoordinator(
            factDao = database.userFactDao(),
            queueDao = database.extractionQueueDao(),
            metaDao = database.memoryMetaDao(),
            messageDao = database.messageDao(),
            llm = llmClient,
            memoryEnabled = prefsFlow.memoryEnabled,
            autoExtractEnabled = prefsFlow.memoryAutoExtract,
            cloudEnabled = prefsFlow.memoryCloudEnabled,
            sensitiveVisible = prefsFlow.memorySensitiveVisible,
            // ---- COGNITIVE_PLAN Phase 2 (§8): behaviour layer ----
            eventDao = database.commandEventDao(),
            ruleDao = database.habitRuleDao(),
            behaviorLogDao = database.behaviorLogDao(),
            summaryDao = database.sessionSummaryDao(),
            // §12.4-1: default OFF; the Settings card flips the pref and the
            // flow pushes it here live (no restart).
            behaviorEnabled = prefsFlow.behaviorEnabled,
            behaviorQuietStart = prefsFlow.behaviorQuietStart,
            behaviorQuietEnd = prefsFlow.behaviorQuietEnd,
            behaviorDailyQuota = prefsFlow.behaviorDailyQuota,
            deviceSignals = com.jarvis.assistant.cognitive.behavior.AndroidDeviceSignals(appContext),
            sessionIdle = sessionIdleFlow,
            lastInteractionAt = { database.messageDao().lastMessageAt() },
            speaker = com.jarvis.assistant.cognitive.behavior.ProactiveSpeaker { text ->
                sessionManager.speakProactively(text)
            },
            habitEligibleTools = config.habitEligibleTools,
            modelId = { provider.openAiModel },
            strings = com.jarvis.assistant.tools.AndroidToolStrings(appContext),
            parentScope = scope,
            inTransaction = { block -> database.withTransaction { block() } },
        )
    }

    val stateMachine = SessionStateMachine()

    /** Locale-aware spoken phrases (values / values-en, phrase_* keys). */
    private val speechPhrases = com.jarvis.assistant.session.AndroidSpeechPhrases(appContext)

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
        focus = audioFocus,
        phrases = speechPhrases,
        systemPrompt = com.jarvis.assistant.session.PromptComposer(),
        voiceSource = voiceSource,
        // Follow-up window: user-controllable, default OFF; the Settings
        // card updates it live through the service binder.
        followUpEnabled = appPrefs.followUpEnabled,
        followUpWindowMs = appPrefs.followUpWindowMs,
        // FIXPLAN B: live voice-stop toggle (Settings card, applies from the
        // next turn).
        voiceStopEnabled = { appPrefs.voiceStopEnabled },
        // COGNITIVE_PLAN 1.2/1.6/1.7: memory gather + ingest hooks. Lazily
        // resolved so the session lane never forces the cognitive migration
        // at graph construction.
        cognitive = object : com.jarvis.assistant.session.CognitiveTurnHooks {
            override suspend fun gather(utterance: String?): String =
                cognitiveCoordinator.gather(utterance)

            override fun ingest(utterance: String, messageId: Long, origin: com.jarvis.assistant.session.TurnOrigin) =
                cognitiveCoordinator.ingest(utterance, messageId, origin)
        },
        // Phase 5 (M7): pause-on-wake reuses the real tool lane — the same
        // capability-gated control path the LLM uses, incl. the media-key
        // fallback for the app that owns audio focus.
        externalMusicPauser = {
            functionRouter.executeResult(
                com.jarvis.assistant.model.FunctionCall(
                    "controlPlayback",
                    """{"action":"pause"}""",
                ),
            )
        },
    ).also { it.setOnError(onSessionError) }

    /**
     * COGNITIVE_PLAN 2.3 gate 3: keep the arbiter's IDLE view in sync with
     * the real state machine. Started once at graph construction; the
     * collector lives on the graph scope and dies with it.
     */
    init {
        scope.launch {
            stateMachine.state.collect { state ->
                sessionIdleFlow.value = state == com.jarvis.assistant.model.AssistantState.IDLE
            }
        }
    }

    /**
     * m12: user mute intent. Owned by the SessionManager (so the semantics —
     * stop pipeline + cancel active session + survive power-receiver restarts
     * — stay JVM-testable); exposed here as the observation point for UI.
     */
    val muteState: StateFlow<Boolean> get() = sessionManager.muted

    private fun wakeKeywordPathFor(model: String): String? = when (model) {
        "builtin" -> null
        "custom_user" -> appPrefs.customWakeWordPath.ifBlank { "jarvis_ru.ppn" }
        else -> "jarvis_ru.ppn" // custom_bundled (default)
    }

    /** FIXPLAN C: extracts the bundled model once for generated keyword files. */
    private val sherpaModelStore by lazy { com.jarvis.assistant.audio.SherpaModelStore(appContext) }

    /**
     * FIXPLAN C: build the Sherpa engine for a request, resolving custom
     * keywords and model directories. Runs OFF the main thread inside the
     * detector's build path; any failure throws → the detector surfaces
     * [com.jarvis.assistant.contracts.DetectorState.Failed] with the reason.
     *
     * Resolution order:
     * 1. No custom keyword, no user model dir → bundled assets (zero-config,
     *    `newFromAsset`) with wake + stop phrases.
     * 2. Custom keyword and/or user model dir → [SherpaModelStore] extraction
     *    (or the user dir), BPE-tokenize the keyword with THAT model's vocab,
     *    generate the keywords file, build via `newFromFile`.
     */
    private fun buildSherpaEngine(req: WakeWordRequest): com.jarvis.assistant.audio.SherpaKwsEngine {
        val customKeyword = req.sherpaCustomKeyword?.trim().orEmpty()
        val userModelDir = appPrefs.sherpaOnnxPath.trim()
        if (customKeyword.isEmpty() && userModelDir.isEmpty()) {
            val entries = buildList {
                add(com.jarvis.assistant.audio.SherpaKeywords.wake())
                if (req.stopPhraseEnabled) add(com.jarvis.assistant.audio.SherpaKeywords.stop())
            }
            return com.jarvis.assistant.audio.SherpaKwsEngine(
                context = appContext,
                sensitivity = req.sensitivity,
                entries = entries,
                modelSource = com.jarvis.assistant.audio.SherpaModelSource.Bundled,
            )
        }

        val usingUserModel = userModelDir.isNotEmpty()
        val modelDir = if (usingUserModel) {
            java.io.File(userModelDir)
        } else {
            sherpaModelStore.ensureExtracted()
        }
        val tokenizer = com.jarvis.assistant.audio.BpeTokenizer.fromModelFile(
            java.io.File(modelDir, "bpe.model"),
        ) ?: throw IllegalStateException(
            "bpe.model is missing or unreadable in ${modelDir.path} — cannot tokenize wake words",
        )
        val wakeText = customKeyword.ifBlank { "Jarvis" }
        val wakeLine = tokenizer.tokenizeKeywordPhrase(wakeText)
            ?: throw IllegalStateException(
                "Wake word '$wakeText' cannot be encoded with this model's BPE vocab " +
                    "(digits, punctuation and non-Latin scripts are not spotable) — " +
                    "pick an English word",
            )
        val entries = buildList {
            add(
                com.jarvis.assistant.audio.SherpaKeywords.Entry(
                    tokenLine = wakeLine,
                    id = wakeText.lowercase().take(24).ifBlank { "wake" },
                    isStop = false,
                ),
            )
            if (req.stopPhraseEnabled) add(com.jarvis.assistant.audio.SherpaKeywords.stop())
        }
        val provider = if (usingUserModel) "" else "xnnpack" // unknown models → default CPU
        return com.jarvis.assistant.audio.SherpaKwsEngine(
            context = appContext,
            sensitivity = req.sensitivity,
            entries = entries,
            modelSource = com.jarvis.assistant.audio.SherpaModelSource.Directory(
                dir = modelDir.absolutePath,
                provider = provider,
            ),
            generatedKeywordsContent =
                com.jarvis.assistant.audio.SherpaKeywords.toKeywordsFileContent(entries),
            workDir = if (usingUserModel) {
                // Never write into a user-supplied directory.
                java.io.File(appContext.filesDir, "sherpa_generated")
            } else {
                modelDir // our own extraction — writable by construction
            },
        )
    }

    /** Build the request the detector should currently run with. */
    private fun initialWakeRequest(): WakeWordRequest = buildWakeRequest()

    private fun buildWakeRequest(): WakeWordRequest = WakeWordRequest(
        engine = appPrefs.wakeWordEngine,
        keywordPath = wakeKeywordPathFor(appPrefs.wakeWordModel),
        sherpaModelDir = null, // resolution happens in [buildSherpaEngine]
        sherpaCustomKeyword = appPrefs.sherpaCustomKeyword,
        sensitivity = appPrefs.wakeSensitivity,
        stopPhraseEnabled = appPrefs.voiceStopEnabled,
    )

    /**
     * Rebuild the live wake-word engine from the current prefs. Safe to call
     * when the assistant is running (it suspends and swaps under the detector's
     * mutex); a no-op if the graph is torn down.
     *
     * Sensitivity is re-read from [appPrefs] here — the `provider` snapshot is
     * sealed at construction, so the old `provider.wakeSensitivity` read made
     * the Settings sensitivity slider a live no-op (the engine rebuilt with
     * the stale value; audit finding "slider no-op").
     */
    suspend fun reconfigureWakeWord() {
        wakeWordDetector.reconfigure(buildWakeRequest())
    }

    /**
     * Y6: «Проверить голос» from the Settings card — speaks one sample
     * sentence through the REAL synthesis + player lane, focus-bracketed
     * like a turn sentence, best-effort (a failed probe is logged, not
     * surfaced as a session error).
     *
     * @param voiceOverride the voice to probe; null = the currently saved pref.
     */
    fun speakVoiceSample(voiceOverride: String? = null) {
        val voice = voiceOverride ?: voiceSource()
        scope.launch {
            try {
                val text = appContext.getString(com.jarvis.assistant.R.string.phrase_voice_sample)
                val flow = ttsClient.synthesizeStream(text, voice)
                audioFocus.onTtsSentenceStarted()
                val done = player.play(flow)
                try {
                    kotlinx.coroutines.withTimeout(20_000) { done.await() }
                } finally {
                    audioFocus.onTtsSentenceFinished()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A8: cleanup, then RETHROW — swallowing cancellation here
                // broke structured concurrency (the probe coroutine would
                // keep running as if nothing happened after scope.cancel()).
                audioFocus.onTtsFlushed()
                throw e
            } catch (t: Throwable) {
                // A dead synthesis must never crash the app scope from a
                // Settings button; the toast-free failure lands in the log.
                Timber.w(t, "Voice sample probe failed (best-effort)")
                audioFocus.onTtsFlushed()
            }
        }
    }

    fun start() {
        try {
            audioPipeline.start()
            sessionManager.startListening()
            // COGNITIVE_PLAN 1.2: queue loop starts with the service; the
            // first lazy touch of the coordinator runs the v3→v4 migration.
            cognitiveCoordinator.startQueueLoop()
            // COGNITIVE_PLAN 2.3: the behaviour ticker (no-op while the
            // §12.4-1 switch is OFF).
            cognitiveCoordinator.startBehaviorLoop()
        } catch (e: Exception) {
            shutdown() // N11: tear down anything we built before the throw
            throw e
        }
    }

    fun shutdown() {
        // N11: every teardown is best-effort so shutdown() is safe to call even
        // if construction/start partially failed (no resource left dangling for
        // the watchdog's next retry).
        runCatching { prefsFlow.close() } // 0.7: release the change listener
        runCatching { sessionManager.cancelAll() }
        runCatching { cognitiveCoordinator.scope.cancel() } // 1.2: stop cognition first
        runCatching { playbackCapture.stop() }
        runCatching { audioPipeline.release() }
        runCatching { wakeWordDetector.release() }
        runCatching { player.release() }
        runCatching { scope.cancel() }
        runCatching { saluteChannel.shutdown().awaitTermination(2, TimeUnit.SECONDS) }
        // C3: the gRPC channel was torn down but the OkHttp client's pooled
        // connections and dispatcher threads were not — every graph rebuild
        // (provider change, watchdog restart) previously left them lingering
        // for their 60-s/5-s idle timeouts, holding sockets to LLM endpoints.
        runCatching { httpClient.connectionPool.evictAll() }
        runCatching { httpClient.dispatcher.executorService.shutdown() }
    }
}
