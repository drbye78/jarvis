package com.jarvis.assistant.cognitive

import com.jarvis.assistant.cognitive.behavior.BehaviorArbiter
import com.jarvis.assistant.cognitive.behavior.DeviceSignals
import com.jarvis.assistant.cognitive.behavior.ProactiveSpeaker
import com.jarvis.assistant.cognitive.data.BehaviorLogDao
import com.jarvis.assistant.cognitive.data.CommandEventDao
import com.jarvis.assistant.cognitive.data.EntityDao
import com.jarvis.assistant.cognitive.data.ExtractionQueueDao
import com.jarvis.assistant.cognitive.data.FactVectorDao
import com.jarvis.assistant.cognitive.data.HabitRuleDao
import com.jarvis.assistant.cognitive.data.MemoryMetaDao
import com.jarvis.assistant.cognitive.data.NoopVectorDaos
import com.jarvis.assistant.cognitive.data.SessionSummaryDao
import com.jarvis.assistant.cognitive.data.UserFactDao
import com.jarvis.assistant.cognitive.embed.EmbeddingEngine
import com.jarvis.assistant.cognitive.embed.LexicalEmbedder
import com.jarvis.assistant.cognitive.embed.RetrievalGate
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.tools.ToolStrings
import kotlinx.coroutines.flow.StateFlow

/**
 * P4.4: the grouped dependency bundle of [CognitiveCoordinator]. Pure
 * mechanical regrouping of the former ~35 constructor params (REMEDIATION_PLAN
 * P4.4 item 4): the SAME objects, the SAME defaults, the SAME comments —
 * only the carrier changed. The coordinator aliases each member under its
 * original name, so behavior (and the invariant comments) are untouched.
 *
 * Grouping order mirrors the former constructor sections (plan §4 → §8 → §11).
 * `parentScope` stays a direct constructor parameter of the coordinator: it
 * is lifecycle, not dependency data.
 */
// detekt LongParameterList: most parameters carry defaults (ignored by the
// rule) and the five required ones are under the configured threshold.
@Suppress("LongParameterList")
data class CognitiveDeps(
    // ---- Core stores + extraction (§4/§5/§6) -------------------------------
    val factDao: UserFactDao,
    val queueDao: ExtractionQueueDao,
    val metaDao: MemoryMetaDao,
    val messageDao: com.jarvis.assistant.data.MessageDao,
    val llm: LlmClient,
    // Reactive settings (plan principle 5). MutableStateFlow in tests.
    val memoryEnabled: StateFlow<Boolean>,
    val autoExtractEnabled: StateFlow<Boolean>,
    val cloudEnabled: StateFlow<Boolean>,
    val sensitiveVisible: StateFlow<Boolean>,
    // ---- COGNITIVE_PLAN Phase 2: behaviour layer (§8) -----------------------
    val eventDao: com.jarvis.assistant.cognitive.data.CommandEventDao = com.jarvis.assistant.cognitive.data.NoopBehaviorDaos,
    val ruleDao: com.jarvis.assistant.cognitive.data.HabitRuleDao = com.jarvis.assistant.cognitive.data.NoopBehaviorDaos,
    val behaviorLogDao: com.jarvis.assistant.cognitive.data.BehaviorLogDao = com.jarvis.assistant.cognitive.data.NoopBehaviorDaos,
    val summaryDao: com.jarvis.assistant.cognitive.data.SessionSummaryDao = com.jarvis.assistant.cognitive.data.NoopBehaviorDaos,
    // ---- COGNITIVE_PLAN Phase 3: semantic recall (§11/§12.4-3/§12.4-4) -----
    val vectorDao: FactVectorDao = NoopVectorDaos,
    val entityDao: EntityDao = NoopVectorDaos,
    /** §12.4-3 selector pref value (AUTO | CLOUD | LOCAL | OFF). Reactive. */
    val embedderChoice: StateFlow<String> = kotlinx.coroutines.flow.MutableStateFlow("AUTO"),
    val localEmbedder: EmbeddingEngine = LexicalEmbedder(),
    /** Null = the cloud embeddings branch is not constructed. */
    val cloudEmbedder: EmbeddingEngine? = null,
    /** §10.2 CI ship-or-reject fallback for AUTO ([RetrievalGate]). */
    val localShipsByCiGate: Boolean = RetrievalGate.LOCAL_BRANCH_SHIPS,
    /** §12.4-1: proactive speech ships DEFAULT OFF. */
    val behaviorEnabled: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
    val behaviorQuietStart: StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(23),
    val behaviorQuietEnd: StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(8),
    val behaviorDailyQuota: StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(BehaviorArbiter.DEFAULT_DAILY_QUOTA),
    /** Gates 2/4: DND/battery/media — Android-backed in production, static in tests. */
    val deviceSignals: DeviceSignals = DeviceSignals.Static,
    /** Gate 3: session-state bridge (AppGraph maps the state machine into it). */
    val sessionIdle: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(true),
    /** Gate 5: presence proxy — the newest conversation row's timestamp. */
    val lastInteractionAt: suspend () -> Long? = { null },
    /** §8.4 delivery seam (SessionManager::speakProactively in production). */
    val speaker: ProactiveSpeaker = ProactiveSpeaker { false },
    /** §8.2: which tools may ever become habits (read-mostly + music only). */
    val habitEligibleTools: Set<String> = emptySet(),
    /** Stamped on every summary (plan §10.1: re-run on model change). */
    val modelId: () -> String = { "unknown" },
    /** Device-local hour for the quiet-hours gate; injectable for tests. */
    val hourOfDay: () -> Int = { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) },
    val strings: ToolStrings = ToolStrings.Default,
    val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * Transaction wrapper (AppGraph passes Room `withTransaction`); tests
     * pass the identity. Keeps the coordinator free of the RoomDatabase
     * type. NB: the default must INVOKE the block — `{ it }` would merely
     * return it (the value is the block, not a thunk to call later).
     */
    val inTransaction: suspend (suspend () -> Unit) -> Unit = { block -> block() },
)
