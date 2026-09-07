package com.jarvis.assistant.cognitive

import com.jarvis.assistant.cognitive.data.EntityDao
import com.jarvis.assistant.cognitive.data.MemoryMetaDao
import com.jarvis.assistant.cognitive.data.MemoryMetaEntity
import com.jarvis.assistant.cognitive.data.UserFactDao
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * §4/§9.2 (P4.4): the MemoryInspector JSON export, extracted from
 * [CognitiveCoordinator] — every fact + meta + the §11 derived entity
 * index, serialized for the user-facing export. Composition only: the
 * coordinator delegates and stays the ONE class the rest of the app sees.
 *
 * No wipe here — `wipeAll` remains on the coordinator (it spans ALL
 * cognitive tables and belongs to the orchestration layer).
 */
class FactExportService(
    private val factDao: UserFactDao,
    private val metaDao: MemoryMetaDao,
    private val entityDao: EntityDao,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** Export (plan §7 principle 7): every fact + meta, JSON. */
    suspend fun exportJson(): JsonObject {
        val facts = factDao.allFacts()
        val meta = metaDao.all()
        return buildJsonObject {
            put("schemaRev", metaDao.get(MemoryMetaEntity.KEY_SCHEMA_REV) ?: SCHEMA_REV)
            put("exportedAt", nowMs())
            putJsonArray("facts") {
                facts.forEach { fact ->
                    add(
                        buildJsonObject {
                            put("factId", fact.factId)
                            put("category", fact.category)
                            put("subject", fact.subject)
                            put("predicate", fact.predicate)
                            put("value", fact.value)
                            put("confidence", fact.confidence.toDouble())
                            put("origin", fact.origin)
                            put("status", fact.status)
                            put("contested", fact.contested)
                            put("sensitive", fact.sensitive)
                            put("sourceMessageId", fact.sourceMessageId ?: -1)
                            put("createdAt", fact.createdAt)
                            put("updatedAt", fact.updatedAt)
                        },
                    )
                }
            }
            // Phase 3 (§11): the derived entity index + vector-store
            // provenance are part of the memory the user can inspect/export.
            // Reads happen BEFORE the JSON builder (its lambdas are not
            // suspend).
            val entityLinks = entityDao.allLinks().groupBy { it.entityId }
            val entities = entityDao.all()
            putJsonArray("entities") {
                entities.forEach { entity ->
                    add(
                        buildJsonObject {
                            put("name", entity.name)
                            put("kind", entity.kind)
                            putJsonArray("factIds") {
                                entityLinks[entity.id].orEmpty().forEach { add(JsonPrimitive(it.factId)) }
                            }
                        },
                    )
                }
            }
            put("vectorEngine", metaDao.get(MemoryMetaEntity.KEY_VECTORS_ENGINE) ?: "")
            putJsonObject("meta") {
                meta.forEach { put(it.key, it.value) }
            }
        }
    }

    companion object {
        /** Bumped with the Room schema (kept in sync with the DB version). */
        const val SCHEMA_REV = "6"
    }
}
