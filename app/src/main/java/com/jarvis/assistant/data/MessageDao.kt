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

    /**
     * COGNITIVE_PLAN 2.5: the summarize-before-prune window — rows in
     * `(fromInclusive, toInclusive]` by id, oldest first. The summarizer
     * reads the doomed range BEFORE the prune delete lands (see
     * ConversationManager.beforePrune).
     */
    @Query("SELECT * FROM messages WHERE id > :fromInclusive AND id <= :toInclusive ORDER BY id ASC")
    suspend fun inRange(fromInclusive: Long, toInclusive: Long): List<MessageEntity>

    /** COGNITIVE_PLAN 2.3 gate 5: presence proxy — the newest row's time. */
    @Query("SELECT MAX(createdAt) FROM messages")
    suspend fun lastMessageAt(): Long?

    /** Live transcript for the UI. */
    @Query("SELECT * FROM messages ORDER BY id DESC LIMIT :n")
    fun recentDescLive(n: Int): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE id NOT IN (:ids)")
    suspend fun trimToIds(ids: Set<Long>)

    /** Deletes all messages except the most recent [maxMessages] by id. */
    @Query("DELETE FROM messages WHERE id NOT IN (SELECT id FROM messages ORDER BY id DESC LIMIT :maxMessages)")
    suspend fun deleteAllExceptRecent(maxMessages: Int)

    /**
     * COGNITIVE_PLAN 2.5: the newest id that will NOT survive retention
     * (the (keep+1)-th newest row's id) — the inclusive upper bound of the
     * doomed range for the summarize-before-prune hook. NULL = nothing to
     * prune (≤ keep rows).
     */
    @Query("SELECT id FROM messages ORDER BY id DESC LIMIT 1 OFFSET :keep")
    suspend fun firstDoomedId(keep: Int): Long?

    @Query("DELETE FROM messages")
    suspend fun clear()
}
