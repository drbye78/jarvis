package com.jarvis.assistant.cognitive.extract

import com.jarvis.assistant.cognitive.data.BehaviorLogDao
import com.jarvis.assistant.cognitive.data.BehaviorLogEntity
import com.jarvis.assistant.cognitive.data.CommandEventDao
import com.jarvis.assistant.cognitive.data.CommandEventEntity
import com.jarvis.assistant.cognitive.data.EntityDao
import com.jarvis.assistant.cognitive.data.EntityRefEntity
import com.jarvis.assistant.cognitive.data.ExtractionQueueDao
import com.jarvis.assistant.cognitive.data.ExtractionQueueEntity
import com.jarvis.assistant.cognitive.data.FactEntityLinkEntity
import com.jarvis.assistant.cognitive.data.FactVectorDao
import com.jarvis.assistant.cognitive.data.FactVectorEntity
import com.jarvis.assistant.cognitive.data.HabitRuleDao
import com.jarvis.assistant.cognitive.data.HabitRuleEntity
import com.jarvis.assistant.cognitive.data.MemoryMetaDao
import com.jarvis.assistant.cognitive.data.MemoryMetaEntity
import com.jarvis.assistant.cognitive.data.SessionSummaryDao
import com.jarvis.assistant.cognitive.data.SessionSummaryEntity
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

    override suspend fun inRange(fromInclusive: Long, toInclusive: Long): List<MessageEntity> =
        rows.values.filter { it.id > fromInclusive && it.id <= toInclusive }.sortedBy { it.id }

    override suspend fun firstDoomedId(keep: Int): Long? =
        rows.keys.sortedDescending().getOrNull(keep)

    override suspend fun lastMessageAt(): Long? = rows.values.maxOfOrNull { it.createdAt }

    override suspend fun clear() = rows.clear()
}

// ---------------------------------------------------------------------------
// COGNITIVE_PLAN Phase 2 (§8): behaviour-layer fakes (in-memory, plain).
// ---------------------------------------------------------------------------

class FakeCommandEventDao : CommandEventDao {
    val rows = mutableListOf<CommandEventEntity>()

    override suspend fun insert(row: CommandEventEntity): Long {
        rows += row
        return rows.size.toLong()
    }

    override suspend fun countAll(): Int = rows.size

    override suspend fun voiceOkSince(since: Long, tools: List<String>): List<CommandEventEntity> =
        rows.filter {
            it.at >= since && it.ok && it.origin == "VOICE" && it.tool in tools
        }

    override suspend fun deleteOlderThan(cutoff: Long): Int {
        val doomed = rows.count { it.at < cutoff }
        rows.removeAll { it.at < cutoff }
        return doomed
    }

    override suspend fun wipeAll() {
        rows.clear()
    }
}

class FakeHabitRuleDao : HabitRuleDao {
    val rows = LinkedHashMap<Long, HabitRuleEntity>()
    private var nextId = 1L

    override suspend fun insert(rule: HabitRuleEntity): Long {
        val id = nextId++
        rows[id] = rule.copy(id = id)
        return id
    }

    override suspend fun update(rule: HabitRuleEntity) {
        rows[rule.id] = rule
    }

    override suspend fun byKey(
        tool: String,
        fingerprint: String,
        hourBucket: Int?,
        kind: String,
    ): HabitRuleEntity? = rows.values.firstOrNull {
        it.tool == tool && it.argsFingerprint == fingerprint &&
            it.hourBucket == hourBucket && it.kind == kind
    }

    override suspend fun byFingerprint(tool: String, fingerprint: String): List<HabitRuleEntity> =
        rows.values.filter { it.tool == tool && it.argsFingerprint == fingerprint }

    override suspend fun all(): List<HabitRuleEntity> = rows.values.toList()

    override suspend fun candidateRules(): List<HabitRuleEntity> =
        rows.values.filter { it.state == "PROBATION" || it.state == "ACTIVE" }

    override suspend fun byId(id: Long): HabitRuleEntity? = rows[id]

    override suspend fun wipeAll() = rows.clear()
}

class FakeBehaviorLogDao : BehaviorLogDao {
    val rows = mutableListOf<BehaviorLogEntity>()

    override suspend fun insert(row: BehaviorLogEntity): Long {
        rows += row
        return rows.size.toLong()
    }

    override suspend fun firedSince(since: Long): Int =
        rows.count { it.decision == "FIRED" && it.at >= since }

    override suspend fun latestForRule(ruleId: Long): BehaviorLogEntity? =
        rows.filter { it.ruleId == ruleId }.maxByOrNull { it.at }

    override suspend fun latestFiredSince(since: Long): BehaviorLogEntity? =
        rows.filter { it.decision == "FIRED" && it.at >= since }.maxByOrNull { it.at }

    override suspend fun countForRuleSince(ruleId: Long, since: Long): Int =
        rows.count { it.ruleId == ruleId && it.at >= since }

