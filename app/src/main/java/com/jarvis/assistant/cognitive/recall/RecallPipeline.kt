package com.jarvis.assistant.cognitive.recall

import com.jarvis.assistant.cognitive.CognitiveDeps
import com.jarvis.assistant.cognitive.data.MemoryMetaEntity
import com.jarvis.assistant.cognitive.embed.CloudEntitlement
import com.jarvis.assistant.cognitive.embed.EmbedderBenchmark
import com.jarvis.assistant.cognitive.embed.EmbedderChoice
import com.jarvis.assistant.cognitive.embed.EmbedderSelection
import com.jarvis.assistant.cognitive.embed.EmbeddingEngine
import com.jarvis.assistant.cognitive.embed.HybridRecall
import com.jarvis.assistant.cognitive.embed.VectorMath
import com.jarvis.assistant.cognitive.entity.EntityIndex
import com.jarvis.assistant.cognitive.model.FactSnapshot
import com.jarvis.assistant.cognitive.model.FactStatus
import com.jarvis.assistant.cognitive.prompt.MemorySectionData
import com.jarvis.assistant.cognitive.prompt.MemorySectionRenderer
import com.jarvis.assistant.cognitive.prompt.renderMemorySection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/**
 * The §7 read path (gather) and the §11 semantic-recall ranking, extracted
 * from `CognitiveCoordinator` as one of exactly two approved seams
 * (REMEDIATION_PLAN Phase 4: `recall/RecallPipeline` — the coordinator is NOT
 * decomposed further). Pure code motion: the KDoc, budgets, degradation
 * policy and byte output are unchanged.
 *
 * Everything here is a *pure ranking/rendering* concern. It owns no
 * scheduling loop, no mutex and no lifecycle: the coordinator constructs one
 * instance at graph build and delegates.
 */
