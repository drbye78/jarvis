package com.jarvis.assistant.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(e: MessageEntity): Long

    @Insert
    suspend fun insertAll(e: List<MessageEntity>): List<Long>

    /**
     * Atomic assistant + tool-results persistence (C2): either both sides of
     * the tool-call pair land in the table, or neither does. An interruption
     * mid-insert can no longer leave a dangling half-pair to poison history.
     */
    @Transaction
    suspend fun insertAssistantWithResults(assistant: MessageEntity, results: List<MessageEntity>) {
        insert(assistant)
        insertAll(results)
    }

    /** Ordering is by monotonically increasing id, never by timestamp —
     *  same-millisecond inserts would sort ambiguously. */
    @Query("SELECT * FROM messages ORDER BY id ASC")
    suspend fun all(): List<MessageEntity>

    /** COGNITIVE_PLAN 1.4: utterance lookup for the extraction queue worker. */
    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): MessageEntity?

    /** COGNITIVE_PLAN 1.9: backfill source — the newest user-role messages. */
    @Query("SELECT * FROM messages WHERE role = 'user' ORDER BY id DESC LIMIT :limit")
    suspend fun recentUserMessages(limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY id DESC LIMIT :n")
    suspend fun recentDesc(n: Int): List<MessageEntity>

    /** Live transcript for the UI. */
    @Query("SELECT * FROM messages ORDER BY id DESC LIMIT :n")
    fun recentDescLive(n: Int): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE id NOT IN (:ids)")
    suspend fun trimToIds(ids: Set<Long>)

    /** Deletes all messages except the most recent [maxMessages] by id. */
    @Query("DELETE FROM messages WHERE id NOT IN (SELECT id FROM messages ORDER BY id DESC LIMIT :maxMessages)")
    suspend fun deleteAllExceptRecent(maxMessages: Int)

    @Query("DELETE FROM messages")
    suspend fun clear()
}