    override suspend fun deleteOlderThan(cutoff: Long): Int {
        val doomed = rows.count { it.at < cutoff }
        rows.removeAll { it.at < cutoff }
        return doomed
    }

    override suspend fun wipeAll() = rows.clear()
}

class FakeSessionSummaryDao : SessionSummaryDao {
    val rows = mutableListOf<SessionSummaryEntity>()
    private var nextId = 1L

    override suspend fun insert(row: SessionSummaryEntity): Long {
        val id = nextId++
        rows += row.copy(id = id)
        return id
    }

    override suspend fun latestDaily(): SessionSummaryEntity? =
        rows.filter { it.kind == "DAILY" }.maxByOrNull { it.toAt }

    override suspend fun sessionsAfter(fromAt: Long): List<SessionSummaryEntity> =
        rows.filter { it.kind == "SESSION" && it.toAt > fromAt }.sortedBy { it.toAt }

    override suspend fun sessionsSince(fromAt: Long): List<SessionSummaryEntity> =
        rows.filter { it.kind == "SESSION" && it.toAt >= fromAt }.sortedBy { it.toAt }

    override suspend fun latestSession(): SessionSummaryEntity? =
        rows.filter { it.kind == "SESSION" }.maxByOrNull { it.toMessageId }

    override suspend fun countDaily(): Int = rows.count { it.kind == "DAILY" }

    override suspend fun oldestDaily(): SessionSummaryEntity? =
        rows.filter { it.kind == "DAILY" }.minByOrNull { it.toAt }

    override suspend fun deleteById(id: Long) {
        rows.removeAll { it.id == id }
    }

    override suspend fun wipeAll() = rows.clear()
}

// ---------------------------------------------------------------------------
// COGNITIVE_PLAN Phase 3 (§11): semantic-recall fakes (in-memory, plain).
// ---------------------------------------------------------------------------

class FakeFactVectorDao : FactVectorDao {
    val rows = linkedMapOf<String, FactVectorEntity>()

    override suspend fun upsert(row: FactVectorEntity) {
        rows[row.factId] = row
    }

    override suspend fun forEngine(engineId: String): List<FactVectorEntity> =
        rows.values.filter { it.engineId == engineId }

    override suspend fun countForEngine(engineId: String): Int =
        rows.values.count { it.engineId == engineId }

    override suspend fun factIdsForEngine(engineId: String): List<String> =
        rows.values.filter { it.engineId == engineId }.map { it.factId }

    override suspend fun deleteForEngine(engineId: String) {
        rows.entries.removeAll { it.value.engineId == engineId }
    }

    override suspend fun deleteByFactIds(factIds: List<String>) {
        rows.keys.removeAll(factIds.toSet())
    }

    override suspend fun wipeAll() = rows.clear()
}

class FakeEntityDao : EntityDao {
    val rows = linkedMapOf<Long, EntityRefEntity>()
    val links = mutableListOf<FactEntityLinkEntity>()
    private var nextId = 1L

    override suspend fun insert(entity: EntityRefEntity): Long {
        val id = nextId++
        rows[id] = entity.copy(id = id)
        return id
    }

    override suspend fun update(entity: EntityRefEntity) {
        rows[entity.id] = entity
    }

    override suspend fun byNameNormalized(nameNormalized: String): EntityRefEntity? =
        rows.values.firstOrNull { it.nameNormalized == nameNormalized }

    override suspend fun all(): List<EntityRefEntity> = rows.values.toList()

    override suspend fun upsertByName(
        name: String,
        nameNormalized: String,
        kind: String,
        now: Long,
    ): Long {
        val existing = byNameNormalized(nameNormalized)
        return if (existing == null) {
            insert(
                EntityRefEntity(
                    name = name,
                    nameNormalized = nameNormalized,
                    kind = kind,
                    firstSeenAt = now,
                    lastSeenAt = now,
                ),
            )
        } else {
            update(existing.copy(kind = kind, lastSeenAt = now))
            existing.id
        }
    }

    override suspend fun insertLink(link: FactEntityLinkEntity) {
        links.removeAll {
            it.factId == link.factId && it.entityId == link.entityId && it.role == link.role
        }
        links += link
    }

    override suspend fun linksForFact(factId: String): List<FactEntityLinkEntity> =
        links.filter { it.factId == factId }

    override suspend fun allLinks(): List<FactEntityLinkEntity> = links.toList()

    override suspend fun deleteLinksByFactIds(factIds: List<String>) {
        links.removeAll { it.factId in factIds }
    }

    override suspend fun deleteOrphans(): Int {
        val linked = links.map { it.entityId }.toSet()
        val doomed = rows.values.count { it.id !in linked }
        rows.entries.removeAll { it.key !in linked }
        return doomed
    }

    override suspend fun wipeLinks() = links.clear()

    override suspend fun wipeAll() = rows.clear()
}
