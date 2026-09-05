package com.jarvis.assistant.cognitive.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * COGNITIVE_PLAN §5: storage contract for user facts. All queries are
 * status-scoped — FORGOTTEN/ARCHIVED/QUARANTINED rows are audit trail and
 * never resurface in prompts (plan principle 1 & 7).
 *
 * The interface is intentionally plain (no Android types beyond Room
 * annotations) so JVM tests can fake it for the queue worker and
 * coordinator tests.
 */
@Dao
interface UserFactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fact: UserFactEntity): Long

    @Update
    suspend fun update(fact: UserFactEntity)

    @Transaction
    suspend fun upsertAll(facts: List<UserFactEntity>) {
        facts.forEach { insert(it) }
    }

    @Query("SELECT * FROM user_facts WHERE factId = :factId LIMIT 1")
    suspend fun byFactId(factId: String): UserFactEntity?

    @Query("SELECT * FROM user_facts WHERE status = 'ACTIVE' ORDER BY updatedAt DESC")
    suspend fun activeFacts(): List<UserFactEntity>

    @Query("SELECT * FROM user_facts ORDER BY createdAt ASC")
    suspend fun allFacts(): List<UserFactEntity>

    /** Live feed for the Memory Inspector (plan §4: transparency is a feature). */
    @Query("SELECT * FROM user_facts ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<UserFactEntity>>

    @Query("SELECT COUNT(*) FROM user_facts WHERE status = 'ACTIVE'")
    suspend fun activeCount(): Int

    /**
     * Exact-identity dedup lookup (plan §6.3 rule 1): same normalized
     * (subject, predicate, value). The paraphrase branch is handled in
     * FactNormalizer over the ACTIVE set.
     */
    @Query(
        "SELECT * FROM user_facts WHERE status = 'ACTIVE' " +
            "AND subject = :subject AND predicate = :predicate AND valueNormalized = :valueNorm " +
            "LIMIT 1",
    )
    suspend fun findExact(
        subject: String,
        predicate: String,
        valueNorm: String,
    ): UserFactEntity?

    @Query(
        "UPDATE user_facts SET confidence = :confidence, lastConfirmedAt = :confirmedAt, " +
            "updatedAt = :confirmedAt WHERE factId = :factId",
    )
    suspend fun confirmFact(factId: String, confidence: Float, confirmedAt: Long)

    /**
     * Maintenance decay: confidence ONLY — never touches updatedAt /
     * lastConfirmedAt, or the decay clock would restart itself.
     */
    @Query("UPDATE user_facts SET confidence = :confidence WHERE factId = :factId")
    suspend fun updateConfidence(factId: String, confidence: Float)

    @Query("UPDATE user_facts SET status = :status, updatedAt = :now WHERE factId = :factId")
    suspend fun updateStatus(factId: String, status: String, now: Long)

    @Query("UPDATE user_facts SET contested = :contested, updatedAt = :now WHERE factId = :factId")
    suspend fun setContested(factId: String, contested: Boolean, now: Long)

    /**
     * Write-behind recall statistics (plan §7.2): batched, never on the hot
     * path — the honest "this memory is actually used" ranking signal.
     */
    @Query(
        "UPDATE user_facts SET recallCount = recallCount + 1, lastRecalledAt = :now " +
            "WHERE factId IN (:factIds)",
    )
    suspend fun recordRecalls(factIds: List<String>, now: Long)

    /** Lexical recall: pre-tokenized prefix MATCH over the FTS index. */
    @Query(
        "SELECT user_facts.* FROM user_facts " +
            "JOIN fact_fts ON user_facts.rowId = fact_fts.rowid " +
            "WHERE user_facts.status = 'ACTIVE' AND fact_fts MATCH :matchQuery " +
            "ORDER BY user_facts.updatedAt DESC LIMIT :limit",
    )
    suspend fun searchActive(matchQuery: String, limit: Int = 20): List<UserFactEntity>

    /** Compaction (plan §5): ACTIVE facts beyond the cap, lowest score first. */
    @Query(
        "SELECT * FROM user_facts WHERE status = 'ACTIVE' " +
            "ORDER BY confidence ASC, updatedAt ASC LIMIT :limit",
    )
    suspend fun weakestActive(limit: Int): List<UserFactEntity>

    /** Supersession chains older than the retention window (plan §5). */
    @Query(
        "SELECT * FROM user_facts WHERE status = 'SUPERSEDED' AND updatedAt < :cutoff",
    )
    suspend fun supersededBefore(cutoff: Long): List<UserFactEntity>

    @Query("DELETE FROM user_facts WHERE factId IN (:factIds)")
    suspend fun deleteByFactIds(factIds: List<String>)

    /** «Забыть всё» (plan §9.2): one wipe, inspector-visible, not touching messages. */
    @Query("DELETE FROM user_facts")
    suspend fun wipeAll()
}
