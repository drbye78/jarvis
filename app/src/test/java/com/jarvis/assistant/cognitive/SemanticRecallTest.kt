package com.jarvis.assistant.cognitive

import com.jarvis.assistant.cognitive.data.EntityRefEntity
import com.jarvis.assistant.cognitive.data.FactVectorEntity
import com.jarvis.assistant.cognitive.data.MemoryMetaEntity
import com.jarvis.assistant.cognitive.embed.EmbeddingEngine
import com.jarvis.assistant.cognitive.embed.VectorMath
import com.jarvis.assistant.cognitive.extract.FakeEntityDao
import com.jarvis.assistant.cognitive.extract.FakeExtractionQueueDao
import com.jarvis.assistant.cognitive.extract.FakeFactVectorDao
import com.jarvis.assistant.cognitive.extract.FakeMemoryMetaDao
import com.jarvis.assistant.cognitive.extract.FakeMessageDao
import com.jarvis.assistant.cognitive.extract.FakeUserFactDao
import com.jarvis.assistant.cognitive.model.FactCategory
import com.jarvis.assistant.cognitive.model.FactOrigin
import com.jarvis.assistant.cognitive.model.FactStatus
import com.jarvis.assistant.cognitive.data.UserFactEntity
import com.jarvis.assistant.cognitive.recall.SearchTokenizer
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.LlmChunk
import com.jarvis.assistant.cognitive.tools.MemoryOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COGNITIVE_PLAN Phase 3: coordinator-level integration of the semantic
 * lane — engine resolution (§12.4-3), the LOCAL-only gather vector channel,
 * the CLOUD tool-path channel, the relation-question boost, wipe/maintenance
 * semantics. All fakes in-memory; the §10.2 CI gate lives in
 * RetrievalEvalTest, the pure math in its own suites.
 */
class SemanticRecallTest {

    /** Embeds only the queryText by exact key; fact vectors come from the DAO. */
    private class QueryVecEngine(
        override val engineId: String,
        override val kind: EmbeddingEngine.Kind,
        private val vectorFor: (String) -> FloatArray,
    ) : EmbeddingEngine {
        override val dim = 2
        override suspend fun embed(texts: List<String>): List<FloatArray> =
            texts.map { vectorFor(it) }
    }

    private val memoryEnabled = MutableStateFlow(true)
    private val cloudEnabled = MutableStateFlow(true)

    private fun fact(id: String, value: String, category: FactCategory = FactCategory.OTHER) =
        UserFactEntity(
            factId = id,
            category = category.name,
            subject = "user",
            predicate = "likes",
            value = value,
            valueNormalized = value,
            searchText = SearchTokenizer.indexText("user", value, category.name),
            confidence = 0.9f,
            origin = FactOrigin.EXPLICIT.name,
            status = FactStatus.ACTIVE.name,
            supersedesId = null,
            contested = false,
            sensitive = false,
            sourceMessageId = null,
            createdAt = 1L,
            updatedAt = 1L,
            lastConfirmedAt = 1L,
            lastRecalledAt = null,
            recallCount = 0,
        )

    private fun coordinator(
        factDao: FakeUserFactDao,
        vectorDao: FakeFactVectorDao,
        entityDao: FakeEntityDao,
        metaDao: FakeMemoryMetaDao,
        embedderChoice: MutableStateFlow<String>,
        localEngine: EmbeddingEngine = QueryVecEngine(
            EmbeddingEngine.LOCAL_ID,
            EmbeddingEngine.Kind.LOCAL,
        ) { floatArrayOf(0f, 1f) },
        cloudEngine: EmbeddingEngine? = null,
    ) = CognitiveCoordinator(
        factDao = factDao,
        queueDao = FakeExtractionQueueDao(),
        metaDao = metaDao,
        messageDao = FakeMessageDao(),
        llm = object : LlmClient {
            override fun chatStream(request: ChatRequest) = flowOf(LlmChunk.Done)
        },
        memoryEnabled = memoryEnabled,
        autoExtractEnabled = MutableStateFlow(false),
        cloudEnabled = cloudEnabled,
        sensitiveVisible = MutableStateFlow(true),
        vectorDao = vectorDao,
        entityDao = entityDao,
        embedderChoice = embedderChoice,
        localEmbedder = localEngine,
        cloudEmbedder = cloudEngine,
        parentScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
    )

    // Three facts sharing the query token "плейлист" — lexical ties break
    // by factId (a < b < c), so ANY other order proves the vector channel.
    private val queryText = "включи плейлист"
    private suspend fun seedTied(factDao: FakeUserFactDao) {
        factDao.insert(fact("a", "плейлист альфа"))
        factDao.insert(fact("b", "плейлист бета"))
        factDao.insert(fact("c", "плейлист гамма"))
    }

