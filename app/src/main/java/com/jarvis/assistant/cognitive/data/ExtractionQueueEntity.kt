package com.jarvis.assistant.cognitive.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * COGNITIVE_PLAN §5, migration v3→v4: the durable extraction work queue.
 *
 * `messageId` is the PRIMARY KEY: enqueueing is `INSERT OR IGNORE`, so work
 * is exactly-once per message even if the ingest hook fires twice (retry,
 * process death between insert and trim). Survives process death; the
 * worker polls PENDING ordered by `messageId`.
 */
@Entity(tableName = "extraction_queue")
data class ExtractionQueueEntity(
    @PrimaryKey val messageId: Long,
    /** 0 on first enqueue; the worker bumps before each cloud attempt. */
    val attempt: Int = 0,
    /** PENDING → RUNNING → DONE | QUARANTINED (plan §5). */
    val state: String = STATE_PENDING,
    /** Set while a batch's cloud call is in flight (crash diagnostics). */
    val batchId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_RUNNING = "RUNNING"
        const val STATE_DONE = "DONE"
        const val STATE_QUARANTINED = "QUARANTINED"

        /** Attempts before a poison row is parked (plan §6.2: never blind-retried). */
        const val MAX_ATTEMPTS = 3
    }
}

/**
 * COGNITIVE_PLAN §5, migration v3→v4: memory subsystem bookkeeping —
 * schema revision, summarization cursor, backfill flag, daily counters.
 * Key/value with JSON-encoded values where structured.
 */
@Entity(tableName = "memory_meta")
data class MemoryMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
) {
    companion object {
        const val KEY_SCHEMA_REV = "schemaRev"
        const val KEY_LAST_SUMMARIZED_MESSAGE_ID = "lastSummarizedMessageId"
        const val KEY_LAST_MAINTENANCE_AT = "lastMaintenanceAt"
        const val KEY_DAILY_COUNTERS_JSON = "dailyCountersJson"
        const val KEY_EXTRACTION_BACKFILL_DONE = "extractionBackfillDone"
    }
}
