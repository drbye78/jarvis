package com.jarvis.assistant.di

import android.content.Context
import androidx.room.withTransaction
import com.jarvis.assistant.audio.AudioPipeline
import com.jarvis.assistant.audio.AudioRecordSource
import com.jarvis.assistant.audio.HybridWakeWordDetector
import com.jarvis.assistant.audio.StreamingAudioTrackPlayer
import com.jarvis.assistant.audio.aec.AecMode
import com.jarvis.assistant.audio.aec.AecProbe
import com.jarvis.assistant.audio.aec.LinearResampler
import com.jarvis.assistant.audio.aec.NlmsEchoCanceller
import com.jarvis.assistant.audio.aec.NoopEchoCanceller
import com.jarvis.assistant.audio.aec.PlaybackCaptureFarEndSource
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.config.ProviderSettings
import com.jarvis.assistant.contracts.WakeWordDetector
import com.jarvis.assistant.contracts.WakeWordRequest
import com.jarvis.assistant.data.AppDatabase
import com.jarvis.assistant.data.ConversationManager
import com.jarvis.assistant.llm.GigaChatNativeClient
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.llm.OpenAiCompatClient
import com.jarvis.assistant.llm.TokenManager
import com.jarvis.assistant.llm.YandexAiStudioClient
import com.jarvis.assistant.session.SessionManager
import com.jarvis.assistant.session.SessionStateMachine
import com.jarvis.assistant.speech.SpeechBackend
import com.jarvis.assistant.speech.asr.SberStreamingAsr
import com.jarvis.assistant.speech.asr.StreamingAsrClient
import com.jarvis.assistant.speech.asr.YandexStreamingAsr
import com.jarvis.assistant.speech.tts.SaluteSpeechTts
import com.jarvis.assistant.speech.tts.TtsClient
import com.jarvis.assistant.speech.tts.TtsPlayer
import com.jarvis.assistant.speech.tts.VoiceCatalog
import com.jarvis.assistant.speech.tts.YandexSpeechTts
import com.jarvis.assistant.speech.tts.YandexVoiceSpec
import com.jarvis.assistant.tools.FunctionRouter
import com.jarvis.assistant.util.NetworkMonitor
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.TimeZone
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
            // Defense-in-depth. Any uncaught coroutine exception (a TTS
            // sentence that slipped past local handling, or a state-collector
            // failure on an odd OEM ROM) must not crash the process on an
            // always-listening appliance. Log it; failure paths already report.
            Timber.e(e, "Uncaught coroutine exception in AppGraph scope")
        },
    )

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // long enough for SSE streams
        // Total-call safety net. 120 s exceeds every legitimate
        // user of this client — the session layer caps LLM streams at
        // config.llmTimeoutMs = 45 s, TTS has a per-sentence deadline, the
        // credential probes use 5–15 s — so this only fires on a genuinely
        // stuck call whose socket-level timeouts were being kept alive by
        // trickling data. It must ALWAYS exceed llmTimeoutMs, or it would
        // truncate legitimately slow streams.
        .callTimeout(120, TimeUnit.SECONDS)
        // Sber endpoints (OAuth, GigaChat) chain to the Минцифры "Russian
        // Trusted CA" hierarchy, absent from the stock Android trust store —
        // without this every Sber-facing call dies with
        // "Trust anchor for certification path not found" on API 29.
        .sslSocketFactory(
            com.jarvis.assistant.util.SberTrust.sslContext().socketFactory,
            com.jarvis.assistant.util.SberTrust.compositeTrustManager(),
        )
        .build()

    val saluteChannel: ManagedChannel = OkHttpChannelBuilder
        .forTarget(config.saluteGrpcEndpoint)
        .useTransportSecurity()
        // gRPC's OkHttp transport takes the factory only: trust decisions
        // flow through the SSLContext, which is built from the composite
        // trust manager (system CAs first, Минцифры fallback).
        .sslSocketFactory(com.jarvis.assistant.util.SberTrust.sslContext().socketFactory)
        .build()

    /**
     * Yandex SpeechKit v3 channels. v3 exposes STT and TTS on SEPARATE hosts,
     * so this is two channels where Salute needed one.
     *
     * TRUST: plain system CAs — Yandex's certificates chain to a public root
     * (unlike Sber's Минцифры hierarchy), so NO [com.jarvis.assistant.util.SberTrust]
     * override is used here. Reusing the Sber composite trust manager would
     * work but would needlessly widen the accepted root set for every Yandex
     * call.
     *
     * Both are built eagerly (and cheaply): [OkHttpChannelBuilder] dials on
     * the first RPC, so an unused channel costs one object, not a socket.
     */
    val yandexSttChannel: ManagedChannel = OkHttpChannelBuilder
        .forTarget(config.yandexSttEndpoint)
        .useTransportSecurity()
        .build()

    val yandexTtsChannel: ManagedChannel = OkHttpChannelBuilder
        .forTarget(config.yandexTtsEndpoint)
        .useTransportSecurity()
        .build()

    val database: AppDatabase = AppDatabase.getInstance(appContext)
    val conversationManager = ConversationManager(
        database.messageDao(),
        config.historyMaxMessages,
        config.historyMaxChars,
        // Keep the recent dialogue on disk (LLM window
        // stays historyMaxMessages) so the opt-in backfill has material.
        config.historyRetentionMessages,
        // Summarize-before-prune — the summarizer reads
        // the doomed range BEFORE the retention delete lands (its cloud call
        // is fire-and-forget on the cognitive scope). The lambda resolves
        // the coordinator lazily, so the conversation lane stays
        // pre-cognitive at graph construction.
        beforePrune = { cutoff -> cognitiveCoordinator.onBeforePrune(cutoff) },
        slipToolNames = { com.jarvis.assistant.tools.ToolRisks.byName.keys },
    )

    val networkMonitor = NetworkMonitor(appContext)

    val appPrefs = com.jarvis.assistant.util.AppPrefs(appContext)

    val tokenManager = TokenManager(appContext, httpClient, config)

    val llmClient: LlmClient = when (provider.type) {
        ProviderSettings.Type.GIGACHAT -> GigaChatNativeClient(
            tokenManager = tokenManager,
            httpClient = httpClient,
            endpoint = config.gigaChatNativeEndpoint,
            defaultModel = provider.gigaChatModel,
            // Product decision: web_search is always-on and model-decided
            // (no Settings toggle); the timezone is resolved per call so the
            // user_info block follows the device zone.
            webSearchEnabled = true,
            timezone = { TimeZone.getDefault().id },
        )

        ProviderSettings.Type.OPENAI_COMPAT -> OpenAiCompatClient(
            httpClient = httpClient,
            baseUrl = provider.openAiBaseUrl,
            apiKey = apiKeyFor(),
            defaultModel = provider.openAiModel,
        )

        ProviderSettings.Type.YANDEX -> YandexAiStudioClient(
            httpClient = httpClient,
            // Same Cloud API key as the Yandex speech backend — the live
            // reader (not a snapshot) so a key entered in Settings lands
            // without a restart, exactly like [yandexApiKeyProvider].
            apiKeyProvider = { appPrefs.yandexApiKey },
            endpoint = config.yandexAiStudioEndpoint,
            defaultModel = provider.yandexModel,
            manualFolderId = provider.yandexFolderId,
            // Product decision: web_search is always-on and model-decided
            // (no Settings toggle), matching the GigaChat branch.
            webSearchEnabled = true,
        )
    }

    private fun apiKeyFor(): String =
        // The OpenAI-compatible key lives in the Keystore vault, same as
        // the Sber credentials (AppPrefs.openAiApiKey routes to the same slot).
        appPrefs.openAiApiKey

    /**
     * The active speech backend, SEALED at construction. Each provider needs
     * its own channel and auth scheme (Sber OAuth vs a Yandex API key), so a
     * switch takes effect after the next service restart — the same rule the
     * LLM provider selector follows, and what the Settings hint tells the user.
     *
     * [voiceSource] reads THIS val rather than re-reading the pref, so the
     * microphone/synthesis path and the voice sent to it can never disagree
     * about which provider is in play.
     */
    val speechBackend: SpeechBackend = appPrefs.speechBackend

    /**
     * Yandex API-key provider. Resolved PER CALL (not snapshotted) so entering
     * the key in Settings lands on the next synthesis/recognition without a
     * restart — the same live-read rule as [voiceSource].
     */
    private val yandexApiKeyProvider: () -> String = { appPrefs.yandexApiKey }

    val asrClient: StreamingAsrClient = when (speechBackend) {
        SpeechBackend.SBER -> SberStreamingAsr(
            tokenManager = tokenManager,
            channel = saluteChannel,
            // The gRPC deadline must OUTLIVE the local maxUtteranceMs cap
            // (90s) plus its grace window, or deadline-exceeded races/masks the
            // local no-speech path and misclassifies the outcome.
            deadlineMs = config.asrStreamDeadlineMs + 5_000,
        )

        SpeechBackend.YANDEX -> YandexStreamingAsr(
            apiKeyProvider = yandexApiKeyProvider,
            channel = yandexSttChannel,
            // Same rule as Sber: outlive the local utterance cap.
            deadlineMs = config.asrStreamDeadlineMs + 5_000,
        )
    }

    val ttsClient: TtsClient = when (speechBackend) {
        SpeechBackend.SBER -> SaluteSpeechTts(tokenManager, saluteChannel)

        SpeechBackend.YANDEX -> YandexSpeechTts(
            apiKeyProvider = yandexApiKeyProvider,
            channel = yandexTtsChannel,
        )
    }

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

    companion object {
        /**
         * Far-end reference frame size fed to the canceller: one drain slot
         * (20 ms @ 16 kHz = 320 samples), matching [AudioPipeline.FRAME_MS].
         */
        const val FAR_END_SLOT_SAMPLES = 320

        /**
         * Pure splitter (fix: TTS tap burst truncation) — cuts a resampled
         * far-end chunk into ≤ [FAR_END_SLOT_SAMPLES]-sample frames. A single
         * [com.jarvis.assistant.audio.aec.FarEndMixer.onFrame] larger than the
         * mixer's whole per-lane queue cap (12 slots = 3840 samples ≈ 240 ms)
         * was dropped ENTIRELY by the overflow drop: every TTS chunk longer
         * than 240 ms self-truncated, leaving a visible far-end reference gap
         * (`droppedFarEndFrames` under `AecDiag`). Frame-sized pushes turn a
         * burst into bounded, oldest-first drops instead. Internal for tests.
         */
        internal fun chunkIntoSlotFrames(samples: ShortArray): List<ShortArray> {
            if (samples.isEmpty()) return emptyList()
            val frames = ArrayList<ShortArray>((samples.size + FAR_END_SLOT_SAMPLES - 1) / FAR_END_SLOT_SAMPLES)
            var offset = 0
            while (offset < samples.size) {
                val len = minOf(FAR_END_SLOT_SAMPLES, samples.size - offset)
                frames.add(samples.copyOfRange(offset, offset + len))
                offset += len
            }
            return frames
        }
    }

    /**
     * Own-TTS far-end tap: every PCM chunk the player writes to the speaker
     * is resampled and pushed onto the canceller's far-end grid in
     * slot-sized frames (see [chunkIntoSlotFrames] for why the split is
     * required). Runs on the player's actor thread; the FarEndMixer is
     * synchronized.
     */
    private fun ttsFarEndTap(pcm: ByteArray) {
        val canceller = echoCanceller ?: return
        // 16-bit mono LE → shorts.
        val shorts = ShortArray(pcm.size / 2) { i ->
            (((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8))).toShort()
        }
        val resampled = ttsResampler.process(shorts)
        for (frame in chunkIntoSlotFrames(resampled)) {
            canceller.onFarEndFrame(NlmsEchoCanceller.LANE_TTS, frame)
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
     * Extracts the bundled model once for generated keyword files.
     *
     * DECLARED BEFORE [wakeWordDetector] DELIBERATELY. The detector starts
     * building its engine on its own dispatcher from its constructor, and for a
     * CUSTOM keyword that build calls back into [buildSherpaEngine] →
     * `sherpaModelStore`. When this property sat BELOW the detector as a
     * `by lazy`, that race hit an unassigned delegate and threw
     * `NullPointerException: Lazy.getValue() on a null object reference`,
     * surfacing as "wake-word engine build failed — wake word disabled" —
     * i.e. a custom wake word silently never worked. Declaration order is
     * Kotlin's initialization order, so this must stay ABOVE the detector.
     *
     * Eager (not `by lazy`): the constructor only holds the context; the real
     * extraction happens in [com.jarvis.assistant.audio.SherpaModelStore.ensureExtracted],
     * so constructing it here costs nothing.
     */
    private val sherpaModelStore = com.jarvis.assistant.audio.SherpaModelStore(appContext)

    /**
     * HYBRID wake-word detector. The initial engine is selected from persisted
     * prefs (engine + model). Sherpa uses the bundled model (extracted from
     * assets on first run) unless the user supplied a custom directory.
     */
    val wakeWordDetector: WakeWordDetector = HybridWakeWordDetector(
        frames = audioPipeline.frames,
        context = appContext,
        initialReq = initialWakeRequest(),
        // Custom keywords / extracted & user models resolve here,
        // off the main thread, inside the detector's build path.
        sherpaEngineBuilder = { req -> buildSherpaEngine(req) },
    )
    val player: TtsPlayer = StreamingAudioTrackPlayer(
        scope,
        farEndTap = if (aecMode == AecMode.SOFTWARE) ::ttsFarEndTap else null,
    )

    // Assistant TTS ducks external players; spoken progress
    // phrases («Секунду…») reuse the same serialized player.
    val audioFocus = com.jarvis.assistant.audio.AssistantAudioFocus(
        com.jarvis.assistant.audio.AndroidAudioFocusAdapter(appContext),
    )

    /**
     * The TTS voice resolved LIVE from prefs (Settings «Голос» card),
     * falling back to the config default when the pref is blank. Read per
     * sentence by the turn lane and per phrase by [speechFeedback], so a
     * Settings change applies with no service restart.
     *
     * BACKEND-AWARE: the two providers have disjoint voice namespaces, so the
     * value handed to [ttsClient] always comes from the ACTIVE backend's pref
     * ([speechBackend], sealed at construction). For Yandex the voice, the
     * validated role and the speed are packed in-band by [YandexVoiceSpec] —
     * the convention [YandexSpeechTts] unpacks — with a blank/invalid role
     * collapsing to the bare voice so the service applies its own default.
     */
    val voiceSource: () -> String = {
        when (speechBackend) {
            SpeechBackend.SBER -> appPrefs.ttsVoice.ifBlank { config.ttsVoice }

            SpeechBackend.YANDEX -> packYandexVoice(
                voice = appPrefs.yandexTtsVoice,
                role = appPrefs.yandexTtsRole,
            )
        }
    }

    /**
     * Packs a Yandex speaker with the validated role and the configured speed
     * into the single in-band spec ([YandexVoiceSpec], the one encode
     * definition). [role] is validated against the voice's documented roles, so
     * a stale invalid pref can never reach the synthesis request. A blank voice
     * falls back to the config default.
     */
    private fun packYandexVoice(voice: String, role: String?): String {
        val speaker = voice.ifBlank { config.yandexTtsVoice }
        return YandexVoiceSpec.join(
            voice = speaker,
            role = VoiceCatalog.validRoleFor(speaker, role),
            speed = appPrefs.yandexTtsSpeed,
        )
    }

    val speechFeedback = com.jarvis.assistant.audio.TtsSpeechFeedback(
        scope,
        ttsClient,
        player,
        voiceSource,
        audioFocus,
        appContext,
    )

    /**
     * The graph-owned alarm scheduler, installed into
     * [com.jarvis.assistant.tools.AlarmSchedulerProvider] AT CONSTRUCTION so
     * the voice lane ([functionRouter]) and the UI/ringing lanes share the
     * SAME instance — one armer, hence ONE [com.jarvis.assistant.tools.OneShotGate]
     * lifetime for the exact-alarm degrade note per app run. The provider's
     * lazy fallback remains only for calls that land before any graph exists
     * (a boot alarm with the service not yet started).
     */
    val alarmScheduler = com.jarvis.assistant.tools.AndroidAlarmScheduler(
        database.alarmDao(),
        com.jarvis.assistant.tools.SystemAlertArmer(appContext),
    ).also { com.jarvis.assistant.tools.AlarmSchedulerProvider.install(it) }

    /**
     * The graph-owned ring coordinator, installed into
     * [com.jarvis.assistant.tools.RingCoordinatorProvider] at construction
     * (same rule as [alarmScheduler]). The receiver, the ringing activity and
     * the cancel/delete tools all resolve THIS instance, so the durable
     * `ring_sessions` bookkeeping and the single ring notification cannot fork.
     */
    val ringCoordinator = com.jarvis.assistant.tools.RingCoordinator(
        database.ringSessionDao(),
        alarmScheduler,
        com.jarvis.assistant.tools.AndroidRingPresenter(appContext),
    ).also { com.jarvis.assistant.tools.RingCoordinatorProvider.install(it) }

    val functionRouter = FunctionRouter(
        appContext,
        httpClient,
        speechFeedback,
        // Tool errors are spoken — resolve them from the device locale.
        toolStrings = com.jarvis.assistant.tools.AndroidToolStrings(appContext),
        // Weather geocoding answers in the device language.
        weatherLanguageTag = java.util.Locale.getDefault().language.ifBlank { "ru" },
        // DI fix: ONE alarm scheduler, wired here through the graph (the
        // router no longer reaches AppDatabase.getInstance directly) — and
        // Shared with the provider (see [alarmScheduler]).
        alarmScheduler = alarmScheduler,
        // 0.7: ONE AppPrefs instance graph-wide (the router built its own).
        appPrefs = appPrefs,
        // GEO lane: findPlace / getRoute. The initializer MUST be process-scoped,
        // not graph-scoped: MapKit allows setApiKey only ONCE per process and
        // this graph is rebuilt on every service start / watchdog revive, so a
        // per-graph MapKitInitializer would reset its once-guard and re-set the
        // key (log "already set", potential crash). MapKitInitializerProvider
        // memoizes the one instance for the whole process.
        geoClient = com.jarvis.assistant.geo.mapkit.YandexMapKitGeoClient(
            appContext,
            com.jarvis.assistant.geo.mapkit.MapKitInitializerProvider.get(),
            apiKey = { appPrefs.mapKitApiKey },
        ),
        // Weather: Open-Meteo URLs + GPS timing live in the config, not the tool.
        config = config,
        // remember_fact / recall_facts / forget_fact.
        cognitiveTools = { cognitiveCoordinator.tools() },
        // Command telemetry — every tool execution writes
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
     * Reactive settings. Every wake-word, voice-stop
     * and follow-up pref as a StateFlow; the CognitiveCoordinator's switches
     * are consumed from here, never re-snapshotted at graph build time.
     */
    val prefsFlow by lazy { com.jarvis.assistant.util.PrefsFlow(appPrefs) }

    /**
     * The arbiter bridge: the arbiter needs the session state
     * machine's IDLE-ness without a coordinator→session dependency. The
     * graph owns both ends and keeps this flow in sync (collector started
     * below, right after [sessionManager] exists).
     */
    private val sessionIdleFlow = kotlinx.coroutines.flow.MutableStateFlow(true)

    /**
     * The Cognitive Core. Lazy so graph construction
     * stays off the cognitive path (startup budget ≤ 30 ms); the daos
     * trigger the v3→v4 migration on first touch, off the hot path.
     */
    val cognitiveCoordinator: com.jarvis.assistant.cognitive.CognitiveCoordinator by lazy {
        com.jarvis.assistant.cognitive.CognitiveCoordinator(
            deps = com.jarvis.assistant.cognitive.CognitiveDeps(

                factDao = database.userFactDao(),
                queueDao = database.extractionQueueDao(),
                metaDao = database.memoryMetaDao(),
                messageDao = database.messageDao(),
                llm = llmClient,
                memoryEnabled = prefsFlow.memoryEnabled,
                autoExtractEnabled = prefsFlow.memoryAutoExtract,
                cloudEnabled = prefsFlow.memoryCloudEnabled,
                sensitiveVisible = prefsFlow.memorySensitiveVisible,
                // ---- Behaviour layer ----
                eventDao = database.commandEventDao(),
                ruleDao = database.habitRuleDao(),
                behaviorLogDao = database.behaviorLogDao(),
                summaryDao = database.sessionSummaryDao(),
                // ---- Semantic recall ----
                vectorDao = database.factVectorDao(),
                entityDao = database.entityDao(),
                embedderChoice = prefsFlow.memoryEmbedder,
                cloudEmbedder = com.jarvis.assistant.cognitive.embed.GigaChatEmbedder(
                    embeddingsEndpoint = com.jarvis.assistant.cognitive.embed.GigaChatEmbedder
                        .endpointFor(config.gigaChatEndpoint),
                    postJson = com.jarvis.assistant.cognitive.embed.GigaChatEmbedder
                        .gigaChatHttpTransport(httpClient) { tokenManager.getGigaChatToken() },
                ),
                // Default OFF; the Settings card flips the pref and the
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
                inTransaction = { block -> database.withTransaction { block() } },
            ),
            parentScope = scope,
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
        // Live voice-stop toggle (Settings card, applies from the
        // next turn).
        voiceStopEnabled = { appPrefs.voiceStopEnabled },
        // Memory gather + ingest hooks. Lazily
        // resolved so the session lane never forces the cognitive migration
        // at graph construction.
        cognitive = object : com.jarvis.assistant.session.CognitiveTurnHooks {
            override suspend fun gather(utterance: String?): String =
                cognitiveCoordinator.gather(utterance)

            override fun ingest(utterance: String, messageId: Long, origin: com.jarvis.assistant.session.TurnOrigin) =
                cognitiveCoordinator.ingest(utterance, messageId, origin)

            override suspend fun gatherSummary(utterance: String?, isFollowUpTurn: Boolean): String =
                cognitiveCoordinator.gatherSummary(utterance, isFollowUpTurn)

            override fun onFollowUpUtterance(text: String) =
                cognitiveCoordinator.onFollowUpUtterance(text)

            override fun noteTurnStart(turnId: Int) =
                cognitiveCoordinator.noteTurnStart(turnId)

            override fun noteUserUtterance(turnId: Int, utterance: String) =
                cognitiveCoordinator.noteUserUtterance(turnId, utterance)
        },
        // Pause-on-wake reuses the real tool lane — the same
        // capability-gated control path the LLM uses, incl. the media-key
        // fallback for the app that owns audio focus.
        //
        // AUTHORIZATION: this is an OFF-TURN, app-authored call. STATEFUL now
        // fails closed on an absent context, so bind an explicit system context
        // around it (sentinel id — cannot collide with or clear a live turn's
        // binding). This runs BEFORE runTurn for the same session, so there is
        // no user turn context to disturb. Cleared in `finally` so a later
        // off-turn call cannot inherit it.
        externalMusicPauser = {
            functionRouter.setAuthorizationContext(
                com.jarvis.assistant.tools.ToolAuthorization.SYSTEM_TURN_ID,
                com.jarvis.assistant.tools.TurnAuthorization.system(),
            )
            try {
                functionRouter.executeResult(
                    com.jarvis.assistant.model.FunctionCall(
                        "controlPlayback",
                        """{"action":"pause"}""",
                    ),
                )
            } finally {
                functionRouter.setAuthorizationContext(
                    com.jarvis.assistant.tools.ToolAuthorization.SYSTEM_TURN_ID,
                    null,
                )
            }
        },
    ).also { it.setOnError(onSessionError) }

    /**
     * Keep the arbiter's IDLE view in sync with
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
     * User mute intent. Owned by the SessionManager (so the semantics —
     * stop pipeline + cancel active session + survive power-receiver restarts
     * — stay JVM-testable); exposed here as the observation point for UI.
     */
    val muteState: StateFlow<Boolean> get() = sessionManager.muted

    private fun wakeKeywordPathFor(model: String): String? = when (model) {
        "builtin" -> null
        "custom_user" -> appPrefs.customWakeWordPath.ifBlank { "jarvis_ru.ppn" }
        else -> "jarvis_ru.ppn" // custom_bundled (default)
    }

    /**
     * Build the Sherpa engine for a request, resolving a custom
     * keyword against the bundled model. Runs OFF the main thread inside the
     * detector's build path; any failure throws → the detector surfaces
     * [com.jarvis.assistant.contracts.DetectorState.Failed] with the reason.
     *
     * Resolution:
     * 1. No custom keyword → bundled assets (zero-config, `newFromAsset`)
     *    with wake + stop phrases.
     * 2. Custom keyword → [SherpaModelStore] extraction of the bundled model,
     *    BPE-tokenize the keyword with THAT model's vocab, generate the
     *    keywords file, build via `newFromFile` — the supported custom-keyword flow.
     *
     * Dormant-knob removal (settings-redesign foundation): the old "user model
     * directory" branch read `appPrefs.sherpaOnnxPath`, a pref with NO writer,
     * NO UI and NO test reference (only ever blank). It is deleted here so the
     * custom-keyword path above stays the single, supported model source.
     */
    private fun buildSherpaEngine(req: WakeWordRequest): com.jarvis.assistant.audio.SherpaKwsEngine {
        val customKeyword = req.sherpaCustomKeyword?.trim().orEmpty()
        if (customKeyword.isEmpty()) {
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

        val modelDir = sherpaModelStore.ensureExtracted()
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
        return com.jarvis.assistant.audio.SherpaKwsEngine(
            context = appContext,
            sensitivity = req.sensitivity,
            entries = entries,
            modelSource = com.jarvis.assistant.audio.SherpaModelSource.Directory(
                dir = modelDir.absolutePath,
                provider = "xnnpack", // the bundled model ships an xnnpack provider
            ),
            generatedKeywordsContent =
            com.jarvis.assistant.audio.SherpaKeywords.toKeywordsFileContent(entries),
            workDir = modelDir, // our own extraction — writable by construction
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
     * the stale value; the "slider no-op" finding).
     */
    suspend fun reconfigureWakeWord() {
        wakeWordDetector.reconfigure(buildWakeRequest())
    }

    /**
     * «Проверить голос» from the Settings card — speaks one sample
     * sentence through the REAL synthesis + player lane, focus-bracketed
     * like a turn sentence, best-effort (a failed probe is logged, not
     * surfaced as a session error).
     *
     * @param voiceOverride the voice to probe; null = the currently saved pref.
     */
    fun speakVoiceSample(voiceOverride: String? = null) {
        val voice = if (voiceOverride == null) {
            voiceSource()
        } else if (speechBackend == SpeechBackend.YANDEX) {
            // The probe may hand us a bare or role-packed Yandex spec; re-pack
            // through the single helper so the preview also carries the saved
            // SPEED and the role is validated.
            val (name, role) = YandexVoiceSpec.split(voiceOverride)
            packYandexVoice(voice = name, role = role)
        } else {
            voiceOverride
        }
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
                // Cleanup, then RETHROW — swallowing cancellation here
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
            // Queue loop starts with the service; the
            // first lazy touch of the coordinator runs the v3→v4 migration.
            cognitiveCoordinator.startQueueLoop()
            // The behaviour ticker (no-op while the
            // switch is OFF).
            cognitiveCoordinator.startBehaviorLoop()
            // Purge cloud vector spaces the moment memory.cloudEnabled flips false.
            cognitiveCoordinator.startCloudPurgeWatch()
        } catch (e: Exception) {
            shutdown() // Tear down anything we built before the throw
            throw e
        }
    }

    fun shutdown() {
        // Every teardown is best-effort so shutdown() is safe to call even
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
        // Yandex v3 own their own two channels (STT + TTS hosts are distinct);
        // leaving them up would pin sockets to Yandex across every graph
        // rebuild (provider change, watchdog restart).
        runCatching { yandexSttChannel.shutdown().awaitTermination(2, TimeUnit.SECONDS) }
        runCatching { yandexTtsChannel.shutdown().awaitTermination(2, TimeUnit.SECONDS) }
        // The gRPC channel was torn down but the OkHttp client's pooled
        // connections and dispatcher threads were not — every graph rebuild
        // (provider change, watchdog restart) previously left them lingering
        // for their 60-s/5-s idle timeouts, holding sockets to LLM endpoints.
        runCatching { httpClient.connectionPool.evictAll() }
        runCatching { httpClient.dispatcher.executorService.shutdown() }
    }
}