    @Test
    fun `default AUTO with no benchmark verdict keeps the Phase 2 order`() = runBlocking {
        val factDao = FakeUserFactDao(); seedTied(factDao)
        val c = coordinator(
            factDao, FakeFactVectorDao(), FakeEntityDao(), FakeMemoryMetaDao(),
            MutableStateFlow("AUTO"),
        )
        val out = c.gather(queryText)
        assertTrue(out.contains("плейлист альфа"))
        assertTrue("lexical order must be unchanged", out.indexOf("альфа") < out.indexOf("бета"))
        assertTrue(out.indexOf("бета") < out.indexOf("гамма"))
    }

    @Test
    fun `LOCAL selector reranks via vectors and applies LIVE`() = runBlocking {
        val factDao = FakeUserFactDao(); seedTied(factDao)
        val vectorDao = FakeFactVectorDao()
        val choice = MutableStateFlow("AUTO")
        val metaDao = FakeMemoryMetaDao()
        val c = coordinator(
            factDao, vectorDao, FakeEntityDao(), metaDao, choice,
            localEngine = QueryVecEngine(
                EmbeddingEngine.LOCAL_ID,
                EmbeddingEngine.Kind.LOCAL,
            ) { text -> if (text == queryText) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f) },
        )
        // Vectors: "c" aligns with the query; a/b are orthogonal.
        vectorDao.upsert(vec("a", EmbeddingEngine.LOCAL_ID, floatArrayOf(0f, 1f)))
        vectorDao.upsert(vec("b", EmbeddingEngine.LOCAL_ID, floatArrayOf(0f, 1f)))
        vectorDao.upsert(vec("c", EmbeddingEngine.LOCAL_ID, floatArrayOf(1f, 0f)))

        val out = c.gather(queryText)
        assertTrue("AUTO without verdict must stay lexical", out.indexOf("альфа") < out.indexOf("гамма"))

