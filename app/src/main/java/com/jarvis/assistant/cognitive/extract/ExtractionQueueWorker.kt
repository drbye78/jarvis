package com.jarvis.assistant.cognitive.extract

import com.jarvis.assistant.cognitive.data.ExtractionQueueDao
import com.jarvis.assistant.cognitive.data.ExtractionQueueEntity
import com.jarvis.assistant.cognitive.data.MemoryMetaDao
import com.jarvis.assistant.cognitive.data.MemoryMetaEntity
import com.jarvis.assistant.cognitive.data.UserFactDao
import com.jarvis.assistant.cognitive.model.Ids
import com.jarvis.assistant.data.MessageDao
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.llm.LlmHttpException
import com.jarvis.assistant.llm.withLlmRetry
import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.Message
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.io.IOException

/**
 * COGNITIVE_PLAN §6.2: drains the durable extraction queue — batches up to
 * [BATCH_SIZE] PENDING messages into ONE `chatOnce` GigaChat call
 * (temperature 0), validates strictly, normalizes, writes.
 *
 * Failure model (plan §6.2/§9.3 — "queued, never dropped, never faked"):
 * - Cloud unavailable / 429 / 5xx: rows return to PENDING (batch released),
 *   the loop backs off [CLOUD_BACKOFF_MS]; attempts persist in the rows.
 * - A row whose attempts exceed [ExtractionQueueEntity.MAX_ATTEMPTS] is
 *   QUARANTINED before the call (poison protection, not blind retried).
 * - Parse/validation failure → the whole batch is QUARANTINED with a
 *   counter (the model output, not the transport, is broken).
 * - A message pruned before extraction: nothing extractable — DONE with
 *   zero facts, logged (expected churn of the retention trim, not an error).
 *
 * The worker is pure pipeline: loop ownership, settings reactivity and the
 * wake signal live in [com.jarvis.assistant.cognitive.CognitiveCoordinator],
 * which keeps every step here suspend + testable with DAO fakes.
 */
