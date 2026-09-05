package com.jarvis.assistant.cognitive.extract

import com.jarvis.assistant.cognitive.data.ExtractionQueueDao
import com.jarvis.assistant.cognitive.data.ExtractionQueueEntity
import com.jarvis.assistant.cognitive.data.MemoryMetaDao
import com.jarvis.assistant.cognitive.data.MemoryMetaEntity
import com.jarvis.assistant.cognitive.data.UserFactDao
import com.jarvis.assistant.cognitive.data.UserFactEntity
import com.jarvis.assistant.data.MessageDao
import com.jarvis.assistant.data.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * JVM fakes for the cognitive DAOs (plan §1.4: "plain interfaces so JVM
 * tests can fake them"). In-memory, single-threaded semantics — enough for
 * the worker/coordinator tests; Room's SQL semantics are covered in
 * androidTest (DatabaseSmokeTest).
 */
class FakeUserFactDao : UserFactDao {
    val rows = LinkedHashMap<Long, UserFactEntity>()
    private var nextRow = 1L

    override suspend fun insert(fact: UserFactEntity): Long {
        val rowId = if (fact.rowId == 0L) nextRow++ else fact.rowId
        // REPLACE semantics (the unique factId index backs it in production).
        rows.entries.firstOrNull { it.value.factId == fact.factId }?.let { old ->
            rows.remove(old.key)
        }
        rows[rowId] = fact.copy(rowId = rowId)
        return rowId
    }

    override suspend fun update(fact: UserFactEntity) {
        rows[fact.rowId] = fact
    }

    override suspend fun byFactId(factId: String): UserFactEntity? =
        rows.values.firstOrNull { it.factId == factId }

    override suspend fun activeFacts(): List<UserFactEntity> =
        rows.values.filter { it.status == "ACTIVE" }.sortedByDescending { it.updatedAt }

    override suspend fun allFacts(): List<UserFactEntity> = rows.values.toList()

    override fun observeAll(): Flow<List<UserFactEntity>> = MutableStateFlow(rows.values.toList())

    override suspend fun activeCount(): Int = activeFacts().size

    override suspend fun findExact(subject: String, predicate: String, valueNorm: String): UserFactEntity? =
        activeFacts().firstOrNull {
            it.subject == subject && it.predicate == predicate && it.valueNormalized == valueNorm
        }

    override suspend fun confirmFact(factId: String, confidence: Float, confirmedAt: Long) {
        val row = byFactId(factId) ?: return
        update(row.copy(confidence = confidence, lastConfirmedAt = confirmedAt, updatedAt = confirmedAt))
    }

    override suspend fun updateConfidence(factId: String, confidence: Float) {
        val row = byFactId(factId) ?: return
        update(row.copy(confidence = confidence))
    }

    override suspend fun updateStatus(factId: String, status: String, now: Long) {
        val row = byFactId(factId) ?: return
        update(row.copy(status = status, updatedAt = now))
    }

    override suspend fun setContested(factId: String, contested: Boolean, now: Long) {
        val row = byFactId(factId) ?: return
        update(row.copy(contested = contested, updatedAt = now))
    }

    override suspend fun recordRecalls(factIds: List<String>, now: Long) {
        factIds.forEach { id ->
            val row = byFactId(id) ?: return@forEach
            update(row.copy(recallCount = row.recallCount + 1, lastRecalledAt = now))
        }
    }

    override suspend fun searchActive(matchQuery: String, limit: Int): List<UserFactEntity> {
        // Prefix-OR emulation over the pre-tokenized searchText column.
        val terms = matchQuery.split(" OR ").map { it.removeSuffix("*") }
        return activeFacts().filter { fact ->
            terms.any { term -> fact.searchText.split(' ').any { it.startsWith(term) } }
        }.take(limit)
    }

    override suspend fun weakestActive(limit: Int): List<UserFactEntity> =
        activeFacts().sortedWith(
            compareBy({ it.confidence }, { it.updatedAt }),
        ).take(limit)

    override suspend fun supersededBefore(cutoff: Long): List<UserFactEntity> =
        rows.values.filter { it.status == "SUPERSEDED" && it.updatedAt < cutoff }

    override suspend fun deleteByFactIds(factIds: List<String>) {
        rows.entries.removeAll { it.value.factId in factIds }
    }

    override suspend fun wipeAll() = rows.clear()
}

class FakeExtractionQueueDao : ExtractionQueueDao {
    val rows = LinkedHashMap<Long, ExtractionQueueEntity>()

    override suspend fun enqueue(row: ExtractionQueueEntity): Long {
        if (rows.containsKey(row.messageId)) return -1L
        rows[row.messageId] = row
        return row.messageId
    }

    override suspend fun pending(limit: Int): List<ExtractionQueueEntity> =
        rows.values.filter { it.state == "PENDING" }.sortedBy { it.messageId }.take(limit)

    override suspend fun running(): List<ExtractionQueueEntity> =
        rows.values.filter { it.state == "RUNNING" }.sortedBy { it.messageId }

    override suspend fun byMessageId(messageId: Long): ExtractionQueueEntity? = rows[messageId]

    override suspend fun pendingCount(): Int = rows.values.count { it.state == "PENDING" }

    override fun observePendingCount(): Flow<Int> =
        MutableStateFlow(rows.values.count { it.state == "PENDING" })

    override suspend fun updateState(messageId: Long, state: String, attempt: Int, batchId: String?, now: Long) {
        val row = rows[messageId] ?: return
        rows[messageId] = row.copy(state = state, attempt = attempt, batchId = batchId, updatedAt = now)
    }

    override suspend fun releaseBatch(batchId: String, now: Long) {
        rows.values.filter { it.batchId == batchId }.forEach {
            rows[it.messageId] = it.copy(state = "PENDING", batchId = null, updatedAt = now)
        }
    }

    override suspend fun delete(messageId: Long) {
        rows.remove(messageId)
    }

    override suspend fun wipeAll() = rows.clear()
}

class FakeMemoryMetaDao : MemoryMetaDao {
    val values = LinkedHashMap<String, String>()

    override suspend fun get(key: String): String? = values[key]

    override suspend fun put(entity: MemoryMetaEntity) {
        values[entity.key] = entity.value
    }

    override suspend fun all(): List<MemoryMetaEntity> =
        values.map { MemoryMetaEntity(it.key, it.value) }

    override suspend fun wipeAll() = values.clear()
}

class FakeMessageDao(vararg seed: MessageEntity) : MessageDao {
    val rows = LinkedHashMap<Long, MessageEntity>()
    private var nextId = 1L

    init {
        seed.forEach { insertSync(it) }
    }

    private fun insertSync(e: MessageEntity): Long {
        val id = if (e.id == 0L) nextId++ else e.id
        rows[id] = e.copy(id = id)
        return id
    }

    override suspend fun insert(e: MessageEntity): Long = insertSync(e)

    override suspend fun insertAll(e: List<MessageEntity>): List<Long> = e.map { insertSync(it) }

    override suspend fun all(): List<MessageEntity> = rows.values.sortedBy { it.id }

    override suspend fun byId(id: Long): MessageEntity? = rows[id]

    override suspend fun recentUserMessages(limit: Int): List<MessageEntity> =
        rows.values.filter { it.role == "user" }.sortedByDescending { it.id }.take(limit)

    override suspend fun recentDesc(n: Int): List<MessageEntity> =
        rows.values.sortedByDescending { it.id }.take(n)

    override fun recentDescLive(n: Int): Flow<List<MessageEntity>> =
        MutableStateFlow(rows.values.sortedByDescending { it.id }.take(n))

    override suspend fun trimToIds(ids: Set<Long>) {
        rows.entries.removeAll { it.key !in ids }
    }

    override suspend fun deleteAllExceptRecent(maxMessages: Int) {
        val keep = rows.keys.sortedDescending().take(maxMessages).toSet()
        rows.entries.removeAll { it.key !in keep }
    }

    override suspend fun clear() = rows.clear()
}
