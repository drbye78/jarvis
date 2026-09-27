package com.jarvis.assistant.cognitive

import com.jarvis.assistant.cognitive.behavior.ArgFingerprints
import com.jarvis.assistant.cognitive.behavior.BehaviorArbiter
import com.jarvis.assistant.cognitive.behavior.HabitDetector
import com.jarvis.assistant.cognitive.behavior.ProactivePresenter
import com.jarvis.assistant.cognitive.data.CommandEventEntity
import com.jarvis.assistant.cognitive.data.ExtractionQueueEntity
import com.jarvis.assistant.cognitive.data.FactEntityLinkEntity
import com.jarvis.assistant.cognitive.data.HabitRuleEntity
import com.jarvis.assistant.cognitive.data.MemoryMetaEntity
import com.jarvis.assistant.cognitive.embed.BenchmarkRunner
import com.jarvis.assistant.cognitive.embed.EmbeddingEngine
import com.jarvis.assistant.cognitive.embed.VectorBackfill
import com.jarvis.assistant.cognitive.entity.EntityIndex
import com.jarvis.assistant.cognitive.extract.ExtractionContract
import com.jarvis.assistant.cognitive.extract.ExtractionGate
import com.jarvis.assistant.cognitive.extract.ExtractionQueueLoop
import com.jarvis.assistant.cognitive.extract.ExtractionQueueWorker
import com.jarvis.assistant.cognitive.extract.FactNormalizer
import com.jarvis.assistant.cognitive.extract.MemoryWriter
import com.jarvis.assistant.cognitive.extract.Summarizer
import com.jarvis.assistant.cognitive.maint.Maintenance
import com.jarvis.assistant.cognitive.model.FactSnapshot
import com.jarvis.assistant.cognitive.model.FactStatus
import com.jarvis.assistant.cognitive.model.ValidatedFact
import com.jarvis.assistant.cognitive.prompt.FactPhrasing
import com.jarvis.assistant.cognitive.recall.FactRanker
import com.jarvis.assistant.cognitive.recall.RecallPipeline
import com.jarvis.assistant.cognitive.recall.SearchTokenizer
import com.jarvis.assistant.cognitive.tools.ForgetConfirmation
import com.jarvis.assistant.cognitive.tools.MemoryOutcome
import com.jarvis.assistant.cognitive.tools.MemoryToolsFactory
import com.jarvis.assistant.session.CognitiveTurnHooks
import com.jarvis.assistant.session.TurnOrigin
import com.jarvis.assistant.tools.ToolContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The ONE class the rest of the app sees. Owns the three
 * asynchronous paths (read / write / maintenance) and the synchronous tool
 * surface, all under a child scope with a [SupervisorJob] and its own
 * exception handler — a crash or hang in cognition must never take a
 * session down.
 *
 * Settings are consumed REACTIVELY (the PrefsFlow lesson): every read path checks the CURRENT [StateFlow] value,
 * so a Settings toggle applies from the next turn without a restart, and a
 * regression test asserts it (AGENTS.md convention).
 *
 * Kill-switch semantics: `memoryEnabled=false` → gather
 * renders "", ingest is a no-op, tools report honestly
 * ([MemoryOutcome.Disabled]) — byte-identical prompts to the pre-cognitive
 * baseline, snapshot-tested.
 */
