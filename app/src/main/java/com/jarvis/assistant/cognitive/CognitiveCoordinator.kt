package com.jarvis.assistant.cognitive

import com.jarvis.assistant.cognitive.data.ExtractionQueueDao
import com.jarvis.assistant.cognitive.data.ExtractionQueueEntity
import com.jarvis.assistant.cognitive.data.MemoryMetaDao
import com.jarvis.assistant.cognitive.data.MemoryMetaEntity
import com.jarvis.assistant.cognitive.data.UserFactDao
import com.jarvis.assistant.cognitive.behavior.ArgFingerprints
import com.jarvis.assistant.cognitive.behavior.BehaviorArbiter
import com.jarvis.assistant.cognitive.behavior.DeviceSignals
import com.jarvis.assistant.cognitive.behavior.HabitDetector
import com.jarvis.assistant.cognitive.behavior.ProactivePresenter
import com.jarvis.assistant.cognitive.behavior.ProactiveSpeaker
import com.jarvis.assistant.cognitive.data.CommandEventEntity
import com.jarvis.assistant.cognitive.data.HabitRuleEntity
import com.jarvis.assistant.cognitive.extract.ExtractionContract
import com.jarvis.assistant.cognitive.extract.ExtractionGate
import com.jarvis.assistant.cognitive.extract.ExtractionQueueWorker
import com.jarvis.assistant.cognitive.extract.FactNormalizer
import com.jarvis.assistant.cognitive.extract.MemoryWriter
import com.jarvis.assistant.cognitive.extract.Summarizer
import com.jarvis.assistant.cognitive.maint.Maintenance
import com.jarvis.assistant.cognitive.model.FactSnapshot
import com.jarvis.assistant.cognitive.model.FactStatus
import com.jarvis.assistant.cognitive.model.ValidatedFact
import com.jarvis.assistant.cognitive.prompt.FactPhrasing
import com.jarvis.assistant.cognitive.prompt.MemorySectionData
import com.jarvis.assistant.cognitive.prompt.MemorySectionRenderer
import com.jarvis.assistant.cognitive.prompt.renderMemorySection
import com.jarvis.assistant.cognitive.recall.FactRanker
import com.jarvis.assistant.cognitive.recall.SearchTokenizer
import com.jarvis.assistant.cognitive.recall.ScoredFact
import com.jarvis.assistant.cognitive.tools.MemoryOutcome
import com.jarvis.assistant.cognitive.tools.MemoryToolsFactory
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.session.CognitiveTurnHooks
import com.jarvis.assistant.session.TurnOrigin
import com.jarvis.assistant.tools.ToolContract
import com.jarvis.assistant.tools.ToolStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber

/**
 * COGNITIVE_PLAN §4: the ONE class the rest of the app sees. Owns the three
 * asynchronous paths (read / write / maintenance) and the synchronous tool
 * surface, all under a child scope with a [SupervisorJob] and its own
 * exception handler — a crash or hang in cognition must never take a
 * session down (plan §4 concurrency rules).
 *
 * Settings are consumed REACTIVELY (plan principle 5 — the Phase 0
 * PrefsFlow lesson): every read path checks the CURRENT [StateFlow] value,
 * so a Settings toggle applies from the next turn without a restart, and a
 * regression test asserts it (AGENTS.md convention).
 *
 * Kill-switch semantics (plan principle 6): `memoryEnabled=false` → gather
 * renders "", ingest is a no-op, tools report honestly
 * ([MemoryOutcome.Disabled]) — byte-identical prompts to the pre-cognitive
 * baseline, snapshot-tested.
 */