        // Live toggle: the same coordinator instance, selector flipped.
        choice.value = "LOCAL"
        val out2 = c.gather(queryText)
        // RRF stabilizes the lexical #1 (a), but the cosine win must lift c
        // over b (c was 3rd in the pure lexical order).
        assertTrue(
            "vector channel must promote c above b",
            out2.indexOf("гамма") < out2.indexOf("бета"),
        )
    }

    @Test
    fun `CLOUD engine never enters the gather path`() = runBlocking {
        val factDao = FakeUserFactDao(); seedTied(factDao)
        val vectorDao = FakeFactVectorDao()
        val cloud = QueryVecEngine(EmbeddingEngine.CLOUD_ID, EmbeddingEngine.Kind.CLOUD) {
            floatArrayOf(1f, 0f)
        }
        val c = coordinator(
            factDao, vectorDao, FakeEntityDao(), FakeMemoryMetaDao(),
            MutableStateFlow("CLOUD"),
            cloudEngine = cloud,
        )
        vectorDao.upsert(vec("c", EmbeddingEngine.CLOUD_ID, floatArrayOf(1f, 0f)))

        val out = c.gather(queryText)
        assertTrue(
            "cloud round-trip cannot fit the gather budget — lexical order only",
            out.indexOf("альфа") < out.indexOf("гамма"),
        )
    }

    @Test
    fun `recall_facts uses the CLOUD channel only with egress enabled`() = runBlocking {
        val factDao = FakeUserFactDao(); seedTied(factDao)
        val vectorDao = FakeFactVectorDao()
        val cloud = QueryVecEngine(EmbeddingEngine.CLOUD_ID, EmbeddingEngine.Kind.CLOUD) {
            floatArrayOf(1f, 0f)
        }
        val c = coordinator(
            factDao, vectorDao, FakeEntityDao(), FakeMemoryMetaDao(),
            MutableStateFlow("CLOUD"),
            cloudEngine = cloud,
        )
        vectorDao.upsert(vec("c", EmbeddingEngine.CLOUD_ID, floatArrayOf(1f, 0f)))

        // Egress OFF: §9.2 gate — lexical order.
        cloudEnabled.value = false
        val off = c.recallFacts(queryText)
        assertTrue(off is MemoryOutcome.Recalled)
        val offLines = (off as MemoryOutcome.Recalled).facts
        assertTrue(offLines[0].contains("альфа"))

        // Egress ON: the cloud query embedding reranks (c to the top).
        cloudEnabled.value = true
        val on = c.recallFacts(queryText)
        val onLines = (on as MemoryOutcome.Recalled).facts
        assertTrue(
            "cloud vector channel must promote c above b",
            onLines.indexOfFirst { it.contains("гамма") } <
                onLines.indexOfFirst { it.contains("бета") },
        )
    }

    @Test
    fun `relation question boost promotes the boss fact`() = runBlocking {
        val factDao = FakeUserFactDao()
        // Both facts are RELATION (the §7.1 profile category) so the boost
        // outcome is observable INSIDE one profile line: higher-ranked
        // first. Boss carries the lower confidence, so without the boost
        // the spouse fact leads; the «кто мой начальник?» question flips it.
        val lowConfBoss = fact("boss", "работаю у Иванова", FactCategory.RELATION)
            .copy(confidence = 0.5f, predicate = "boss")
        factDao.insert(lowConfBoss)
        factDao.insert(fact("sp", "жена Маша", FactCategory.RELATION).copy(predicate = "spouse"))
        val c = coordinator(
            factDao, FakeFactVectorDao(), FakeEntityDao(), FakeMemoryMetaDao(),
            MutableStateFlow("OFF"),
        )
        // Neutral utterance: no relation question → the higher-confidence
        // fact (spouse, 0.9) leads the profile line.
        val neutral = c.gather("что происходит?")
        assertTrue(
            "neutral: high-confidence fact must lead the profile line",
            neutral.indexOf("супруг(а)") < neutral.indexOf("начальник"),
        )

        // «кто мой начальник?» → the boss fact gets the +0.3 relation boost.
        val asked = c.gather("кто мой начальник?")
        assertTrue(
            "boss fact must be promoted by the relation boost",
            asked.indexOf("начальник") < asked.indexOf("супруг(а)"),
        )
    }

    @Test
    fun `wipeAll clears the semantic stores too`() = runBlocking {
        val factDao = FakeUserFactDao(); seedTied(factDao)
        val vectorDao = FakeFactVectorDao()
        val entityDao = FakeEntityDao()
        val metaDao = FakeMemoryMetaDao()
        val c = coordinator(factDao, vectorDao, entityDao, metaDao, MutableStateFlow("OFF"))
        vectorDao.upsert(vec("a", EmbeddingEngine.LOCAL_ID, floatArrayOf(1f, 0f)))
        entityDao.insert(EntityRefEntity(name = "Иванов", nameNormalized = "иванов", kind = "PERSON", firstSeenAt = 1L, lastSeenAt = 1L))
        metaDao.values[MemoryMetaEntity.KEY_EMBEDDER_WINNER] = EmbeddingEngine.LOCAL_ID

        c.wipeAll()
        assertTrue(vectorDao.rows.isEmpty())

        assertTrue(entityDao.rows.isEmpty())
        assertTrue(metaDao.values.isEmpty())
    }

    @Test
    fun `maintenance GCs stale vectors, tops up missing, derives entities`() = runBlocking {
        val factDao = FakeUserFactDao()
        val now = System.currentTimeMillis()

        // Fresh timestamps: the decay step would otherwise archive facts
        // stamped at epoch-1 (20600 idle days → below the floor) before the
        // vector step ever sees them — that IS the production contract.
        fun fresh(id: String, value: String, category: FactCategory = FactCategory.OTHER) =
            fact(id, value, category).copy(
                createdAt = now, updatedAt = now, lastConfirmedAt = now,
            )
        factDao.insert(fresh("a", "плейлист альфа"))
        factDao.insert(fact("gone", "устаревший факт"))
        factDao.updateStatus("gone", FactStatus.FORGOTTEN.name, 2L)
        factDao.insert(fresh("boss", "работаю у Иванова", FactCategory.RELATION).copy(predicate = "boss"))
        val vectorDao = FakeFactVectorDao()
        val entityDao = FakeEntityDao()
        val metaDao = FakeMemoryMetaDao()
        val c = coordinator(factDao, vectorDao, entityDao, metaDao, MutableStateFlow("OFF"))
        vectorDao.upsert(vec("gone", EmbeddingEngine.LOCAL_ID, floatArrayOf(1f, 0f)))
        metaDao.values[MemoryMetaEntity.KEY_VECTORS_ENGINE] = EmbeddingEngine.LOCAL_ID

        c.onMaintenance()
        println("PROGRESS=" + c.vectorBackfill.progress.value)

        // Stale vector GC'd…
        assertTrue(vectorDao.rows["gone"] == null)
        // …missing ACTIVE facts topped up (zero vectors from the query-only
        // fake engine are fine — the row existence is the contract)…
        assertEquals(2, vectorDao.countForEngine(EmbeddingEngine.LOCAL_ID))
        // …and the RELATION fact produced one entity + link. The entity
        // name is the fact's value verbatim (the honest minimal derivation:
        // bare-name values like «Иванов» derive cleanly; phrase values
        // derive as phrase entities — the inspector shows them as-is).
        assertEquals(1, entityDao.rows.size)
        val entity = entityDao.rows.values.single()
        assertEquals("работаю у Иванова", entity.name)
        assertEquals(EntityRefEntity.KIND_PERSON, entity.kind)
        assertEquals(listOf("boss"), entityDao.links.map { it.factId })
        // Idempotent: a second pass leaves one entity, still linked.
        c.onMaintenance()
        assertEquals(1, entityDao.rows.size)
    }

    private fun vec(factId: String, engineId: String, v: FloatArray) = FactVectorEntity(
        factId = factId,
        engineId = engineId,
        dim = 2,
        vec = VectorMath.floatsToBytes(v),
        createdAt = 1L,
    )
}