class RecallPipeline(
    deps: CognitiveDeps,
    private val scope: CoroutineScope,
    /** Plan §7.2 diagnostics hook: the coordinator owns the observable counter. */
    private val onDegraded: () -> Unit,
) {

    private val factDao = deps.factDao
    private val vectorDao = deps.vectorDao
    private val metaDao = deps.metaDao
    private val memoryEnabled = deps.memoryEnabled
    private val sensitiveVisible = deps.sensitiveVisible
    private val cloudEnabled = deps.cloudEnabled
    private val embedderChoice = deps.embedderChoice
    private val localEmbedder = deps.localEmbedder
    private val cloudEmbedder = deps.cloudEmbedder
    private val localShipsByCiGate = deps.localShipsByCiGate
    private val strings = deps.strings
    private val nowMs = deps.nowMs
    private val cpuDispatcher = deps.cpuDispatcher
    private val elapsedNow = deps.elapsedNow

    private val ranker = FactRanker(nowMs)

    // ------------------------------------------------------------------
    // READ PATH (§7): gather ≤ 40 ms, never blocks the turn on failure.
    // ------------------------------------------------------------------

    /**
     * F11: the pure-CPU phases of gather run on the injected CPU dispatcher
     * (`CognitiveDeps.cpuDispatcher`), never on the caller's dispatcher
     * (production caller = the session's `Dispatchers.IO` scope). Wrapping is
     * unconditional-but-cheap: when the caller already is on that dispatcher,
     * `withContext` is a no-op.
     */
    private suspend fun <T> onCpu(block: () -> T): T = withContext(cpuDispatcher) { block() }

    suspend fun gather(utterance: String?): String = try {
        withTimeout(GATHER_BUDGET_MS) { gatherInternal(utterance) }
    } catch (e: TimeoutCancellationException) {
        onDegraded()
        Timber.w("Cognitive: gather exceeded %d ms — rendering without memory", GATHER_BUDGET_MS)
        ""
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onDegraded()
        Timber.e(e, "Cognitive: gather failed — rendering without memory")
        ""
    }

    private suspend fun gatherInternal(utterance: String?): String {
        val startedAt = elapsedNow()
        if (!memoryEnabled.value) return ""
        val active = factDao.activeFacts()
        if (active.isEmpty()) return ""

        // Touches every active fact (filter + snapshot): CPU, and the input
        // pool can be large on a long-lived install.
        val visible = onCpu {
            active.asSequence()
                .filter { it.status == FactStatus.ACTIVE.name }
                .filter { sensitiveVisible.value || !it.sensitive }
                .map { it.toSnapshot() }
                .toList()
        }
        if (visible.isEmpty()) return ""

        // Lexical union: FTS hits get the plan's +0.3 boost (§7.2).
        val ftsHits = lexicalHits(utterance)
        // Mandatory phase: the lexical rank is the whole point of the read
        // path, so it always runs even if the budget is already tight.
        var ranked = onCpu {
            boostedList(
                ranker.topFacts(visible, utterance, limit = GATHER_POOL, maxPerCategory = SPREAD_POOL),
                ftsHits,
                markLexical = true,
            )
        }
        // §11 recall integration: a «кто мой начальник?» question maps onto
        // the relation-predicate vocabulary and boosts the answering facts
        // (same flat boost as an FTS hit — deterministic, no entity-table
        // read on the hot path).
        //
        // F11 phase budget: the relation and vector lanes are OPTIONAL boosts.
        // If the mandatory lexical rank already spent most of the window we
        // stop adding them and render what we have — strictly better than
        // letting the outer `withTimeout` discard the whole block.
        if (elapsedSince(startedAt) < GATHER_OPTIONAL_PHASE_BUDGET_MS) {
            ranked = onCpu { boostedList(ranked, EntityIndex.relationBoostFactIds(visible, utterance)) }
        }
        if (elapsedSince(startedAt) < GATHER_OPTIONAL_PHASE_BUDGET_MS) {
            // §11 vector channel — LOCAL engine ONLY inside the gather budget
            // (see [applyVectorChannel]).
            ranked = applyVectorChannel(ranked, utterance, visible)
        }

        val finalRanked = ranked.take(RECALL_LIMIT)
        if (finalRanked.isEmpty()) return ""
        writeBehindRecallStats(finalRanked)

        val data: MemorySectionData = renderMemorySection(finalRanked, degraded = false, strings)
        val rendered = onCpu { MemorySectionRenderer.render(data, strings) }
        // F11: content-free diagnostic (duration + pool sizes only — never an
        // utterance, fact id or fact value; see SpeechContentLoggingTest).
        Timber.d(
            "Cognitive: gather %d ms active=%d pool=%d emitted=%d",
            elapsedSince(startedAt) / 1_000_000,
            active.size,
            visible.size,
            finalRanked.size,
        )
        return rendered
    }

    /** F11: elapsed monotonic milliseconds since [startedAt]. */
    private fun elapsedSince(startedAt: Long): Long = (elapsedNow() - startedAt) / 1_000_000

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

    /**
     * Flat +0.3 boost (the plan's lexical-hit weight, §7.2) for the given
     * fact ids, then the deterministic re-sort. Shared by the FTS lane and
     * the §11 relation-question lane.
     */
    private fun boostedList(
        list: List<ScoredFact>,
        ids: Set<String>,
        markLexical: Boolean = false,
    ): List<ScoredFact> {
        if (ids.isEmpty()) return list
        return list
            .map { scored ->
                if (scored.fact.factId in ids) {
                    scored.copy(
                        score = scored.score + FactRanker.LEXICAL_HIT_BOOST,
                        lexicalHit = scored.lexicalHit || markLexical,
                    )
                } else {
                    scored
                }
            }
            .sortedWith(compareByDescending<ScoredFact> { it.score }.thenBy { it.fact.factId })
    }

    /**
     * §11 vector channel — LOCAL engine ONLY inside the gather budget: a
     * cloud round-trip can never fit 40 ms (the CLOUD engine serves
     * recall_facts and the benchmark instead; documented deviation — F11:
     * that is a real cost on this path, not a TTFT-hidden one). Null engine
     * (selector OFF / unproven AUTO) → the list passes through untouched,
     * byte-path identical to Phase 2.
     */
    private suspend fun applyVectorChannel(
        ranked: List<ScoredFact>,
        utterance: String?,
        visible: List<FactSnapshot>,
    ): List<ScoredFact> {
        if (utterance == null) return ranked
        val engine = resolveActiveEngine() ?: return ranked
        if (engine.kind != EmbeddingEngine.Kind.LOCAL) return ranked
        val vectorHits = vectorTopFacts(utterance, engine, visible.mapTo(HashSet()) { it.factId })
            ?: return ranked
        val byId = ranked.associateBy { it.fact.factId }
        // F11: RRF fusion + reordering is pure CPU over the candidate lists.
        return onCpu {
            HybridRecall.rrfFuse(scoredFactIds(ranked), vectorHits).mapNotNull { byId[it] }
        }.ifEmpty { ranked }
    }

    // ------------------------------------------------------------------
    // SEMANTIC RECALL (§11): engine resolution + the cosine channel.
    // ------------------------------------------------------------------

    /**
     * §12.4-3: which engine is ACTIVE right now. Reads the selector flow
     * AND the benchmark verdict (memory_meta) — both change live, so a
     * Settings toggle or a fresh benchmark result applies from the very
     * next turn (plan principle 5; the live-toggle regression test pins
     * this).
     */
    suspend fun resolveActiveEngine(): EmbeddingEngine? {
        val choice = EmbedderChoice.fromPref(embedderChoice.value)
        val winner = readMeta(MemoryMetaEntity.KEY_EMBEDDER_WINNER)
        // F5: "usable" is the RECORDED verdict, not merely "an object exists".
        // A Denied probe (or a denied account that never re-probed) must keep
        // facts on-device even though `cloudEmbedder` was constructed.
        val usable = CloudEntitlement.isUsable(
            engineConstructed = cloudEmbedder != null,
            entitledStamp = readMeta(MemoryMetaEntity.KEY_CLOUD_EMBED_ENTITLED),
            unavailableStamp = readMeta(MemoryMetaEntity.KEY_CLOUD_EMBED_UNAVAILABLE),
        )
        val engineId = EmbedderSelection.resolve(
            choice = choice,
            benchmarkWinner = winner,
            cloudUsable = usable,
            localShipsByCiGate = localShipsByCiGate,
        )
        return engineById(engineId)
    }

    /** A meta read that degrades to "not recorded" instead of failing a turn. */
    private suspend fun readMeta(key: String): String? = try {
        metaDao.get(key)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null // no verdict recorded — the selector falls back to its fail-closed branch
    }

    fun engineById(engineId: String?): EmbeddingEngine? = when (engineId) {
        EmbeddingEngine.LOCAL_ID -> localEmbedder
        EmbeddingEngine.CLOUD_ID -> cloudEmbedder
        else -> null
    }

    /**
     * Cosine channel over stored vectors, filtered to the visible fact
     * set. Returns null on any failure or absence — the lexical lane NEVER
     * degrades because the vector lane hiccupped (§7.2 fail-quiet). CLOUD
     * calls are additionally gated by the §9.2 egress switch.
     */
    private suspend fun vectorTopFacts(
        utterance: String,
        engine: EmbeddingEngine,
        allowedFactIds: Set<String>,
    ): List<String>? = try {
        if (engine.kind == EmbeddingEngine.Kind.CLOUD && !cloudEnabled.value) {
            null
        } else {
            val rows = vectorDao.forEngine(engine.engineId)
            val candidates = onCpu { rows.filter { it.factId in allowedFactIds && it.dim == engine.dim } }
            if (candidates.isEmpty()) {
                null
            } else {
                val queryVec = engine.embed(listOf(utterance)).first()
                // F11: byte→float decoding of every candidate + the cosine
                // sweep is the one genuinely heavy CPU block on the read path.
                onCpu {
                    VectorMath
                        .topK(
                            queryVec,
                            candidates.map { it.factId to VectorMath.bytesToFloats(it.vec) },
                            k = EmbedderBenchmark.VECTOR_CHANNEL_K,
                        )
                        .ifEmpty { null }
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Cognitive: vector channel failed — continuing lexical-only")
        null
    }

    /** Write-behind recall statistics (plan §7.2) — never on the hot path. */
    fun writeBehindRecallStats(ranked: List<ScoredFact>) {
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

    private fun scoredFactIds(scoredList: List<ScoredFact>): List<String> =
        scoredList.map { it.fact.factId }

    /**
     * The `recall_facts(query)` ranking: lexical lane → §11 relation
     * boost → §11 vector channel. BOTH engines are allowed here: the tool
     * path already tolerates tool latency (weather/music do I/O); CLOUD
     * embeds the query via GigaChat, gated by the §9.2 egress switch.
     */
    suspend fun rankedForQuery(
        active: List<FactSnapshot>,
        query: String,
    ): List<ScoredFact> {
        var scoredList = boostedList(
            ranker.topFacts(active, query),
            lexicalHits(query),
            markLexical = true,
        )
        scoredList = boostedList(scoredList, EntityIndex.relationBoostFactIds(active, query))
        val engine = resolveActiveEngine()
        if (engine != null) {
            val vectorHits = vectorTopFacts(query, engine, active.mapTo(HashSet()) { it.factId })
            if (!vectorHits.isNullOrEmpty()) {
                val byId = scoredList.associateBy { it.fact.factId }
                val fused = HybridRecall
                    .rrfFuse(scoredFactIds(scoredList), vectorHits)
                    .mapNotNull { byId[it] }
                if (fused.isNotEmpty()) scoredList = fused
            }
        }
        return scoredList
    }

    companion object {
        /**
         * Plan §7.2: hard gather budget. F11 correction: this cost overlaps
         * the caller's PRE-LLM prompt assembly (buildPromptContext → composer
         * render), NOT the server's time-to-first-token — TTFT is measured
         * after the request is on the wire, so it can never "hide" local
         * ranking work. Exceeding this window discards the whole block (the
         * phase budget below degrades gracefully first).
         */
        const val GATHER_BUDGET_MS = 40L

        /**
         * F11: while the mandatory lexical rank is done, the two OPTIONAL
         * boost lanes (relation vocabulary, local vector channel) are skipped
         * once the read path has already consumed this much of the window —
         * the block renders what it has instead of being discarded whole by
         * [GATHER_BUDGET_MS].
         */
        const val GATHER_OPTIONAL_PHASE_BUDGET_MS = 20L

        const val GATHER_POOL = 8
        const val SPREAD_POOL = 3
        const val RECALL_LIMIT = 5
    }
}
