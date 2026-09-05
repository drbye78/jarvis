package com.jarvis.assistant.cognitive.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * COGNITIVE_PLAN §5/§6: extraction work queue + memory bookkeeping DAOs.
 * Plain interfaces — JVM-testable via fakes (the queue worker and the
 * coordinator tests never touch a real database).
 */
@Dao
interface ExtractionQueueDao {

    /** Exactly-once per message (PK conflict → ignore; the ingest hook may re-fire). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(row: ExtractionQueueEntity): Long

    @Query("SELECT * FROM extraction_queue WHERE state = 'PENDING' ORDER BY messageId ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<ExtractionQueueEntity>

    @Query("SELECT * FROM extraction_queue WHERE state = 'RUNNING' ORDER BY messageId ASC")
    suspend fun running(): List<ExtractionQueueEntity>

    @Query("SELECT * FROM extraction_queue WHERE messageId = :messageId LIMIT 1")
    suspend fun byMessageId(messageId: Long): ExtractionQueueEntity?

    @Query("SELECT COUNT(*) FROM extraction_queue WHERE state = 'PENDING'")
    suspend fun pendingCount(): Int

    /** Live count for the inspector header. */
    @Query("SELECT COUNT(*) FROM extraction_queue WHERE state = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query(
        "UPDATE extraction_queue SET state = :state, attempt = :attempt, batchId = :batchId, " +
            "updatedAt = :now WHERE messageId = :messageId",
    )
    suspend fun updateState(messageId: Long, state: String, attempt: Int, batchId: String?, now: Long)

    /** Batch bookkeeping: rows claimed by one cloud call go back to PENDING. */
    @Query(
        "UPDATE extraction_queue SET state = 'PENDING', batchId = NULL, updatedAt = :now " +
            "WHERE batchId = :batchId",
    )
    suspend fun releaseBatch(batchId: String, now: Long)

    @Query("DELETE FROM extraction_queue WHERE messageId = :messageId")
    suspend fun delete(messageId: Long)

    /** «Забыть всё»: cancel queued work with the facts (plan §9.2). */
    @Query("DELETE FROM extraction_queue")
    suspend fun wipeAll()
}

@Dao
interface MemoryMetaDao {

    @Query("SELECT value FROM memory_meta WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: MemoryMetaEntity)

    suspend fun putValue(key: String, value: String) {
        put(MemoryMetaEntity(key, value))
    }

    @Query("SELECT * FROM memory_meta")
    suspend fun all(): List<MemoryMetaEntity>

    @Query("DELETE FROM memory_meta")
    suspend fun wipeAll()
}
