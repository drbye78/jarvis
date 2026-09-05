package com.jarvis.assistant.cognitive.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

/**
 * COGNITIVE_PLAN Phase 3: vector store + entity-index DAOs. Plain
 * interfaces — JVM-testable via fakes exactly like the memory-core and
 * behaviour DAOs.
 */
@Dao
interface FactVectorDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: FactVectorEntity)

    /** All vectors of one engine space — cosine never mixes engines. */
    @Query("SELECT * FROM fact_vectors WHERE engineId = :engineId")
    suspend fun forEngine(engineId: String): List<FactVectorEntity>

    @Query("SELECT COUNT(*) FROM fact_vectors WHERE engineId = :engineId")
    suspend fun countForEngine(engineId: String): Int

    /** Fact ids that already have a vector in this engine space. */
    @Query("SELECT factId FROM fact_vectors WHERE engineId = :engineId")
    suspend fun factIdsForEngine(engineId: String): List<String>

    @Query("DELETE FROM fact_vectors WHERE engineId = :engineId")
    suspend fun deleteForEngine(engineId: String)

    /** Fact lifecycle GC (supersede/forget/delete → drop the vector). */
    @Query("DELETE FROM fact_vectors WHERE factId IN (:factIds)")
    suspend fun deleteByFactIds(factIds: List<String>)

    @Query("DELETE FROM fact_vectors")
    suspend fun wipeAll()
}

@Dao
interface EntityDao {

    @Insert
    suspend fun insert(entity: EntityRefEntity): Long

    @Update
    suspend fun update(entity: EntityRefEntity)

    @Query("SELECT * FROM entities WHERE nameNormalized = :nameNormalized LIMIT 1")
    suspend fun byNameNormalized(nameNormalized: String): EntityRefEntity?

    @Query("SELECT * FROM entities ORDER BY nameNormalized ASC")
    suspend fun all(): List<EntityRefEntity>

    /** Idempotent upsert on the normalized merge key; returns the row id. */
    @Transaction
    suspend fun upsertByName(
        name: String,
        nameNormalized: String,
        kind: String,
        now: Long,
    ): Long {
        val existing = byNameNormalized(nameNormalized)
        return if (existing == null) {
            insert(EntityRefEntity(name = name, nameNormalized = nameNormalized, kind = kind, firstSeenAt = now, lastSeenAt = now))
        } else {
            update(
                existing.copy(
                    kind = kind,
                    lastSeenAt = now,
                    // Keep the first-seen display name stable.
                    name = existing.name,
                ),
            )
            existing.id
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLink(link: FactEntityLinkEntity)

    @Query("SELECT * FROM fact_entities WHERE factId = :factId")
    suspend fun linksForFact(factId: String): List<FactEntityLinkEntity>

    @Query("SELECT * FROM fact_entities")
    suspend fun allLinks(): List<FactEntityLinkEntity>

    /** Link GC for deleted/superseded facts. */
    @Query("DELETE FROM fact_entities WHERE factId IN (:factIds)")
    suspend fun deleteLinksByFactIds(factIds: List<String>)

    /** Entities no fact mentions anymore (full re-derivation leftovers). */
    @Query("DELETE FROM entities WHERE id NOT IN (SELECT DISTINCT entityId FROM fact_entities)")
    suspend fun deleteOrphans(): Int

    @Query("DELETE FROM fact_entities")
    suspend fun wipeLinks()

    @Query("DELETE FROM entities")
    suspend fun wipeAll()
}

/**
 * Inert composite for tests and default coordinator wiring (mirrors
 * [NoopBehaviorDaos]): the semantic lane silently does nothing without a
 * database.
 */
@Suppress("TooManyFunctions")
object NoopVectorDaos : FactVectorDao, EntityDao {
    override suspend fun upsert(row: FactVectorEntity) = Unit
    override suspend fun forEngine(engineId: String): List<FactVectorEntity> = emptyList()
    override suspend fun countForEngine(engineId: String): Int = 0
    override suspend fun factIdsForEngine(engineId: String): List<String> = emptyList()
    override suspend fun deleteForEngine(engineId: String) = Unit
    override suspend fun deleteByFactIds(factIds: List<String>) = Unit
    // wipeAll: one override satisfies both identical declarations.

    override suspend fun insert(entity: EntityRefEntity): Long = 0
    override suspend fun update(entity: EntityRefEntity) = Unit
    override suspend fun byNameNormalized(nameNormalized: String): EntityRefEntity? = null
    override suspend fun all(): List<EntityRefEntity> = emptyList()
    override suspend fun upsertByName(name: String, nameNormalized: String, kind: String, now: Long): Long = 0
    override suspend fun insertLink(link: FactEntityLinkEntity) = Unit
    override suspend fun linksForFact(factId: String): List<FactEntityLinkEntity> = emptyList()
    override suspend fun allLinks(): List<FactEntityLinkEntity> = emptyList()
    override suspend fun deleteLinksByFactIds(factIds: List<String>) = Unit
    override suspend fun deleteOrphans(): Int = 0
    override suspend fun wipeLinks() = Unit

    override suspend fun wipeAll() = Unit
}