// COGNITIVE_PLAN §4 names this class deliberately: "the ONE class the rest
// of the app sees". All pure logic lives in separate, unit-tested classes
// (FactRanker, HabitDetector, BehaviorArbiter, Summarizer, …); what remains
// here is composition + fire-and-forget orchestration over the child scope.
@Suppress("LargeClass")
class CognitiveCoordinator(
    private val factDao: UserFactDao,
    private val queueDao: ExtractionQueueDao,
    private val metaDao: MemoryMetaDao,
    private val llm: LlmClient,
    private val messageDao: com.jarvis.assistant.data.MessageDao,
    // Reactive settings (plan principle 5). MutableStateFlow in tests.
    private val memoryEnabled: StateFlow<Boolean>,
    private val autoExtractEnabled: StateFlow<Boolean>,
    private val cloudEnabled: StateFlow<Boolean>,
    private val sensitiveVisible: StateFlow<Boolean>,
    // ---- COGNITIVE_PLAN Phase 2: behaviour layer (§8) -----------------------
    private val eventDao: com.jarvis.assistant.cognitive.data.CommandEventDao = com.jarvis.assistant.cognitive.data.NoopBehaviorDaos,
    private val ruleDao: com.jarvis.assistant.cognitive.data.HabitRuleDao = com.jarvis.assistant.cognitive.data.NoopBehaviorDaos,
    private val behaviorLogDao: com.jarvis.assistant.cognitive.data.BehaviorLogDao = com.jarvis.assistant.cognitive.data.NoopBehaviorDaos,
    private val summaryDao: com.jarvis.assistant.cognitive.data.SessionSummaryDao = com.jarvis.assistant.cognitive.data.NoopBehaviorDaos,
    /** §12.4-1: proactive speech ships DEFAULT OFF. */
    private val behaviorEnabled: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false),
    private val behaviorQuietStart: StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(23),
    private val behaviorQuietEnd: StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(8),
    private val behaviorDailyQuota: StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(BehaviorArbiter.DEFAULT_DAILY_QUOTA),
    /** Gates 2/4: DND/battery/media — Android-backed in production, static in tests. */
    private val deviceSignals: DeviceSignals = DeviceSignals.Static,
    /** Gate 3: session-state bridge (AppGraph maps the state machine into it). */
    private val sessionIdle: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(true),
    /** Gate 5: presence proxy — the newest conversation row's timestamp. */
    private val lastInteractionAt: suspend () -> Long? = { null },
    /** §8.4 delivery seam (SessionManager::speakProactively in production). */
    private val speaker: ProactiveSpeaker = ProactiveSpeaker { false },
    /** §8.2: which tools may ever become habits (read-mostly + music only). */
    private val habitEligibleTools: Set<String> = emptySet(),
    /** Stamped on every summary (plan §10.1: re-run on model change). */
    private val modelId: () -> String = { "unknown" },
    /** Device-local hour for the quiet-hours gate; injectable for tests. */
    private val hourOfDay: () -> Int = { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) },
    private val strings: ToolStrings = ToolStrings.Default,
    parentScope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * Transaction wrapper (AppGraph passes Room `withTransaction`); tests
     * pass the identity. Keeps the coordinator free of the RoomDatabase
     * type. NB: the default must INVOKE the block — `{ it }` would merely
     * return it (the value is the block, not a thunk to call later).
     */
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { block -> block() },
) : CognitiveTurnHooks {

    /** Child scope: supervisor + own handler, per plan §4. */
    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob(parent = parentScope.coroutineContext[Job]) +
            Dispatchers.IO +
            CoroutineExceptionHandler { _, e ->
                Timber.e(e, "Cognitive: uncaught exception on the cognitive scope")
                degradedCounter++
            } +
            CoroutineName("cognitive"),
    )

    private val ranker = FactRanker(nowMs)
    private val normalizer = FactNormalizer(nowMs = nowMs)
    private val writer = MemoryWriter(factDao, normalizer)
    private val worker = ExtractionQueueWorker(
        queueDao = queueDao,
        factDao = factDao,
        metaDao = metaDao,
        messageDao = messageDao,
        llm = llm,
        normalizer = normalizer,
    )

    // ---- Phase 2 behaviour layer (§8) ---------------------------------------

    private val habitDetector = HabitDetector(
        eventDao = eventDao,
        ruleDao = ruleDao,
        habitEligibleTools = habitEligibleTools,
        nowMs = nowMs,
    )

    private val summarizer = Summarizer(
        summaryDao = summaryDao,
        messageDao = messageDao,
        metaDao = metaDao,
        llm = llm,
        memoryEnabled = memoryEnabled,
        cloudEnabled = cloudEnabled,
        modelId = modelId,
        nowMs = nowMs,
    ).also { it.background = scope }

    /** Wake signal for the drain loop (coalescing, never blocks the caller). */
    private val wakeChannel = Channel<Unit>(
        capacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    private var drainJob: Job? = null

    /** Observable degraded counter (plan §7.2); exposed for diagnostics. */
    @Volatile
    var degradedCounter: Long = 0
        private set

    // ------------------------------------------------------------------
    // READ PATH (§7): gather ≤ 40 ms, never blocks the turn on failure.
    // ------------------------------------------------------------------

    override suspend fun gather(utterance: String?): String = try {
        withTimeout(GATHER_BUDGET_MS) { gatherInternal(utterance) }
    } catch (e: TimeoutCancellationException) {
        degradedCounter++
        Timber.w("Cognitive: gather exceeded %d ms — rendering without memory", GATHER_BUDGET_MS)
        ""
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        degradedCounter++
        Timber.e(e, "Cognitive: gather failed — rendering without memory")
        ""
    }

    private suspend fun gatherInternal(utterance: String?): String {
        if (!memoryEnabled.value) return ""
        val active = factDao.activeFacts()
        if (active.isEmpty()) return ""

        val visible = active.asSequence()
            .filter { it.status == FactStatus.ACTIVE.name }
            .filter { sensitiveVisible.value || !it.sensitive }
            .map { it.toSnapshot() }
            .toList()
        if (visible.isEmpty()) return ""

        // Lexical union: FTS hits get the plan's +0.3 boost (§7.2).
        val ftsHits = lexicalHits(utterance)
        val ranked = ranker
            .topFacts(visible, utterance, limit = GATHER_POOL, maxPerCategory = SPREAD_POOL)
            .map { scored ->
                if (scored.fact.factId in ftsHits) {
                    scored.copy(score = scored.score + FactRanker.LEXICAL_HIT_BOOST, lexicalHit = true)
                } else {
                    scored
                }
            }
            .sortedWith(compareByDescending<ScoredFact> { it.score }.thenBy { it.fact.factId })
            .take(RECALL_LIMIT)

        if (ranked.isEmpty()) return ""
        writeBehindRecallStats(ranked)

        val data: MemorySectionData = renderMemorySection(ranked, degraded = false, strings)
        return MemorySectionRenderer.render(data, strings)
    }

    private suspend fun lexicalHits(utterance: String?): Set<String> {
        if (utterance.isNullOrBlank()) return emptySet()
        val matchQuery = SearchTokenizer.matchQuery(utterance) ?: return emptySet()
        return try {
            factDao.searchActive(matchQuery).mapTo(HashSet()) { it.factId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Cognitive: FTS search failed — continuing without lexical boost")
            emptySet()
        }
    }

    /** Write-behind recall statistics (plan §7.2) — never on the hot path. */
    private fun writeBehindRecallStats(ranked: List<ScoredFact>) {
        val ids = ranked.map { it.fact.factId }
        scope.launch {
            try {
                factDao.recordRecalls(ids, nowMs())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Cognitive: recall-stat write-behind failed")
            }
        }
    }

    // ------------------------------------------------------------------
    // WRITE PATH (§6): ingest → queue → batched cloud extraction.
    // ------------------------------------------------------------------

    override fun ingest(utterance: String, messageId: Long, origin: TurnOrigin) {
        // The assistant must not learn from its own voice (plan §6.1).
        if (origin != TurnOrigin.VOICE) return
        if (!memoryEnabled.value || !autoExtractEnabled.value) return
        if (!ExtractionGate.shouldExtract(utterance)) return

        scope.launch(CoroutineName("cognitive-ingest")) {
            try {
                val now = nowMs()
                val inserted = queueDao.enqueue(
                    ExtractionQueueEntity(messageId = messageId, createdAt = now, updatedAt = now),
                )
                if (inserted != -1L) {
                    Timber.d("Cognitive: ingested message %d for extraction", messageId)
                    wake()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Cognitive: ingest enqueue failed for message %d", messageId)
            }
        }
    }

    // ------------------------------------------------------------------
    // Queue loop: batching (≤3 / 90 s flush) + 30 s cloud backoff (§6.2).
    // ------------------------------------------------------------------

    /** Starts the drain loop (idempotent). Called by the graph on start. */
    fun startQueueLoop() {
        if (drainJob?.isActive == true) return
        // Settings flips wake the loop so toggles apply live (plan principle
        // 5). The first combine emission is immediate — one harmless wake.
        scope.launch(CoroutineName("cognitive-settings-watch")) {
            kotlinx.coroutines.flow.combine(
                memoryEnabled,
                autoExtractEnabled,
                cloudEnabled,
                sensitiveVisible,
            ) { _, _, _, _ -> Unit }.collect { wake() }
        }
        drainJob = scope.launch(CoroutineName("cognitive-drain")) {
            // Crash recovery: RUNNING rows from a dead process → PENDING
            // (plan §5 idempotency: work is exactly-once per message).
            try {
                queueDao.running().forEach {
                    queueDao.updateState(
                        it.messageId,
                        ExtractionQueueEntity.STATE_PENDING,
                        it.attempt,
                        null,
                        nowMs(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Cognitive: running-row recovery failed")
            }

            while (isActive) {
                val cloudOn = memoryEnabled.value && autoExtractEnabled.value && cloudEnabled.value
                if (!cloudOn) {
                    withTimeoutOrNull(IDLE_WAIT_MS) { wakeChannel.receive() }
                } else {
                    val pending = try {
                        queueDao.pendingCount()
                    } catch (e: Exception) {
                        Timber.w(e, "Cognitive: pendingCount failed"); 0
                    }
                    when {
                        pending == 0 ->
                            withTimeoutOrNull(IDLE_WAIT_MS) { wakeChannel.receive() }

                        else -> {
                            // Flush after the idle window even with <
                            // BATCH_SIZE (plan §6.2: "or flushes after 90 s
                            // idle"); an ingest/settings wake returns early.
                            if (pending < ExtractionQueueWorker.BATCH_SIZE) {
                                withTimeoutOrNull(IDLE_FLUSH_MS) { wakeChannel.receive() }
                            }
                            try {
                                worker.drainOnce()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.e(e, "Cognitive: drain step failed")
                            }
                            if (worker.lastBatchTransportFailed) {
                                kotlinx.coroutines.delay(ExtractionQueueWorker.CLOUD_BACKOFF_MS)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun wake() {
        wakeChannel.trySend(Unit)
    }

    // ------------------------------------------------------------------
    // Synchronous tool surface (§6.4) — deterministic, honest outcomes.
    // ------------------------------------------------------------------

    /**
     * `remember_fact(value, category?, subject?)`: deterministic local
     * write, origin EXPLICIT, confidence 1.0, routed through the SAME
     * normalizer as extraction (plan §6.4).
     */
    suspend fun rememberFact(
        value: String,
        category: String?,
        subject: String?,
    ): MemoryOutcome {
        if (!memoryEnabled.value) return MemoryOutcome.Disabled
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return MemoryOutcome.Failed("empty value")

        val predicate = category?.trim()?.lowercase(java.util.Locale.ROOT)?.ifBlank { "other" } ?: "other"
        val subjectNorm = subject?.let { SearchTokenizer.normalize(it).ifBlank { "user" } } ?: "user"
        val (factCategory, sensitive) = ExtractionContract.categorize(predicate, trimmed)
        val fact = ValidatedFact(
            subject = subjectNorm,
            predicate = predicate,
            value = trimmed,
            confidence = 1f,
            evidence = trimmed, // self-anchored: the tool args ARE the evidence
            messageId = 0L, // explicit writes have no source message
            category = factCategory,
            sensitive = sensitive,
        )
        return try {
            val applied = writer.writeExplicit(fact)
            when (applied) {
                is MemoryWriter.Applied.Confirmed -> MemoryOutcome.Merged(applied.fact.value)
                is MemoryWriter.Applied.Created -> MemoryOutcome.Written(applied.fact.value)
                is MemoryWriter.Applied.Superseded -> MemoryOutcome.Written(applied.new.value)
                is MemoryWriter.Applied.Contested -> MemoryOutcome.NeedsClarification(
                    applied.old.value,
                    applied.new.value,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Cognitive: rememberFact write failed")
            MemoryOutcome.Failed(e.message)
        }
    }

    /**
     * `recall_facts(query?)`: FTS + ranking over ACTIVE facts with honest
     * confidence marks; empty result says so (plan §6.4).
     */
    suspend fun recallFacts(query: String?): MemoryOutcome {
        if (!memoryEnabled.value) return MemoryOutcome.Disabled
        return try {
            val active = factDao.activeFacts()
                .filter { sensitiveVisible.value || !it.sensitive }
                .map { it.toSnapshot() }
            val selected = if (query.isNullOrBlank()) {
                ranker.topFacts(active, null)
            } else {
                val hits = lexicalHits(query)
                ranker.topFacts(active, query)
                    .map { scored ->
                        if (scored.fact.factId in hits) {
                            scored.copy(score = scored.score + FactRanker.LEXICAL_HIT_BOOST, lexicalHit = true)
                        } else {
                            scored
                        }
                    }
                    .sortedWith(compareByDescending<ScoredFact> { it.score }.thenBy { it.fact.factId })
            }
            if (selected.isEmpty()) {
                MemoryOutcome.RecallEmpty
            } else {
                writeBehindRecallStats(selected)
                MemoryOutcome.Recalled(
                    selected.map { FactPhrasing.bullet(it.fact, strings).removePrefix("— ") },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Cognitive: recallFacts failed")
            MemoryOutcome.Failed(e.message)
        }
    }

    /**
     * `forget_fact(query, confirmed=false)`: two-step confirm-then-delete
     * (plan §6.4). `confirmed=true` is only honored with the [token] the
     * candidate step produced — the tool refuses to skip the confirmation.
     */
    suspend fun forgetFact(query: String, confirmed: Boolean, token: String?): MemoryOutcome {
        if (!memoryEnabled.value) return MemoryOutcome.Disabled
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return MemoryOutcome.NothingToForget
        return try {
            val candidates = forgetCandidates(trimmed)
            if (candidates.isEmpty()) return MemoryOutcome.NothingToForget

            if (!confirmed) {
                MemoryOutcome.ForgetCandidates(
                    candidates.map { FactPhrasing.phrase(it) },
                    confirmTokenFor(candidates),
                )
            } else {
                val expected = confirmTokenFor(candidates)
                if (token.isNullOrBlank() || !constantTimeEquals(token, expected)) {
                    // Confirmation without a listed candidate set — refuse
                    // and re-list (the plan's "refuses confirmed=true unless
                    // candidates were listed in the same window").
                    return MemoryOutcome.ForgetCandidates(
                        candidates.map { FactPhrasing.phrase(it) },
                        expected,
                    )
                }
                val now = nowMs()
                candidates.forEach {
                    factDao.updateStatus(it.factId, FactStatus.FORGOTTEN.name, now)
                }
                MemoryOutcome.Forgotten(candidates.joinToString("; ") { FactPhrasing.phrase(it) })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Cognitive: forgetFact failed")
            MemoryOutcome.Failed(e.message)
        }
    }

    private suspend fun forgetCandidates(query: String): List<FactSnapshot> {
        val active = factDao.activeFacts()
            .filter { sensitiveVisible.value || !it.sensitive }
            .map { it.toSnapshot() }
        val tokens = SearchTokenizer.tokens(query).toSet()
        if (tokens.isEmpty()) return emptyList()
        return active.filter { fact ->
            val factTokens = SearchTokenizer
                .tokens(fact.value + " " + fact.subject + " " + fact.predicate)
                .toSet()
            tokens.any { needle -> factTokens.any { it.startsWith(needle) || needle.startsWith(it) } }
        }
    }

    /** Stateless confirmation token over the candidate set (plan §6.4). */
    private fun confirmTokenFor(candidates: List<FactSnapshot>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(
            candidates.map { it.factId }.sorted().joinToString(",").toByteArray(),
        )
        return hash.take(8).joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var acc = 0
        for (i in a.indices) acc = acc or (a[i].code xor b[i].code)
        return acc == 0
    }

    // ------------------------------------------------------------------
    // BEHAVIOUR (§8): telemetry → habits → arbitration → proactive speech.
    // Every entry point is fire-and-forget on the cognitive scope — none
    // of this may ever block a turn or crash a session (§4).
    // ------------------------------------------------------------------

    /**
     * §8.1: one executed tool call lands here (via the ToolRegistry
     * observer wired by AppGraph). Writes the `command_events` row (slot
     * fingerprint ONLY — never raw utterances), reinforces a suggestion the
     * user just accepted (§8.2: a matching command within 10 minutes), and
     * triggers habit recomputation on every 10th event.
     */
    suspend fun recordCommandEvent(tool: String, argsJson: String?, ok: Boolean, latencyMs: Long) {
        try {
            val fingerprint = ArgFingerprints.of(tool, argsJson)
            eventDao.insert(
                CommandEventEntity(
                    at = nowMs(),
                    tool = tool,
                    argsFingerprint = fingerprint,
                    ok = ok,
                    latencyMs = latencyMs,
                    origin = CommandEventEntity.ORIGIN_VOICE,
                ),
            )
            reinforceAccept(tool, fingerprint)
            if (eventDao.countAll() % HABIT_RECOMPUTE_EVERY == 0L) {
                habitDetector.recompute()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Cognitive: command telemetry failed (ignored)")
        }
    }

    /** Non-suspending wrapper for the ToolRegistry observer. */
    fun observeCommandExecution(
        tool: String,
        argsJson: String?,
        ok: Boolean,
        latencyMs: Long,
    ) {
        scope.launch(CoroutineName("cognitive-telemetry")) {
            recordCommandEvent(tool, argsJson, ok, latencyMs)
        }
    }

    /** §8.2: the user executed the suggested command within the window. */
    private suspend fun reinforceAccept(tool: String, fingerprint: String) {
        val now = nowMs()
        for (rule in ruleDao.byFingerprint(tool, fingerprint)) {
            val firedAt = rule.lastFiredAt
            if (firedAt == null || now - firedAt > ACCEPT_WINDOW_MS) continue
            ruleDao.update(
                rule.copy(
                    acceptCount = rule.acceptCount + 1,
                    // First accept completes the first successful suggestion
                    // cycle — PROBATION graduates (§8.2).
                    state = HabitRuleEntity.STATE_ACTIVE,
                ),
            )
            Timber.i("Cognitive: habit accept for %s %s", tool, fingerprint)
        }
    }

    /**
     * §8.3: evaluate every candidate rule against the gate matrix. Called
     * by the behaviour ticker ([startBehaviorLoop]) and after maintenance.
     * Gate 1 short-circuits on the flow value — with the switch OFF (the
     * default) this method is one cheap flow read, nothing else.
     */
    suspend fun evaluateDueRules() {
        if (!behaviorEnabled.value) return
        val now = nowMs()
        val rules = try {
            ruleDao.candidateRules()
        } catch (e: Exception) {
            Timber.w(e, "Cognitive: rule query failed"); return
        }
        if (rules.isEmpty()) return

        val quiet = BehaviorArbiter.isQuietHour(
            hourOfDay(),
            behaviorQuietStart.value,
            behaviorQuietEnd.value,
        )
        val quotaUsedStart = behaviorLogDao.firedSince(BehaviorArbiter.startOfDayMs(now))
        var firedThisPass = 0
        val presence = lastInteractionAt()?.let {
            now - it <= BehaviorArbiter.PRESENCE_WINDOW_MS
        } ?: false
        val signals = deviceSignals
        val idle = sessionIdle.value

        for (rule in rules) {
            val decision = BehaviorArbiter.evaluate(
                rule,
                BehaviorArbiter.ArbiterContext(
                    behaviorEnabled = behaviorEnabled.value,
                    quietHoursActive = quiet,
                    dndActive = signals.dndActive(),
                    batteryOk = signals.batteryOk(),
                    sessionIdle = idle,
                    mediaActive = signals.mediaActive(),
                    recentInteraction = presence,
                    quotaLeft = quotaUsedStart + firedThisPass < behaviorDailyQuota.value,
                    cooldownOk = rule.lastSuggestedAt == null ||
                        now - rule.lastSuggestedAt >= BehaviorArbiter.COOLDOWN_MS,
                    notRecentlyDelivered = rule.lastFiredAt == null ||
                        now - rule.lastFiredAt >= BehaviorArbiter.DELIVERY_FRESHNESS_MS,
                ),
            )
            when (decision) {
                BehaviorArbiter.Decision.Fired -> {
                    fireRule(rule, now)
                    firedThisPass++
                }
                is BehaviorArbiter.Decision.Deferred -> logThrottled(decision, rule, now)
                is BehaviorArbiter.Decision.Blocked -> logThrottled(decision, rule, now)
            }
        }
    }

    /** §8.4: present → speak → bookkeeping. A blank rendering is dropped. */
    private suspend fun fireRule(rule: HabitRuleEntity, now: Long) {
        val text = ProactivePresenter.render(rule, strings)
        if (text.isBlank()) return
        val spoken = speaker.speak(text)
        // The delivery seam returns false only when the machine was no
        // longer IDLE — the arbiter raced a user interaction and lost. The
        // attempt is still logged (the cooldown applies either way: the
        // user must not be nagged twice because a race ate one attempt).
        ruleDao.update(rule.copy(lastSuggestedAt = now, lastFiredAt = now))
        behaviorLogDao.insert(
            BehaviorArbiter.toLogRow(BehaviorArbiter.Decision.Fired, rule.id, now, utterance = text),
        )
        Timber.i(
            "Cognitive: proactive %s (rule %d, tool %s)",
            if (spoken) "delivered" else "refused by session",
            rule.id,
            rule.tool,
        )
    }

    /**
     * Non-FIRED rows are throttled to ≤1 per rule per hour: §8.3 wants every
     * refusal audited, but an IDLE device in front of a TV would otherwise
     * write the same BLOCKED row every tick and drown the log. FIRED rows
     * are never throttled.
     */
    private suspend fun logThrottled(
        decision: BehaviorArbiter.Decision,
        rule: HabitRuleEntity,
        now: Long,
    ) {
        val recent = behaviorLogDao.countForRuleSince(rule.id, now - DECISION_LOG_THROTTLE_MS)
        if (recent > 0) return
        behaviorLogDao.insert(BehaviorArbiter.toLogRow(decision, rule.id, now))
    }

    /**
     * §8.3/§8.2: the behaviour ticker — evaluates due rules periodically
     * (this is also the DEFERRED same-day re-check). Start once from the
     * graph; a no-op while the switch is OFF.
     */
    fun startBehaviorLoop() {
        scope.launch(CoroutineName("cognitive-behavior")) {
            while (isActive) {
                try {
                    evaluateDueRules()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Cognitive: behaviour tick failed")
                }
                kotlinx.coroutines.delay(BEHAVIOR_TICK_MS)
            }
        }
    }

    /**
     * §8.2: the reject half of the accept/reject loop. A SHORT explicit
     * refusal («нет», «не надо») right after a delivered suggestion counts
     * against the rule; 3 rejections mute it for 30 days, 6 retire it.
     * Long or affirmative replies are ignored — only unambiguous refusals
     * punish a rule.
     */
    override fun onFollowUpUtterance(utterance: String) {
        scope.launch(CoroutineName("cognitive-reject")) {
            try {
                if (!isExplicitReject(utterance)) return@launch
                val fired = behaviorLogDao.latestFiredSince(nowMs() - REJECT_WINDOW_MS)
                    ?: return@launch
                val ruleId = fired.ruleId ?: return@launch
                val rule = ruleDao.byId(ruleId) ?: return@launch
                val rejects = rule.rejectCount + 1
                val now = nowMs()
                when {
                    rejects >= RETIRE_REJECTS -> ruleDao.update(
                        rule.copy(rejectCount = rejects, state = HabitRuleEntity.STATE_RETIRED),
                    )
                    rejects >= MUTE_REJECTS -> ruleDao.update(
                        rule.copy(
                            rejectCount = rejects,
                            state = HabitRuleEntity.STATE_MUTED,
                            mutedUntil = now + MUTE_DURATION_MS,
                        ),
                    )
                    else -> ruleDao.update(rule.copy(rejectCount = rejects))
                }
                Timber.i("Cognitive: habit reject #%d for rule %d", rejects, ruleId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Cognitive: reject handling failed")
            }
        }
    }

    /**
     * Conservative refusal heuristic: the utterance is short AND starts
     * with (or equals) an unambiguous refusal token. «Нет» alone rejects;
     * «нет, а что за трек?» does not — a question mark tail is allowed to
     * continue as dialogue (it is NOT a refusal, it is engagement).
     */
    private fun isExplicitReject(utterance: String): Boolean {
        val normalized = ArgFingerprints.normalize(utterance)
        if (normalized.isEmpty()) return false
        if (normalized.length > REJECT_MAX_CHARS) return false
        val tokens = normalized.split(" ")
        val head = tokens.firstOrNull() ?: return false
        if (head in REJECT_HEAD_TOKENS) {
            // «Нет, ...» followed by a real command tail is engagement, not
            // refusal — require the utterance to stay short (it already is)
            // and not contain a follow-up ask.
            return tokens.size <= REJECT_MAX_TOKENS &&
                tokens.none { it in CONTINUATION_TOKENS }
        }
        // Multi-word refusals are unambiguous anywhere in a short utterance.
        return REJECT_PHRASES.any { normalized.contains(it) }
    }

    /**
     * §2.5/§7.1: rendered summary block for the composer (presence-gated,
     * budget-truncated by the Summarizer; same fail-quiet contract as
     * [gather]).
     */
    override suspend fun gatherSummary(utterance: String?, isFollowUp: Boolean): String = try {
        withTimeout(GATHER_BUDGET_MS) { summarizer.renderForPrompt() }
    } catch (e: TimeoutCancellationException) {
        degradedCounter++
        ""
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        degradedCounter++
        Timber.e(e, "Cognitive: summary gather failed")
        ""
    }

    /**
     * §2.5: the ConversationManager prune hook — capture the doomed range
     * BEFORE the delete lands. The local read is synchronous (fast); the
     * cloud call runs on the cognitive scope.
     */
    suspend fun onBeforePrune(cutoffMessageId: Long) {
        try {
            summarizer.captureDoomed(cutoffMessageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Cognitive: prune capture failed — pruning continues")
        }
    }

    // ------------------------------------------------------------------
    // Maintenance (§9.1) — Phase 1 logic; the nightly alarm lands in 2.2.
    // ------------------------------------------------------------------

    suspend fun onMaintenance() {
        val now = nowMs()
        // Every step individually guarded (plan §9.1) so one failure cannot
        // skip the others.
        runCatching { decayInactiveFacts(now) }
            .onFailure { Timber.e(it, "Cognitive: decay step failed") }
        runCatching { compactOverCap() }
            .onFailure { Timber.e(it, "Cognitive: compaction step failed") }
        runCatching { deleteExpiredSuperseded(now) }
            .onFailure { Timber.e(it, "Cognitive: superseded-retention step failed") }
        // ---- Phase 2 steps (§8.2/§2.5/§5 compaction) ----
        runCatching { habitDetector.recompute() }
            .onFailure { Timber.e(it, "Cognitive: habit recompute failed") }
        runCatching { habitDetector.promoteProbationRules(now) }
            .onFailure { Timber.e(it, "Cognitive: habit promotion failed") }
        runCatching { habitDetector.unmuteExpired(now) }
            .onFailure { Timber.e(it, "Cognitive: habit unmute failed") }
        runCatching { eventDao.deleteOlderThan(now - COMMAND_EVENT_RETENTION_MS) }
            .onFailure { Timber.e(it, "Cognitive: command-event retention failed") }
        runCatching { behaviorLogDao.deleteOlderThan(now - BEHAVIOR_LOG_RETENTION_MS) }
            .onFailure { Timber.e(it, "Cognitive: behavior-log retention failed") }
        runCatching { compactSummaries() }
            .onFailure { Timber.e(it, "Cognitive: summary compaction failed") }
        runCatching {
            val made = summarizer.runBacklogAndDigest()
            if (made > 0) Timber.i("Cognitive: %d summary batch(es) produced", made)
        }.onFailure { Timber.e(it, "Cognitive: summarization step failed") }
        runCatching {
            metaDao.putValue(MemoryMetaEntity.KEY_LAST_MAINTENANCE_AT, now.toString())
        }
    }

    /**
     * §5 cap: DAILY summaries beyond [SUMMARY_ROW_CAP] → oldest dropped.
     */
    private suspend fun compactSummaries() {
        var excess = summaryDao.countDaily() - SUMMARY_ROW_CAP
        while (excess > 0) {
            val oldest = summaryDao.oldestDaily() ?: break
            summaryDao.deleteById(oldest.id)
            excess--
        }
    }

    private suspend fun decayInactiveFacts(now: Long) {
        val facts = factDao.allFacts().map { it.toSnapshot() }
        var decayed = 0
        for (fact in facts) {
            if (fact.status != FactStatus.ACTIVE) continue
            val target = Maintenance.decayedConfidence(fact, now)
            if (target < fact.confidence) {
                decayed++
                if (target <= Maintenance.CONFIDENCE_FLOOR) {
                    factDao.updateStatus(fact.factId, FactStatus.ARCHIVED.name, now)
                } else {
                    // Confidence only — updatedAt (ranking recency) and
                    // lastConfirmedAt (usage proof) must NOT be refreshed by
                    // decay, or the decay clock would restart itself.
                    factDao.updateConfidence(fact.factId, target)
                }
            }
        }
        if (decayed > 0) Timber.i("Cognitive: decayed %d fact(s)", decayed)
    }

    private suspend fun compactOverCap() {
        val count = factDao.activeCount()
        if (count <= Maintenance.MAX_ACTIVE_FACTS) return
        val weakest = factDao.weakestActive(count - Maintenance.MAX_ACTIVE_FACTS + BUFFER)
            .map { it.toSnapshot() }
        val candidates = Maintenance.overCapArchiveCandidates(count, weakest)
        candidates.forEach { factDao.updateStatus(it, FactStatus.ARCHIVED.name, nowMs()) }
        Timber.i("Cognitive: archived %d fact(s) over cap", candidates.size)
    }

    private suspend fun deleteExpiredSuperseded(now: Long) {
        val all = factDao.allFacts().map { it.toSnapshot() }
        val expired = Maintenance.expiredSuperseded(all, now)
        if (expired.isNotEmpty()) {
            factDao.deleteByFactIds(expired)
            Timber.i("Cognitive: deleted %d expired superseded row(s)", expired.size)
        }
    }

    // ------------------------------------------------------------------
    // Inspector support (§4/§9.2): observe, wipe, export.
    // ------------------------------------------------------------------

    fun observeFacts() = factDao.observeAll()

    fun observePendingCount() = queueDao.observePendingCount()

    /** Inspector single-item delete: marks FORGOTTEN (audit trail kept). */
    suspend fun forgetById(factId: String) {
        factDao.updateStatus(factId, FactStatus.FORGOTTEN.name, nowMs())
    }

    /** «Забыть всё» (plan §9.2): ALL cognitive tables, never `messages`. */
    suspend fun wipeAll() = inTransaction {
        println("DBG9 wipeAll actually entered")
        factDao.wipeAll()
        queueDao.wipeAll()
        metaDao.wipeAll()
        eventDao.wipeAll()
        ruleDao.wipeAll()
        behaviorLogDao.wipeAll()
        summaryDao.wipeAll()
    }

    /** Export (plan §7 principle 7): every fact + meta, JSON. */
    suspend fun exportJson(): JsonObject {
        val facts = factDao.allFacts()
        val meta = metaDao.all()
        return buildJsonObject {
            put("schemaRev", metaDao.get(MemoryMetaEntity.KEY_SCHEMA_REV) ?: SCHEMA_REV)
            put("exportedAt", nowMs())
            putJsonArray("facts") {
                facts.forEach { fact ->
                    add(
                        buildJsonObject {
                            put("factId", fact.factId)
                            put("category", fact.category)
                            put("subject", fact.subject)
                            put("predicate", fact.predicate)
                            put("value", fact.value)
                            put("confidence", fact.confidence.toDouble())
                            put("origin", fact.origin)
                            put("status", fact.status)
                            put("contested", fact.contested)
                            put("sensitive", fact.sensitive)
                            put("sourceMessageId", fact.sourceMessageId ?: -1)
                            put("createdAt", fact.createdAt)
                            put("updatedAt", fact.updatedAt)
                        },
                    )
                }
            }
            putJsonObject("meta") {
                meta.forEach { put(it.key, it.value) }
            }
        }
    }

    /** Registers the LLM-callable memory tools (plan §6.4). */
    fun tools(): List<ToolContract> = MemoryToolsFactory(this).all()

    /**
     * COGNITIVE_PLAN 1.9: the opt-in backfill entry point (Settings «Память»
     * → «Проанализировать прошлые диалоги»). Delegates to the worker; -1
     * means "already done" (the UI shows the done state).
     */
    suspend fun backfillRecent(limit: Int = ExtractionQueueWorker.BACKFILL_LIMIT): Int {
        val enqueued = worker.backfillRecent(limit)
        if (enqueued > 0) wake()
        return enqueued
    }

    companion object {
        /** Plan §7.2: hard gather budget (hidden inside LLM TTFT). */
        const val GATHER_BUDGET_MS = 40L

        // ---- Phase 2 (§8/§5/§9.1) ----

        /** Recompute habits after every Nth recorded event (§8.2). */
        const val HABIT_RECOMPUTE_EVERY = 10L

        /** §8.2: a matching user command within 10 min = accept. */
        const val ACCEPT_WINDOW_MS = 10 * 60_000L

        /** §8.2: 3 rejections → MUTED… */
        const val MUTE_REJECTS = 3

        /** …for 30 days; */
        const val MUTE_DURATION_MS = 30L * 24 * 60 * 60_000L

        /** 6 lifetime rejections → RETIRED. */
        const val RETIRE_REJECTS = 6

        /** A refusal counts only this soon after a delivered suggestion. */
        const val REJECT_WINDOW_MS = 10 * 60_000L

        /** Refusal heuristic bounds (see [isExplicitReject]). */
        const val REJECT_MAX_CHARS = 40
        const val REJECT_MAX_TOKENS = 5

        /** Unambiguous refusal heads (utterance starts with one of these). */
        private val REJECT_HEAD_TOKENS = setOf(
            "нет", "не", "no", "неа", "стоп", "отстань", "не",
        )

        /** Unambiguous refusal phrases (matched anywhere in a short reply). */
        private val REJECT_PHRASES = setOf(
            "не надо", "не нужно", "не стоит", "не сейчас", "не включай",
            "не беспокой", "не спрашивай", "отстань", "не смей",
        )

        /** A refusal followed by one of these is engagement, not refusal. */
        private val CONTINUATION_TOKENS = setOf(
            "а", "но", "включи", "поставь", "запусти", "да", "давай", "лучше",
            "and", "but", "play", "yes", "instead",
        )

        /** Behaviour ticker cadence (also the DEFERRED same-day re-check). */
        const val BEHAVIOR_TICK_MS = 15 * 60_000L

        /** Non-FIRED decision rows: ≤1 per rule per hour (see [logThrottled]). */
        const val DECISION_LOG_THROTTLE_MS = 60 * 60_000L

        /** §5 retention: command_events 90 days, behavior_log 30 days. */
        const val COMMAND_EVENT_RETENTION_MS = 90L * 24 * 60 * 60_000L
        const val BEHAVIOR_LOG_RETENTION_MS = 30L * 24 * 60 * 60_000L

        /** §5 cap: DAILY summary rows. */
        const val SUMMARY_ROW_CAP = 365

        /** Plan §6.2: idle flush window for a partial batch. */
        const val IDLE_FLUSH_MS = 90_000L

        /** Idle wait when there is nothing to do (woken by ingest/settings). */
        const val IDLE_WAIT_MS = 600_000L

        /** Candidate pool before the final take (FTS-merge headroom). */
        const val GATHER_POOL = 8
        const val SPREAD_POOL = 3

        /** Plan §7.1: ≤5 facts in the prompt. */
        const val RECALL_LIMIT = 5

        /** Compaction over-fetch buffer. */
        const val BUFFER = 10

        const val SCHEMA_REV = "5"

        private val json = Json { prettyPrint = false }

        fun prettyJson(obj: JsonObject): String = json.encodeToString(JsonObject.serializer(), obj)
    }
}