// This class is deliberately the ONE class the rest
// of the app sees. All pure logic lives in separate, unit-tested classes
// (FactRanker, HabitDetector, BehaviorArbiter, Summarizer, …); what remains
// here is composition + fire-and-forget orchestration over the child scope.
@Suppress("LargeClass", "TooManyFunctions")
class CognitiveCoordinator(
    /**
     * The former ~35 constructor params, grouped into [CognitiveDeps]
     * (pure mechanical regrouping — same objects, same defaults). The body
     * keeps the original names via the alias block below, so no invariant
     * comment moved because of the grouping.
     */
    private val deps: CognitiveDeps,
    parentScope: CoroutineScope,
) : CognitiveTurnHooks {

    /** Child scope: supervisor + own handler. */
    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob(parent = parentScope.coroutineContext[Job]) +
            deps.cognitiveDispatcher +
            CoroutineExceptionHandler { _, e ->
                Timber.e(e, "Cognitive: uncaught exception on the cognitive scope")
                degradedCounterAtomic.incrementAndGet()
            } +
            CoroutineName("cognitive"),
    )

    // The former constructor params, carried by [deps] and aliased
    // under their original names — the body and its invariant comments are
    // untouched by the regrouping. Declared FIRST so the component
    // initializers below keep evaluating in the original order.
    private val factDao = deps.factDao
    private val queueDao = deps.queueDao
    private val metaDao = deps.metaDao
    private val messageDao = deps.messageDao
    private val llm = deps.llm
    private val memoryEnabled = deps.memoryEnabled
    private val autoExtractEnabled = deps.autoExtractEnabled
    private val cloudEnabled = deps.cloudEnabled
    private val sensitiveVisible = deps.sensitiveVisible
    private val eventDao = deps.eventDao
    private val ruleDao = deps.ruleDao
    private val behaviorLogDao = deps.behaviorLogDao
    private val summaryDao = deps.summaryDao
    private val vectorDao = deps.vectorDao
    private val entityDao = deps.entityDao
    private val embedderChoice = deps.embedderChoice
    private val localEmbedder = deps.localEmbedder
    private val cloudEmbedder = deps.cloudEmbedder
    private val localShipsByCiGate = deps.localShipsByCiGate
    private val behaviorEnabled = deps.behaviorEnabled
    private val behaviorQuietStart = deps.behaviorQuietStart
    private val behaviorQuietEnd = deps.behaviorQuietEnd
    private val behaviorDailyQuota = deps.behaviorDailyQuota
    private val deviceSignals = deps.deviceSignals
    private val sessionIdle = deps.sessionIdle
    private val lastInteractionAt = deps.lastInteractionAt
    private val speaker = deps.speaker
    private val habitEligibleTools = deps.habitEligibleTools
    private val modelId = deps.modelId
    private val hourOfDay = deps.hourOfDay
    private val strings = deps.strings
    private val nowMs = deps.nowMs
    private val inTransaction = deps.inTransaction
    private val cpuDispatcher = deps.cpuDispatcher
    private val elapsedNow = deps.elapsedNow

    private val ranker = FactRanker(nowMs)
    private val normalizer = FactNormalizer(nowMs = nowMs)
    private val writer = MemoryWriter(factDao, normalizer, inTransaction)
    private val worker = ExtractionQueueWorker(
        queueDao = queueDao,
        factDao = factDao,
        metaDao = metaDao,
        messageDao = messageDao,
        llm = llm,
        normalizer = normalizer,
        inTransaction = inTransaction,
        memoryEnabled = memoryEnabled,
    )

    /** Export (Inspector support) extracted; composition only. */
    private val factExport = FactExportService(
        factDao = factDao,
        metaDao = metaDao,
        entityDao = entityDao,
        nowMs = nowMs,
    )

    /** Benchmark orchestration extracted; delegates. */
    private val benchmarkRunner = BenchmarkRunner(
        metaDao = metaDao,
        localEmbedder = localEmbedder,
        cloudEmbedder = cloudEmbedder,
        // The real egress gate, read per run() — the same
        // reactive pattern VectorBackfill uses (never a graph-build value).
        cloudEnabled = { cloudEnabled.value },
        nowMs = nowMs,
    )

    // ---- Behaviour layer ---------------------------------------------------

    /**
     * Serializes read-modify-write cycles on habit-rule rows (reject /
     * accept counters, mute transitions, fire bookkeeping). The reject
     * path is fire-and-forget per utterance: three rapid refusals launch
     * three concurrent coroutines whose `byId` → `rejectCount + 1` →
     * `update` cycles interleave on the Dispatchers.IO pool and LOSE
     * increments — the 3-strikes mute then never happens (reproduced:
     * 70/300 scenarios ended at rejectCount 1–2). A lock per cycle here
     * is microseconds and only ever contended between these rare paths.
     *
     * The SAME mutex is injected into [HabitDetector], whose rule
     * writes (recompute / promoteProbationRules / unmuteExpired —
     * nightly-ticker paths) were previously uncovered: they do
     * read-modify-write cycles on the same rows this class writes from
     * the reject/accept/fire paths. NOT reentrant — no method here calls
     * a [HabitDetector] write from inside a `withLock` block.
     */
    private val ruleWriteMutex = Mutex()

    private val habitDetector = HabitDetector(
        eventDao = eventDao,
        ruleDao = ruleDao,
        habitEligibleTools = habitEligibleTools,
        nowMs = nowMs,
        ruleWriteMutex = ruleWriteMutex,
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

    /** Opt-in vector builder (Settings «Построить векторы»). */
    val vectorBackfill = VectorBackfill(
        factDao = factDao,
        vectorDao = vectorDao,
        metaDao = metaDao,
        cloudEnabled = { cloudEnabled.value },
        inTransaction = inTransaction,
        nowMs = nowMs,
    )

    /** Observable degraded counter; exposed for diagnostics. */
    private val degradedCounterAtomic = AtomicLong()

    val degradedCounter: Long get() = degradedCounterAtomic.get()

    /**
     * Reactive wake for the drain loop: any cognitive setting flip re-applies
     * live (config is consumed reactively, never snapshotted).
     * The loop receives only "something changed" — it stays free of the
     * settings vocabulary the coordinator owns.
     */
    private val settingsChanged: Flow<Unit> = combine(
        memoryEnabled,
        autoExtractEnabled,
        cloudEnabled,
        sensitiveVisible,
        embedderChoice,
    ) { _, _, _, _, _ -> Unit }

    /** Read path (extracted seam): ranking, engine resolution, rendering. */
    private val recall = RecallPipeline(
        deps = deps,
        scope = scope,
        onDegraded = { degradedCounterAtomic.incrementAndGet() },
    )

    /** Drain loop (extracted seam): batching, pacing, crash recovery. */
    private val queueLoop = ExtractionQueueLoop(
        scope = scope,
        queueDao = queueDao,
        worker = worker,
        memoryEnabled = memoryEnabled,
        autoExtractEnabled = autoExtractEnabled,
        cloudEnabled = cloudEnabled,
        nowMs = nowMs,
        settingsChanged = settingsChanged,
    )

    // ------------------------------------------------------------------
    // READ PATH: gather ≤ 40 ms, never blocks the turn on failure.
    // ------------------------------------------------------------------

    /**
     * One gather per turn. The read path itself (ranking, engine
     * resolution, rendering, phase-budget degradation) is the [RecallPipeline]
     * seam; this remains the session-facing entry point.
     */
    override suspend fun gather(utterance: String?): String = recall.gather(utterance)

    // ------------------------------------------------------------------
    // WRITE PATH: ingest → queue → batched cloud extraction.
    // ------------------------------------------------------------------

    override fun ingest(utterance: String, messageId: Long, origin: TurnOrigin) {
        // The assistant must not learn from its own voice.
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
                    queueLoop.wake()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Cognitive: ingest enqueue failed for message %d", messageId)
            }
        }
    }

    /**
     * Starts the drain loop (idempotent, called by the graph on start). The
     * batching/pacing logic itself is the [ExtractionQueueLoop] seam.
     */
    fun startQueueLoop() = queueLoop.start()

    // ------------------------------------------------------------------
    // Synchronous tool surface — deterministic, honest outcomes.
    // ------------------------------------------------------------------

    /**
     * `remember_fact(value, category?)`: deterministic local write, origin
     * EXPLICIT, confidence 1.0, routed through the SAME normalizer as
     * extraction.
     *
     * Subject anchoring (owner decision): the ONLY subject is
     * `user`. The tool surface no longer advertises a `subject` parameter, but
     * this method stays the enforcement point for any off-contract value a
     * model smuggles into the raw arguments: it is routed through the SAME
     * [ExtractionContract.sanitizeSubject] gate as the extraction parser, and
     * an unsanctioned subject is REJECTED with an honest
     * [MemoryOutcome.Failed] — never silently coerced onto `user`, so the
     * refusal is visible to the model and the transcript. Blank/absent keeps
     * the documented `user` default.
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
        val subjectNorm = ExtractionContract.sanitizeSubject(subject)
            ?: return MemoryOutcome.Failed("unsupported subject")
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
            // e.message can QUOTE the fact row that broke the
            // DAO/serializer — user memory content — and [detail] crosses into
            // the LLM tool-result JSON AND the spoken user string
            // (MemoryOutcome.spoken → memoryWriteFailed), while the throwable
            // here would persist the trace to the rotating log (LogScrubber
            // deliberately does not match `near '...'` quoted spans). Only the
            // exception CLASS crosses; the stack stays DEBUG (not persisted).
            Timber.e("Cognitive: rememberFact write failed (%s)", e.javaClass.name)
            Timber.d(e, "Cognitive: rememberFact write failed detail")
            MemoryOutcome.Failed("write failure (${e.javaClass.simpleName})")
        }
    }

    /**
     * `recall_facts(query?)`: FTS + ranking over ACTIVE facts with honest
     * confidence marks; empty result says so.
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
                recall.rankedForQuery(active, query)
            }
            if (selected.isEmpty()) {
                MemoryOutcome.RecallEmpty
            } else {
                recall.writeBehindRecallStats(selected)
                MemoryOutcome.Recalled(
                    selected.map { FactPhrasing.bullet(it.fact, strings).removePrefix("— ") },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Same sanitization contract as rememberFact.
            Timber.e("Cognitive: recallFacts failed (%s)", e.javaClass.name)
            Timber.d(e, "Cognitive: recallFacts failure detail")
            MemoryOutcome.Failed("query failure (${e.javaClass.simpleName})")
        }
    }

    /**
     * A candidate listing armed for a specific
     * turn, user-utterance ordinal and query. Confirmation is bound to TURN
     * PROVENANCE + an EXPLICIT AFFIRMATION, not to a token handed to the model:
     * the token used to ride in the same tool-result JSON as the candidates, so
     * with `maxToolPasses = 5` the model could echo it back with
     * `confirmed=true` inside the SAME turn — the "confirmation" was never a
     * user gate. A grant now requires the IMMEDIATELY-NEXT user turn to carry
     * a genuine affirmative, which makes "any strictly later utterance" («нет»,
     * an unrelated question, …) insufficient. Bounded by both a turn-age bound
     * and a wall-clock TTL so it cannot linger for a hundred turns.
     */
    private data class PendingForget(
        val turnId: Int,
        val ordinal: Int,
        val factIds: Set<String>,
        val query: String,
        val armedAtMs: Long,
    )

    /** The turn currently executing ([noteTurnStart]); 0 = none observed yet. */
    private val currentTurnId = AtomicInteger(0)

    /** The finalized user utterance of a turn, bound to that turn's id. */
    private data class UtteranceBinding(val turnId: Int, val text: String)

    /** The most recent finalized user utterance (turn id + text), or null. */
    private val utteranceBinding = AtomicReference<UtteranceBinding?>(null)

    /**
     * Monotonic count of FINALIZED USER UTTERANCES (one per speech-bearing
     * turn, regardless of session-seq gaps from supersede/cancelAll). The
     * pending grant stores the ordinal it was armed at; confirmation requires
     * the current ordinal to be exactly one greater, i.e. the immediately-next
     * user turn — seq gaps never widen the window.
     */
    private val utteranceOrdinal = AtomicInteger(0)

    /** The candidate set armed for confirmation, with its provenance. */
    private val pendingForget = AtomicReference<PendingForget?>(null)

    /**
     * Serializes the forget read-check-act cycle (arm / honor / clear). The
     * coroutine Mutex (not a thread monitor) is used deliberately: the guarded
     * block suspends on the Room DAOs, which a `synchronized` monitor must not
     * straddle.
     */
    private val forgetMutex = Mutex()

    /**
     * Records the turn that is starting. Called once at
     * the top of `TurnRunner.runTurn`, before any ASR/LLM/tool work, so a
     * candidate listing can be bound to the turn that produced it.
     */
    override fun noteTurnStart(turnId: Int) {
        currentTurnId.set(turnId)
    }

    /**
     * Records the FINALIZED user utterance of
     * [turnId] (called by `TurnRunner` right after ASR finalizes). [turnId] is
     * the SAME session seq passed to [noteTurnStart], so the coordinator can
     * bind utterance → turn and refuse to honor a confirmation whose turn is
     * not the currently-executing one.
     *
     * Also invalidates a SUPERSEDED grant: a pending armed at ordinal `k` is
     * valid only for ordinal `k+1`; once a later utterance arrives more than
     * one turn after the arming (because no tool call re-listed it), the grant
     * is dead. This is what stops a stale grant surviving indefinitely when
     * turns pass without a re-list.
     */
    override fun noteUserUtterance(turnId: Int, utterance: String) {
        val ordinal = utteranceOrdinal.incrementAndGet()
        utteranceBinding.set(UtteranceBinding(turnId, utterance))
        if (ordinal > FORGET_MAX_UTTERANCE_GAP) {
            val stale = pendingForget.get()
            if (stale != null && stale.ordinal < ordinal - FORGET_MAX_UTTERANCE_GAP) {
                pendingForget.compareAndSet(stale, null)
            }
        }
    }

    /**
     * `forget_fact(query, confirmed=false)`: two-step confirm-then-delete.
     * The read-check-act cycle is serialized on [forgetMutex]; the
     * whole check runs in [forgetFactLocked]. A same-turn, absent, stale,
     * expired, mismatched or NON-AFFIRMATIVE confirmation is refused and the
     * candidates are re-listed — never a silent success.
     */
    suspend fun forgetFact(query: String, confirmed: Boolean): MemoryOutcome {
        if (!memoryEnabled.value) return MemoryOutcome.Disabled
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return MemoryOutcome.NothingToForget
        return try {
            forgetMutex.withLock { forgetFactLocked(trimmed, confirmed) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Same sanitization contract as rememberFact. The
            // grant is deliberately LEFT ARMED on failure: the pending clear
            // happens only after the whole delete loop succeeds (see below), so
            // a partial delete does not consume the confirmation as if it had
            // succeeded. A retry re-derives the candidate set from ACTIVE
            // facts only, so already-forgotten rows drop out of the set — a
            // leftover grant can never authorize a second delete and the model
            // is told Failed, never success.
            Timber.e("Cognitive: forgetFact failed (%s)", e.javaClass.name)
            Timber.d(e, "Cognitive: forgetFact failure detail")
            MemoryOutcome.Failed("delete failure (${e.javaClass.simpleName})")
        }
    }

    /**
     * The read-check-act body of [forgetFact], under [forgetMutex]. Deletes
     * happen BEFORE the grant is cleared: on any failure the exception
     * propagates to [forgetFact]'s catch while the grant stays armed, so a
     * partial delete is reported as [MemoryOutcome.Failed] and never consumes
     * the confirmation as though it had succeeded.
     */
    @Suppress("ComplexCondition") // the single, auditable confirmation predicate
    private suspend fun forgetFactLocked(trimmed: String, confirmed: Boolean): MemoryOutcome {
        val candidates = forgetCandidates(trimmed)
        val candidateIds = candidates.mapTo(HashSet()) { it.factId }
        val expectedQuery = SearchTokenizer.normalize(trimmed)
        val issuedTurn = currentTurnId.get()
        val ordinal = utteranceOrdinal.get()
        val binding = utteranceBinding.get()
        val now = nowMs()
        val pending = pendingForget.get()

        val honored = confirmed &&
            candidates.isNotEmpty() &&
            pending != null &&
            binding != null &&
            binding.turnId == issuedTurn &&
            ForgetConfirmation.isAffirmative(binding.text) &&
            pending.ordinal == ordinal - FORGET_MAX_UTTERANCE_GAP &&
            issuedTurn > pending.turnId &&
            now - pending.armedAtMs <= FORGET_PENDING_TTL_MS &&
            pending.factIds == candidateIds &&
            pending.query == expectedQuery

        return when {
            honored -> {
                // Delete FIRST, clear the grant only after the whole loop
                // succeeded. If any updateStatus throws, the exception reaches
                // [forgetFact] with the grant still armed (no double-delete:
                // candidates are ACTIVE facts only).
                val forNow = nowMs()
                candidates.forEach {
                    factDao.updateStatus(it.factId, FactStatus.FORGOTTEN.name, forNow)
                }
                pendingForget.set(null)
                MemoryOutcome.Forgotten(candidates.joinToString("; ") { FactPhrasing.phrase(it) })
            }
            candidates.isEmpty() -> {
                // Nothing matched — drop any stale pending record.
                pendingForget.set(null)
                MemoryOutcome.NothingToForget
            }
            else -> {
                // Refuse and re-list. Re-arm this set for the CURRENT turn
                // (superseding any stale/mismatched/expired record) so a
                // genuine confirmation in the immediately-next user turn still
                // works; the same turn can never confirm its own listing. If
                // the running turn has no bound utterance yet, arm nothing —
                // an unbindable grant could never be legitimately confirmed.
                val canArm = binding != null && binding.turnId == issuedTurn
                pendingForget.set(
                    if (canArm) {
                        PendingForget(
                            turnId = issuedTurn,
                            ordinal = ordinal,
                            factIds = candidateIds,
                            query = expectedQuery,
                            armedAtMs = now,
                        )
                    } else {
                        null
                    },
                )
                MemoryOutcome.ForgetCandidates(candidates.map { FactPhrasing.phrase(it) })
            }
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

    // ------------------------------------------------------------------
    // BEHAVIOUR: telemetry → habits → arbitration → proactive speech.
    // Every entry point is fire-and-forget on the cognitive scope — none
    // of this may ever block a turn or crash a session.
    // ------------------------------------------------------------------

    /**
     * One executed tool call lands here (via the ToolRegistry
     * observer wired by AppGraph). Writes the `command_events` row (slot
     * fingerprint ONLY — never raw utterances), reinforces a suggestion the
     * user just accepted (a matching command within 10 minutes), and
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

    // ------------------------------------------------------------------
    // SEMANTIC RECALL — user-facing entries: the Settings
    // card calls these. All are opt-in; nothing here runs on a timer.
    // ------------------------------------------------------------------

    /**
     * The Settings-card benchmark: the orchestration lives in
     * [BenchmarkRunner]; the coordinator stays the ONE
     * entry point the app sees.
     */
    suspend fun runRetrievalBenchmark(): BenchmarkRunner.BenchmarkOutcome = benchmarkRunner.run()

    /**
     * Settings seam: the engine the selector resolves to RIGHT
     * NOW (null = vectors off). The vectors action builds for THIS engine.
     */
    suspend fun resolvedEngineId(): String? = recall.resolveActiveEngine()?.engineId

    /**
     * Start the opt-in vector build for [engineId] on the
     * cognitive scope. Returns false when the engine is unknown or a run
     * is already in progress; progress is observable via
     * [vectorBackfill.progress]. The CALLER owns the privacy dialog for
     * the CLOUD branch (fact values egress).
     */
    fun startVectorBuild(engineId: String): Boolean {
        val engine = recall.engineById(engineId) ?: return false
        if (vectorBackfill.progress.value?.running == true) return false
        scope.launch(CoroutineName("cognitive-vector-backfill")) {
            try {
                val written = vectorBackfill.runFor(engine)
                Timber.i("Cognitive: vector backfill wrote %d vectors (%s)", written, engineId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Cognitive: vector backfill failed")
            }
        }
        return true
    }

    /** The user executed the suggested command within the window. */
    private suspend fun reinforceAccept(tool: String, fingerprint: String) {
        val now = nowMs()
        // Same read-modify-write discipline as the reject path: an accept
        // and a reject can overlap on the same rule row (fire-and-forget
        // launches on both sides), and either losing an increment corrupts
        // the reinforcement loop.
        ruleWriteMutex.withLock {
            for (rule in ruleDao.byFingerprint(tool, fingerprint)) {
                val firedAt = rule.lastFiredAt
                if (firedAt == null || now - firedAt > ACCEPT_WINDOW_MS) continue
                ruleDao.update(
                    rule.copy(
                        acceptCount = rule.acceptCount + 1,
                        // First accept completes the first successful
                        // suggestion cycle — PROBATION graduates.
                        state = HabitRuleEntity.STATE_ACTIVE,
                    ),
                )
                // The fingerprint is a projection of user-set slot values
                // (AGENTS.md content rule): INFO keeps the tool name only,
                // the fingerprint rides DEBUG.
                Timber.i("Cognitive: habit accept for %s", tool)
                Timber.d("Cognitive: habit accept for %s %s", tool, fingerprint)
            }
        }
    }

    /**
     * Evaluate every candidate rule against the gate matrix. Called
     * by the behaviour ticker ([startBehaviorLoop]) and after maintenance.
     * Gate 1 short-circuits on the flow value — with the switch OFF (the
     * default) this method is one cheap flow read, nothing else.
     */
    suspend fun evaluateDueRules() {
        if (!behaviorEnabled.value) return
        val now = nowMs()
        val rules = try {
            ruleDao.candidateRules()
        } catch (e: CancellationException) {
            throw e // A cancelled pass is not a query failure
        } catch (e: Exception) {
            Timber.w(e, "Cognitive: rule query failed")
            return
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

    /** Present → speak → bookkeeping. A blank rendering is dropped. */
    private suspend fun fireRule(rule: HabitRuleEntity, now: Long) {
        val text = ProactivePresenter.render(rule, strings)
        if (text.isBlank()) return
        val spoken = speaker.speak(text)
        // The delivery seam returns false only when the machine was no
        // longer IDLE — the arbiter raced a user interaction and lost. The
        // attempt is still logged (the cooldown applies either way: the
        // user must not be nagged twice because a race ate one attempt).
        // Re-read under the write mutex: the arbiter's snapshot is stale by
        // delivery time; merging into the FRESH row keeps a concurrent
        // accept/reject increment from being clobbered by this full-row
        // write (same lost-update class as the reject path).
        ruleWriteMutex.withLock {
            val fresh = ruleDao.byId(rule.id) ?: rule
            ruleDao.update(fresh.copy(lastSuggestedAt = now, lastFiredAt = now))
        }
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
     * Non-FIRED rows are throttled to ≤1 per rule per hour: every
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
     * The behaviour ticker — evaluates due rules periodically
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
     * The reject half of the accept/reject loop. A SHORT explicit
     * refusal («нет», «не надо») right after a delivered suggestion counts
     * against the rule; 3 rejections mute it for 30 days, 6 retire it.
     * Long or affirmative replies are ignored — only unambiguous refusals
     * punish a rule.
     */
    override fun onFollowUpUtterance(utterance: String) {
        scope.launch(CoroutineName("cognitive-reject")) {
            try {
                if (!isExplicitReject(utterance)) return@launch
                // Lookup + read + write atomically under the rule write
                // mutex: two overlapping reject coroutines must each count
                // against the row the OTHER one has already incremented.
                ruleWriteMutex.withLock {
                    val fired = behaviorLogDao.latestFiredSince(nowMs() - REJECT_WINDOW_MS)
                        ?: return@withLock
                    val ruleId = fired.ruleId ?: return@withLock
                    val rule = ruleDao.byId(ruleId) ?: return@withLock
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
                }
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
     * Rendered summary block for the composer (presence-gated,
     * budget-truncated by the Summarizer; same fail-quiet contract as
     * [gather]).
     */
    override suspend fun gatherSummary(utterance: String?, isFollowUp: Boolean): String = try {
        withTimeout(GATHER_BUDGET_MS) { summarizer.renderForPrompt() }
    } catch (e: TimeoutCancellationException) {
        degradedCounterAtomic.incrementAndGet()
        ""
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        degradedCounterAtomic.incrementAndGet()
        Timber.e(e, "Cognitive: summary gather failed")
        ""
    }

    /**
     * The ConversationManager prune hook — capture the doomed range
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
    // Maintenance — the nightly alarm owns the schedule.
    // ------------------------------------------------------------------

    /**
     * Maintenance-step guard with the cancellation contract.
     * [runCatching] swallows [CancellationException] — a cancel
     * mid-maintenance then produced a storm of spurious "step failed"
     * ERROR lines (12 after the first) and the machine limped through the
     * remaining steps. Here CE ALWAYS propagates (the run aborts at once),
     * every genuine failure is logged (throwable attached; messages are
     * content-free — FileLoggingTree persists WARN+ to disk) and still
     * never skips the other steps.
     */
    private suspend inline fun maintenanceStep(
        name: String,
        crossinline block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Cognitive: %s failed", name)
        }
    }

    /**
     * Memory-freeze guard for the nightly pass. While the master
     * `memoryEnabled` switch is OFF, every step that MUTATES memory tables
     * (facts, vectors, entities, summaries) is skipped: stored facts, queued
     * rows, vectors and entities are left EXACTLY as they were and resume
     * when the switch flips back on. The manual «Забыть всё» wipe is the ONLY
     * path that deletes stored memory — maintenance-off must never silently
     * destroy it. The flag is read live from the reactive source, never
     * snapshotted at construction.
     *
     * Composes [maintenanceStep], so a skipped step is silent (not logged as
     * a failure) and a genuine failure in a running step still cannot skip
     * the rest of the pass.
     */
    private suspend fun memoryStep(
        name: String,
        block: suspend () -> Unit,
    ) {
        if (!memoryEnabled.value) return
        maintenanceStep(name, block)
    }

    suspend fun onMaintenance() {
        val now = nowMs()
        // Every step individually guarded so one failure cannot
        // skip the others. Memory steps additionally freeze while the master
        // switch is off (see [memoryStep]).
        // ---- Memory steps: frozen while `memoryEnabled` is OFF ----------
        memoryStep("decay step") { decayInactiveFacts(now) }
        memoryStep("compaction step") { compactOverCap() }
        memoryStep("superseded-retention step") { deleteExpiredSuperseded(now) }
        // ---- Behaviour steps: NOT memory, always run --------------------
        // ONE acquisition of `ruleWriteMutex` for all three habit passes,
        // so no session-lane reject/accept can land between them.
        maintenanceStep("habit maintenance") { habitDetector.nightly(now) }
        maintenanceStep("command-event retention") {
            eventDao.deleteOlderThan(now - COMMAND_EVENT_RETENTION_MS)
        }
        maintenanceStep("behavior-log retention") {
            behaviorLogDao.deleteOlderThan(now - BEHAVIOR_LOG_RETENTION_MS)
        }
        // ---- Memory steps (continued): frozen while OFF ------------------
        memoryStep("summary compaction") { compactSummaries() }
        memoryStep("summarization step") {
            val made = summarizer.runBacklogAndDigest()
            if (made > 0) Timber.i("Cognitive: %d summary batch(es) produced", made)
        }
        // ---- Semantic-recall steps: memory, frozen while OFF -------------
        memoryStep("vector maintenance") { vectorMaintenance() }
        memoryStep("entity derivation") { deriveEntities() }
        // The stamp is NOT memory: it must still advance while frozen, or
        // the staleness check re-triggers maintenance on every launch.
        maintenanceStep("maintenance stamp") {
            metaDao.putValue(MemoryMetaEntity.KEY_LAST_MAINTENANCE_AT, now.toString())
        }
    }

    /**
     * Keep the vector store consistent — GC vectors of facts that
     * left ACTIVE (superseded/forgotten/deleted), then top-up the facts
     * that appeared since the last build. Only the engine the user actually
     * built with is maintained; no engine recorded → no-op.
     */
    private suspend fun vectorMaintenance() {
        // Backstop: a cloud-off transition that happened while the app was
        // killed (or before this watch existed) must still be purged.
        if (!cloudEnabled.value) purgeCloudVectors()
        val engineId = metaDao.get(MemoryMetaEntity.KEY_VECTORS_ENGINE) ?: return
        val engine = when (engineId) {
            EmbeddingEngine.LOCAL_ID -> localEmbedder
            EmbeddingEngine.CLOUD_ID -> cloudEmbedder?.takeIf { cloudEnabled.value } ?: return
            else -> return
        }
        val activeIds = factDao.activeFacts().mapTo(HashSet()) { it.factId }
        val rows = vectorDao.forEngine(engineId)
        val stale = rows.filter { it.factId !in activeIds }.map { it.factId }
        if (stale.isNotEmpty()) vectorDao.deleteByFactIds(stale)
        val known = rows.mapTo(HashSet()) { it.factId }
        if (activeIds.any { it !in known }) {
            // Same cancellation contract as maintenanceStep — a
            // wedged top-up defers to tomorrow, a cancel propagates.
            try {
                vectorBackfill.runFor(engine)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Cognitive: vector top-up failed (resumes next night)")
            }
        }
    }

    /**
     * When the user turns `memory.cloudEnabled` OFF, every CLOUD vector
     * space must be deleted. The read gate in RecallPipeline already refuses to
     * *use* them, but the rows (embeddings of user facts) otherwise stay on
     * disk indefinitely. Deletes every engine space that is not the on-device
     * LOCAL engine — this also catches stale/renamed cloud ids. Content-free log.
     */
    private suspend fun purgeCloudVectors() {
        val engineIds = vectorDao.distinctEngineIds().filter { it != EmbeddingEngine.LOCAL_ID }
        if (engineIds.isEmpty()) return
        var removed = 0
        engineIds.forEach { id ->
            removed += vectorDao.countForEngine(id)
            vectorDao.deleteForEngine(id)
        }
        Timber.i("Cognitive: purged %d cloud vector space(s), %d row(s)", engineIds.size, removed)
    }

    /**
     * Purge CLOUD vector spaces the moment `memory.cloudEnabled` flips
     * false. The nightly `vectorMaintenance` backstop only runs overnight, so a
     * user who disables cloud expects the data gone now, not tomorrow.
     */
    fun startCloudPurgeWatch() {
        scope.launch(CoroutineName("cognitive-cloud-purge")) {
            cloudEnabled
                .drop(1) // skip StateFlow's initial emission; the backstop covers cold start
                .filter { !it }
                .collect {
                    try {
                        purgeCloudVectors()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "Cognitive: cloud vector purge failed")
                    }
                }
        }
    }

    /**
     * Rebuild the two-table entity index from ACTIVE RELATION facts
     * (idempotent full rebuild, atomic in one transaction — the recall
     * boost itself never reads these tables, so derivation lag cannot
     * corrupt recall).
     */
    private suspend fun deriveEntities() {
        val now = nowMs()
        val derived = EntityIndex.deriveEntities(factDao.activeFacts().map { it.toSnapshot() })
        if (derived.isEmpty() && entityDao.all().isEmpty()) return
        inTransaction {
            entityDao.wipeLinks()
            entityDao.wipeAll()
            derived.forEach { entity ->
                val id = entityDao.upsertByName(
                    entity.name,
                    entity.nameNormalized,
                    entity.kind.name,
                    now,
                )
                entity.factIds.forEach { factId ->
                    entityDao.insertLink(
                        FactEntityLinkEntity(factId, id, FactEntityLinkEntity.ROLE_OBJECT),
                    )
                }
            }
            entityDao.deleteOrphans()
        }
    }

    /**
     * Cap: DAILY summaries beyond [SUMMARY_ROW_CAP] → oldest dropped.
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
                } else if (fact.decayAnchorAt == 0L) {
                    // Confidence only — updatedAt (ranking recency) and
                    // lastConfirmedAt (usage proof) must NOT be refreshed by
                    // decay, or the decay clock would restart itself. Latch
                    // the immutable anchor (pre-decay confidence + updatedAt)
                    // in the SAME statement so the next pass recomputes from
                    // the anchor instead of re-decaying this result.
                    factDao.updateConfidenceAndAnchor(
                        fact.factId,
                        target,
                        fact.confidence,
                        fact.updatedAt,
                    )
                } else {
                    // Already anchored: recompute against the stored anchor
                    // (idempotent) without moving it.
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
    // Inspector support: observe, wipe, export.
    // ------------------------------------------------------------------

    fun observeFacts() = factDao.observeAll()

    fun observePendingCount() = queueDao.observePendingCount()

    /** Inspector single-item delete: marks FORGOTTEN (audit trail kept). */
    suspend fun forgetById(factId: String) {
        factDao.updateStatus(factId, FactStatus.FORGOTTEN.name, nowMs())
    }

    /** «Забыть всё»: ALL cognitive tables, never `messages`. */
    suspend fun wipeAll() = inTransaction {
        factDao.wipeAll()
        queueDao.wipeAll()
        metaDao.wipeAll()
        eventDao.wipeAll()
        ruleDao.wipeAll()
        behaviorLogDao.wipeAll()
        summaryDao.wipeAll()
        // The semantic stores are cognitive data too.
        vectorDao.wipeAll()
        entityDao.wipeAll()
    }

    /**
     * Export: every fact + meta, JSON — the serialization lives in
     * [FactExportService].
     */
    suspend fun exportJson(): JsonObject = factExport.exportJson()

    /** Registers the LLM-callable memory tools. */
    fun tools(): List<ToolContract> = MemoryToolsFactory(this).all()

    /**
     * The opt-in backfill entry point (Settings «Память»
     * → «Проанализировать прошлые диалоги»). Delegates to the worker; -1
     * means "already done" (the UI shows the done state).
     */
    suspend fun backfillRecent(limit: Int = ExtractionQueueWorker.BACKFILL_LIMIT): Int {
        // Frozen with the rest of memory: enqueuing rows the drain loop will
        // not process (it is gated on the same switch) would just accumulate
        // extraction work until — and unless — memory comes back on.
        if (!memoryEnabled.value) return 0
        val enqueued = worker.backfillRecent(limit)
        if (enqueued > 0) queueLoop.wake()
        return enqueued
    }

    companion object {
        /**
         * Hard prompt-block budget, used by [gatherSummary]. This cost
         * overlaps the caller's PRE-LLM prompt assembly
         * (buildPromptContext → composer render), NOT the server's
         * time-to-first-token — TTFT is measured after the request is on the
         * wire, so it can never "hide" local ranking work. The fact-gather
         * read path owns its own copy of this window plus the optional-phase
         * budget (see RecallPipeline).
         */
        const val GATHER_BUDGET_MS = 40L

        // ---- Behaviour + retention ----

        /** Recompute habits after every Nth recorded event. */
        const val HABIT_RECOMPUTE_EVERY = 10L

        /** A matching user command within 10 min = accept. */
        const val ACCEPT_WINDOW_MS = 10 * 60_000L

        /** 3 rejections → MUTED… */
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
            "нет",
            "не",
            "no",
            "неа",
            "стоп",
            "отстань",
            "не",
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

        /** Retention: command_events 90 days, behavior_log 30 days. */
        const val COMMAND_EVENT_RETENTION_MS = 90L * 24 * 60 * 60_000L
        const val BEHAVIOR_LOG_RETENTION_MS = 30L * 24 * 60 * 60_000L

        /** Cap: DAILY summary rows. */
        const val SUMMARY_ROW_CAP = 365

        /**
         * The confirmation grant is
         * valid only for the IMMEDIATELY-NEXT user turn (utterance-ordinal
         * gap == 1). Combined with [FORGET_PENDING_TTL_MS] this bounds the
         * window from above by both turn count and wall clock.
         */
        const val FORGET_MAX_UTTERANCE_GAP = 1

        /**
         * Wall-clock lifetime of a
         * confirmation grant. Whichever bound is tighter wins — a grant whose
         * confirming utterance is the immediately-next turn but arrives more
         * than five minutes later is refused and re-listed.
         */
        const val FORGET_PENDING_TTL_MS = 5 * 60_000L

        /** Compaction over-fetch buffer. */
        const val BUFFER = 10

        private val json = Json { prettyPrint = false }

        fun prettyJson(obj: JsonObject): String = json.encodeToString(JsonObject.serializer(), obj)
    }
}
