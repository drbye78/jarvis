package com.jarvis.assistant.cognitive.embed

import com.jarvis.assistant.cognitive.data.FactVectorDao
import com.jarvis.assistant.cognitive.data.FactVectorEntity
import com.jarvis.assistant.cognitive.data.MemoryMetaDao
import com.jarvis.assistant.cognitive.data.MemoryMetaEntity
import com.jarvis.assistant.cognitive.data.UserFactDao
import com.jarvis.assistant.cognitive.recall.SearchTokenizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * COGNITIVE_PLAN Phase 3 (§11 "v6 migration + backfill"; §12.4-4: opt-in):
 * builds one vector per ACTIVE fact in ONE engine space, chunked,
 * resumable, observable.
 *
 * Opt-in semantics: nothing here runs on its own — the Settings action
 * calls the coordinator, which resolves the ACTIVE engine from the
 * selector and launches [runFor] on the cognitive scope. Cloud backfill
 * additionally requires `memory.cloudEnabled` and is preceded by a
 * privacy dialog (fact values egress to GigaChat — §9.2 truth table).
 *
 * Resumability: built rows persist; a crashed/interrupted run simply
 * continues where it stopped (the missing-set is recomputed per run).
 * A transient cloud failure aborts the current run with an error in
 * [Progress.error] — pressing the action again resumes; nothing is faked.
 */
class VectorBackfill(
    private val factDao: UserFactDao,
    private val vectorDao: FactVectorDao,
    private val metaDao: MemoryMetaDao,
    private val cloudEnabled: () -> Boolean,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    data class Progress(
        val engineId: String,
        val done: Int,
        val total: Int,
        val running: Boolean,
        val error: String?,
    )

    private val _progress = MutableStateFlow<Progress?>(null)

    /** Last/current backfill run state for the Settings card. */
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    /**
     * Build (or top-up) vectors for all ACTIVE facts in [engine]'s space.
     * Returns the number of vectors written; -1 when a run is already in
     * progress. CLOUD engines must be entitled AND cloud-enabled — the
     * caller enforces the dialog; this method enforces the switch.
     */
    suspend fun runFor(engine: EmbeddingEngine): Int {
        check(_progress.value?.running != true) { "backfill already running" }
        if (engine.kind == EmbeddingEngine.Kind.CLOUD) {
            check(cloudEnabled()) { "cloud egress is disabled (memory.cloudEnabled)" }
        }
        val facts = factDao.activeFacts()
        val existing = vectorDao.factIdsForEngine(engine.engineId).toHashSet()
        val missing = facts.filter { it.factId !in existing }
        _progress.value = Progress(engine.engineId, 0, missing.size, running = true, error = null)
        if (missing.isEmpty()) {
            metaDao.putValue(MemoryMetaEntity.KEY_VECTORS_ENGINE, engine.engineId)
            _progress.value = Progress(engine.engineId, 0, 0, running = false, error = null)
            return 0
        }

        var done = 0
        try {
            val chunk = if (engine.kind == EmbeddingEngine.Kind.LOCAL) LOCAL_CHUNK else CLOUD_CHUNK
            var index = 0
            while (index < missing.size) {
                val slice = missing.subList(index, minOf(index + chunk, missing.size))
                val texts = slice.map {
                    SearchTokenizer.indexText(it.subject, it.value, it.category)
                }
                val vectors = engine.embed(texts)
                inTransaction {
                    slice.forEachIndexed { j, fact ->
                        vectorDao.upsert(
                            FactVectorEntity(
                                factId = fact.factId,
                                engineId = engine.engineId,
                                dim = engine.dim,
                                vec = VectorMath.floatsToBytes(vectors[j]),
                                createdAt = nowMs(),
                            ),
                        )
                    }
                }
                index += chunk
                done += slice.size
                _progress.value = Progress(engine.engineId, done, missing.size, running = true, error = null)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            _progress.value = Progress(engine.engineId, done, missing.size, running = false, error = "cancelled")
            throw e
        } catch (e: Exception) {
            _progress.value = Progress(engine.engineId, done, missing.size, running = false, error = e.message)
            return done
        }
        metaDao.putValue(MemoryMetaEntity.KEY_VECTORS_ENGINE, engine.engineId)
        _progress.value = Progress(engine.engineId, done, missing.size, running = false, error = null)
        return done
    }

    companion object {
        /** Local embedding is ~microseconds — big chunks, one transaction. */
        const val LOCAL_CHUNK = 128

        /** Cloud chunks stay small (16 values per HTTP call, rate-limit kind). */
        const val CLOUD_CHUNK = 16
    }
}