class ExtractionQueueWorker(
    private val queueDao: ExtractionQueueDao,
    private val factDao: UserFactDao,
    private val metaDao: MemoryMetaDao,
    private val messageDao: MessageDao,
    private val llm: LlmClient,
    private val normalizer: FactNormalizer = FactNormalizer(),
    private val parser: ExtractionParser = ExtractionParser(),
) {

    private val writer = MemoryWriter(factDao, normalizer)

    /** Counters flushed to memory_meta daily counters (plan principle 7). */
    var extractedCount: Long = 0
        private set
    var quarantinedCount: Long = 0
        private set
    var droppedCount: Long = 0
        private set

    /**
     * True when the most recent batch failed on the TRANSPORT (429/5xx/IO)
     * — the loop owner reads it to apply the 30 s cloud backoff (plan
     * §6.2), as opposed to a successful parse with zero facts.
     */
    @Volatile
    var lastBatchTransportFailed: Boolean = false
        private set

    /**
     * One drain step: claim a batch (or null when the queue is empty),
     * call the LLM once, validate, write. Returns the batch report.
     */
    suspend fun drainOnce(batchSize: Int = BATCH_SIZE): ExtractionBatchReport? {
        val batch = queueDao.pending(batchSize)
        if (batch.isEmpty()) return null
        return processBatch(batch)
    }

    suspend fun processBatch(batch: List<ExtractionQueueEntity>): ExtractionBatchReport {
        val batchId = Ids.uuidV7()
        val now = System.currentTimeMillis()

        // Quarantine poison rows BEFORE spending a call on the batch.
        val fresh = mutableListOf<ExtractionQueueEntity>()
        for (row in batch) {
            if (row.attempt >= ExtractionQueueEntity.MAX_ATTEMPTS) {
                queueDao.updateState(
                    row.messageId,
                    ExtractionQueueEntity.STATE_QUARANTINED,
                    row.attempt,
                    null,
                    now,
                )
                quarantinedCount++
            } else {
                fresh.add(row)
            }
        }
        if (fresh.isEmpty()) {
            return ExtractionBatchReport(batchId, batch.size, 0, 0, quarantined = true)
        }

        // Claim: RUNNING + attempt bumped (crash-safe bookkeeping).
        fresh.forEach {
            queueDao.updateState(
                it.messageId,
                ExtractionQueueEntity.STATE_RUNNING,
                it.attempt + 1,
                batchId,
                now,
            )
        }

        val pairs = fresh.mapNotNull { row ->
            messageDao.byId(row.messageId)?.content?.let { row.messageId to it }
        }
        if (pairs.isEmpty()) {
            // All source messages pruned before extraction — nothing to do.
            fresh.forEach {
                queueDao.updateState(
                    it.messageId,
                    ExtractionQueueEntity.STATE_DONE,
                    it.attempt + 1,
                    null,
                    System.currentTimeMillis(),
                )
            }
            Timber.i("Cognitive: extraction batch %s emptied by retention", batchId)
            return ExtractionBatchReport(batchId, batch.size, 0, 0, quarantined = false)
        }

        // ONE cloud call for the whole batch (plan §6.2), transient-retried.
        lastBatchTransportFailed = false
        val response = requestCompletion(batchId, pairs)
        if (response == null) {
            return ExtractionBatchReport(batchId, pairs.size, 0, 0, quarantined = false)
        }
        return finishBatch(batchId, fresh, pairs, response)
    }

    /** The cloud call with transient retry; null = transport failure (released). */
    private suspend fun requestCompletion(
        batchId: String,
        pairs: List<Pair<Long, String>>,
    ): String? = try {
        withLlmRetry(attempts = 2) { _ ->
            llm.chatOnce(
                ChatRequest(
                    messages = listOf(
                        Message.system(ExtractionContract.SYSTEM_PROMPT),
                        Message.user(ExtractionContract.buildUserContent(pairs)),
                    ),
                    // No tool definitions: extraction is a plain completion.
                    tools = emptyList(),
                    temperature = ExtractionContract.TEMPERATURE,
                    maxTokens = ExtractionContract.MAX_TOKENS,
                ),
            )
        }
    } catch (e: CancellationException) {
        throw e // A8: shutdown/barge-in propagates; rows are recovered at startup
    } catch (e: LlmHttpException) {
        Timber.w("Cognitive: extraction batch %s failed (HTTP %d)", batchId, e.code)
        markTransportFailure(batchId)
        null
    } catch (e: IOException) {
        Timber.w(e, "Cognitive: extraction batch %s unreachable", batchId)
        markTransportFailure(batchId)
        null
    }

    private suspend fun markTransportFailure(batchId: String) {
        lastBatchTransportFailed = true
        releaseBatch(batchId)
    }

    /** Strict validation + write + DONE bookkeeping for a completed call. */
    private suspend fun finishBatch(
        batchId: String,
        fresh: List<ExtractionQueueEntity>,
        pairs: List<Pair<Long, String>>,
        response: String,
    ): ExtractionBatchReport {
        val result = parser.parse(response, pairs)
        return when (result) {
            is ExtractionParser.Result.ParseError -> {
                Timber.w(
                    "Cognitive: extraction batch %s unparseable (%s) — quarantined",
                    batchId,
                    result.detail,
                )
                fresh.forEach {
                    queueDao.updateState(
                        it.messageId,
                        ExtractionQueueEntity.STATE_QUARANTINED,
                        it.attempt + 1,
                        null,
                        System.currentTimeMillis(),
                    )
                    quarantinedCount++
                }
                ExtractionBatchReport(batchId, pairs.size, 0, 0, quarantined = true)
            }

            is ExtractionParser.Result.Ok -> {
                val applied = writer.writeAll(result.facts)
                extractedCount += applied.size
                droppedCount += result.droppedCount
                fresh.forEach {
                    queueDao.updateState(
                        it.messageId,
                        ExtractionQueueEntity.STATE_DONE,
                        it.attempt + 1,
                        null,
                        System.currentTimeMillis(),
                    )
                }
                Timber.i(
                    "Cognitive: batch %s → %d fact(s) from %d message(s) (%d dropped)",
                    batchId,
                    applied.size,
                    pairs.size,
                    result.droppedCount,
                )
                ExtractionBatchReport(batchId, pairs.size, applied.size, result.droppedCount, quarantined = false)
            }
        }
    }

    private suspend fun releaseBatch(batchId: String) {
        queueDao.releaseBatch(batchId, System.currentTimeMillis())
        // Release flips the whole batch back to PENDING — attempts were
        // already bumped at claim time, so poison rows quarantine on their
        // next pass through [processBatch].
    }

    /**
     * COGNITIVE_PLAN 1.9: opt-in backfill — enqueue the still-retained
     * user messages for extraction, one-shot via the `extractionBackfillDone`
     * meta flag. Returns the number of NEWLY enqueued rows.
     */
    suspend fun backfillRecent(
        limit: Int = BACKFILL_LIMIT,
        force: Boolean = false,
    ): Int {
        if (!force && metaDao.get(MemoryMetaEntity.KEY_EXTRACTION_BACKFILL_DONE) != null) {
            return -1 // already done (UI shows the "done" state)
        }
        val candidates = messageDao.recentUserMessages(limit)
        var enqueued = 0
        val now = System.currentTimeMillis()
        for (message in candidates) {
            val inserted = queueDao.enqueue(
                ExtractionQueueEntity(
                    messageId = message.id,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            if (inserted != -1L) enqueued++
        }
        metaDao.putValue(MemoryMetaEntity.KEY_EXTRACTION_BACKFILL_DONE, now.toString())
        Timber.i("Cognitive: backfill enqueued %d/%d message(s)", enqueued, candidates.size)
        return enqueued
    }

    companion object {
        /** Plan §6.2: 3 turns per cloud call. */
        const val BATCH_SIZE = 3

        /** Plan §1.9: backfill window. */
        const val BACKFILL_LIMIT = 200

        /** Plan §4: queue idle backoff after a 429/5xx/IO failure. */
        const val CLOUD_BACKOFF_MS = 30_000L
    }
}
