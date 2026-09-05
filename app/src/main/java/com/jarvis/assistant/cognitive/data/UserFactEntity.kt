package com.jarvis.assistant.cognitive.data

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jarvis.assistant.cognitive.model.FactCategory
import com.jarvis.assistant.cognitive.model.FactOrigin
import com.jarvis.assistant.cognitive.model.FactSnapshot
import com.jarvis.assistant.cognitive.model.FactStatus
import com.jarvis.assistant.cognitive.recall.SearchTokenizer

/**
 * COGNITIVE_PLAN §5, migration v3→v4: one long-term user fact.
 *
 * Primary-key note (deliberate deviation, documented): the plan specifies a
 * UUIDv7 `id`, but Room's external-content FTS4 requires the content entity
 * to carry an INTEGER autoincrement primary key to drive its sync triggers.
 * So the row identity is [rowId] (Room/FTS-facing) and the plan's stable,
 * time-ordered identity is [factId] (unique index) — supersession chains,
 * the inspector and exports reference `factId`, never `rowId`.
 *
 * [searchText] is the ONLY FTS-indexed column: the plan's "indexed content
 * is written pre-tokenized" — a Russian-normalized stem stream produced by
 * [SearchTokenizer.indexText] from subject+value+category. Raw text columns
 * are never matched directly (SQLite's default tokenizers do not handle
 * Russian morphology; see the SearchTokenizer KDoc).
 */
@Entity(
    tableName = "user_facts",
    indices = [
        Index("factId", unique = true),
        Index("status"),
        Index("category"),
        Index("updatedAt"),
    ],
)
data class UserFactEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val factId: String,
    val category: String,
    val subject: String,
    val predicate: String,
    val value: String,
    val valueNormalized: String,
    /** Pre-tokenized index stream (see class KDoc); never shown to the user. */
    val searchText: String,
    val confidence: Float,
    val origin: String,
    val status: String,
    val supersedesId: String?,
    val contested: Boolean,
    val sensitive: Boolean,
    val sourceMessageId: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastConfirmedAt: Long,
    val lastRecalledAt: Long?,
    val recallCount: Int,
) {
    fun toSnapshot(): FactSnapshot = FactSnapshot(
        factId = factId,
        category = FactCategory.valueOf(category),
        subject = subject,
        predicate = predicate,
        value = value,
        valueNormalized = valueNormalized,
        confidence = confidence,
        origin = FactOrigin.valueOf(origin),
        status = FactStatus.valueOf(status),
        supersedesId = supersedesId,
        contested = contested,
        sensitive = sensitive,
        sourceMessageId = sourceMessageId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastConfirmedAt = lastConfirmedAt,
        lastRecalledAt = lastRecalledAt,
        recallCount = recallCount,
    )

    companion object {
        /**
         * Build an insertable entity from a pure snapshot. [searchText] is
         * derived HERE (single derivation point) so the index can never
         * drift from the value it mirrors.
         */
        fun fromSnapshot(s: FactSnapshot): UserFactEntity = UserFactEntity(
            factId = s.factId,
            category = s.category.name,
            subject = s.subject,
            predicate = s.predicate,
            value = s.value,
            valueNormalized = s.valueNormalized,
            searchText = SearchTokenizer.indexText(s.subject, s.value, s.category.name),
            confidence = s.confidence,
            origin = s.origin.name,
            status = s.status.name,
            supersedesId = s.supersedesId,
            contested = s.contested,
            sensitive = s.sensitive,
            sourceMessageId = s.sourceMessageId,
            createdAt = s.createdAt,
            updatedAt = s.updatedAt,
            lastConfirmedAt = s.lastConfirmedAt,
            lastRecalledAt = s.lastRecalledAt,
            recallCount = s.recallCount,
        )
    }
}

/**
 * External-content FTS4 index over [UserFactEntity] (Room keeps it in sync
 * with AFTER INSERT/UPDATE/DELETE triggers it generates). Only the
 * pre-tokenized [UserFactEntity.searchText] stream is indexed — queries go
 * through [SearchTokenizer.matchQuery], never raw text.
 */
@Fts4(contentEntity = UserFactEntity::class)
@Entity(tableName = "fact_fts")
data class FactFtsEntity(
    val searchText: String,
)
