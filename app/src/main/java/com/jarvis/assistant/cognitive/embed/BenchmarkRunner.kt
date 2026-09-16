package com.jarvis.assistant.cognitive.embed

import com.jarvis.assistant.cognitive.data.MemoryMetaDao
import com.jarvis.assistant.cognitive.data.MemoryMetaEntity
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * §11/§12.4-3 (P4.4): the Settings-card retrieval benchmark orchestration,
 * extracted from the coordinator. Runs the retrieval benchmark over the
 * STATIC synthetic probe set ([RetrievalProbes] — never user facts, so the
 * cloud branch needs no privacy dialog), writes the winner to memory_meta,
 * and returns a structured result for the UI. The LOCAL branch always runs;
 * the CLOUD branch first probes entitlement (a 4xx is an honest "not
 * entitled", NOT a network failure to retry forever).
 *
 * Nothing here runs on a timer: called explicitly from the Settings card.
 *
 * P1-C (audit §9.2): the CLOUD branch is gated on BOTH a constructed
 * cloud engine AND the reactive `memory.cloudEnabled` switch. The provider
 * is read at every [run] — never snapshotted at graph build (plan
 * principle 5), mirroring [VectorBackfill]'s gate. With the flag OFF the
 * branch is skipped before the entitlement probe: zero HTTP calls of any
 * kind, and the outcome reports the honest `entitlement = "disabled"`
 * state (vs `null` = engine not constructed).
 */
class BenchmarkRunner(
    private val metaDao: MemoryMetaDao,
    private val localEmbedder: EmbeddingEngine,
    /** Null = the cloud embeddings branch is not constructed. */
    private val cloudEmbedder: EmbeddingEngine?,
    /**
     * Real cloud-egress gate (`memory.cloudEnabled`). NO default: every
     * construction site must state where the flag comes from — fail-open
     * defaults are how the §9.2 breach happened. Reactive read: the value
     * is taken at call time, e.g. `{ flow.value }`.
     */
    private val cloudEnabled: () -> Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    suspend fun run(): BenchmarkOutcome {
        val fixtures = RetrievalProbes.fixtures
        val localReport = EmbedderBenchmark.evaluate(
            fixtures,
            EmbedderBenchmark.EngineAdapter { texts -> localEmbedder.embed(texts) },
        )

        var cloudReport: EmbedderBenchmark.EngineReport? = null
        var entitlement: String? = null
        val cloud = cloudEmbedder
        if (cloud != null && !cloudEnabled()) {
            // §9.2 hard privacy gate: flag OFF → no entitlement probe, no
            // embed call — the whole cloud lane is skipped, not just the
            // HTTP failure-tolerant part. The probes are synthetic fixtures,
            // but they still egress, and the plan's privacy inventory binds
            // every cloud class behind this switch.
            entitlement = "disabled"
        } else if (cloud != null) {
            when (val probe = cloud.checkEntitlement()) {
                is EmbeddingEngine.Entitlement.Ok -> {
                    entitlement = "ok"
                    metaDao.putValue(MemoryMetaEntity.KEY_CLOUD_EMBED_ENTITLED, nowMs().toString())
                    cloudReport = try {
                        EmbedderBenchmark.evaluate(
                            fixtures,
                            EmbedderBenchmark.EngineAdapter { texts -> cloud.embed(texts) },
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "Cognitive: cloud benchmark failed after entitlement")
                        null
                    }
                }
                is EmbeddingEngine.Entitlement.Denied -> {
                    entitlement = "denied:${probe.code}"
                    metaDao.putValue(MemoryMetaEntity.KEY_CLOUD_EMBED_UNAVAILABLE, probe.code.toString())
                }
                is EmbeddingEngine.Entitlement.Transient -> entitlement = "transient"
            }
        }

        // §10.2: winner = the best SHIPPING branch (≥ 15 % over baseline).
        val winner = listOfNotNull(
            EmbeddingEngine.LOCAL_ID.takeIf { localReport.ships() } to localReport,
            cloudReport?.let { EmbeddingEngine.CLOUD_ID.takeIf { _ -> it.ships() } to it },
        ).maxByOrNull { (_, report) -> report.hybridRecallAt5 }?.first
        val stored = winner != null
        if (winner != null) {
            metaDao.putValue(MemoryMetaEntity.KEY_EMBEDDER_WINNER, winner)
        }
        Timber.i(
            "Cognitive: benchmark done — local=[%s] cloud=[%s] winner=%s",
            localReport,
            cloudReport?.toString() ?: "n/a",
            winner ?: "none",
        )
        return BenchmarkOutcome(
            localReport = localReport.toString(),
            cloudReport = cloudReport?.toString(),
            winner = winner,
            entitlement = entitlement,
            winnerStored = stored,
        )
    }

    data class BenchmarkOutcome(
        val localReport: String,
        val cloudReport: String?,
        val winner: String?,
        /**
         * Cloud-lane verdict for the UI: null = engine not constructed,
         * "disabled" = §9.2 switch off (skipped, zero calls), "ok" /
         * "denied:<code>" / "transient" = the probe ran.
         */
        val entitlement: String?,
        val winnerStored: Boolean,
    )
}
