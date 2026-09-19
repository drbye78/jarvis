package com.jarvis.assistant.cognitive.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * COGNITIVE_PLAN Phase 3 (§11), migration v5→v6: one stored embedding for
 * one fact, in exactly ONE engine space ([engineId]). The primary key is the
 * composite `(factId, engineId)` — a fact has at most one vector per engine,
 * and re-computing upserts over that pair (REMEDIATION_PLAN Phase 2 fixed
 * the old single-column `factId` PK, which contradicted this per-engine
 * contract and erased the previous engine's row on an engine switch).
 *
 * Vectors of non-ACTIVE facts are garbage-collected by maintenance
 * (retention step), and a selector switch to another engine makes all
 * rows of the old engine dead weight that the next backfill pass replaces
 * (per-engine storage keeps the switch cheap and resumable).
 *
 * `factId` is a declared FK to `user_facts.factId` with CASCADE (D1:
 * derived children are enforced): a fact deletion drops its vectors rather
 * than leaving orphans. The composite PK's leading column already indexes
 * the child side, and `UserFactEntity.factId` carries the unique index Room
 * requires on the parent.
 */
@Entity(
    tableName = "fact_vectors",
    primaryKeys = ["factId", "engineId"],
    foreignKeys = [
        ForeignKey(
            entity = UserFactEntity::class,
            parentColumns = ["factId"],
            childColumns = ["factId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("engineId")],
)
data class FactVectorEntity(
    /** Stable fact identity (user_facts.factId), never the internal rowid. */
    val factId: String,
    val engineId: String,
    /** Vector length — a corruption tripwire validated before use. */
    val dim: Int,
    /** L2-normalized float32, little-endian ([VectorMath] codec). */
    val vec: ByteArray,
    val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is FactVectorEntity &&
            other.factId == factId &&
            other.engineId == engineId &&
            other.dim == dim &&
            other.createdAt == createdAt &&
            other.vec.contentEquals(vec)

    override fun hashCode(): Int = factId.hashCode() * 31 + engineId.hashCode()
}

/**
 * One named thing derived from ACTIVE RELATION facts (§11: "entity/relation
 * derivation"). «работаю у Иванова» + «начальник — Иванов» merge into one
 * entity on the normalized name, carrying the kind for display.
 */
@Entity(
    tableName = "entities",
    indices = [Index("nameNormalized", unique = true)],
)
data class EntityRefEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Display name as first seen (original casing). */
    val name: String,
    /** Merge key ([SearchTokenizer.normalize]). */
    val nameNormalized: String,
    /** PERSON | ORG | ROLE | PET ([EntityIndex.EntityKind]). */
    val kind: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
) {
    companion object {
        const val KIND_PERSON = "PERSON"
        const val KIND_ORG = "ORG"
        const val KIND_ROLE = "ROLE"
        const val KIND_PET = "PET"
    }
}

/** Link row: which entity is mentioned by which fact, in which role. */
@Entity(
    tableName = "fact_entities",
    primaryKeys = ["factId", "entityId", "role"],
    foreignKeys = [
        ForeignKey(
            entity = UserFactEntity::class,
            parentColumns = ["factId"],
            childColumns = ["factId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("entityId")],
)
data class FactEntityLinkEntity(
    val factId: String,
    val entityId: Long,
    /** OBJECT = the fact's value names the entity (subject links reserved). */
    val role: String,
) {
    companion object {
        const val ROLE_OBJECT = "OBJECT"
    }
}
