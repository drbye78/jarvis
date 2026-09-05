package com.jarvis.assistant.cognitive.embed

import com.jarvis.assistant.cognitive.data.FactVectorEntity
import com.jarvis.assistant.cognitive.data.MemoryMetaEntity
import com.jarvis.assistant.cognitive.extract.FakeFactVectorDao
import com.jarvis.assistant.cognitive.extract.FakeMemoryMetaDao
import com.jarvis.assistant.cognitive.extract.FakeUserFactDao
import com.jarvis.assistant.cognitive.model.FactCategory
import com.jarvis.assistant.cognitive.model.FactOrigin
import com.jarvis.assistant.cognitive.model.FactStatus
import com.jarvis.assistant.cognitive.data.UserFactEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class VectorBackfillTest {

    private fun fact(id: String, value: String) = UserFactEntity(
        factId = id,
        category = FactCategory.PREFERENCE.name,
        subject = "user",
        predicate = "likes",
        value = value,
        valueNormalized = value,
        searchText = value,
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

    private class ThrowingEngine : EmbeddingEngine {
        override val engineId = "throwing"
        override val kind = EmbeddingEngine.Kind.LOCAL
        override val dim = 4
        override suspend fun embed(texts: List<String>): List<FloatArray> =
            throw IOException("boom")
    }

    @Test
    fun `local build writes all vectors and stamps the engine`() = runBlocking {
        val factDao = FakeUserFactDao()
        factDao.insert(fact("a", "люблю джаз"))
        factDao.insert(fact("b", "люблю рок"))
        val vectorDao = FakeFactVectorDao()
        val metaDao = FakeMemoryMetaDao()
        val backfill = VectorBackfill(
            factDao = factDao,
            vectorDao = vectorDao,
            metaDao = metaDao,
            cloudEnabled = { false },
            inTransaction = { it() },
        )

        val written = backfill.runFor(LexicalEmbedder())
        assertEquals(2, written)
        assertEquals(2, vectorDao.countForEngine(EmbeddingEngine.LOCAL_ID))
        assertEquals(EmbeddingEngine.LOCAL_ID, metaDao.values[MemoryMetaEntity.KEY_VECTORS_ENGINE])
        val progress = backfill.progress.value
        assertNotNull(progress)
        assertTrue(!progress!!.running)
        assertEquals(2, progress.done)
        assertEquals(null, progress.error)
    }

    @Test
    fun `run is resumable - existing vectors are not recomputed`() = runBlocking {
        val factDao = FakeUserFactDao()
        factDao.insert(fact("a", "люблю джаз"))
        factDao.insert(fact("b", "люблю рок"))
        val vectorDao = FakeFactVectorDao()
        val metaDao = FakeMemoryMetaDao()
        val backfill = VectorBackfill(
            factDao, vectorDao, metaDao,
            cloudEnabled = { false },
            inTransaction = { it() },
        )
        // One vector already present from an interrupted run.
        vectorDao.upsert(
            FactVectorEntity("a", EmbeddingEngine.LOCAL_ID, LexicalEmbedder.DIM, ByteArray(4), 1L),
        )

        val written = backfill.runFor(LexicalEmbedder())
        assertEquals(1, written)
        assertEquals(2, vectorDao.countForEngine(EmbeddingEngine.LOCAL_ID))
    }

    @Test
    fun `engine failure aborts with an error and keeps built rows`() = runBlocking {
        val factDao = FakeUserFactDao()
        factDao.insert(fact("a", "люблю джаз"))
        val vectorDao = FakeFactVectorDao()
        val backfill = VectorBackfill(
            factDao, vectorDao, FakeMemoryMetaDao(),
            cloudEnabled = { false },
            inTransaction = { it() },
        )
        val written = backfill.runFor(ThrowingEngine())
        assertEquals(0, written)
        assertEquals("boom", backfill.progress.value?.error)
        assertTrue(!backfill.progress.value!!.running)
    }

    @Test
    fun `cloud engine refuses to run while egress is disabled`() = runBlocking {
        val backfill = VectorBackfill(
            FakeUserFactDao(), FakeFactVectorDao(), FakeMemoryMetaDao(),
            cloudEnabled = { false },
            inTransaction = { it() },
        )
        val cloud = ThrowingEngine().let {
            // A CLOUD-kind engine stub.
            object : EmbeddingEngine {
                override val engineId = EmbeddingEngine.CLOUD_ID
                override val kind = EmbeddingEngine.Kind.CLOUD
                override val dim = 4
                override suspend fun embed(texts: List<String>) = emptyList<FloatArray>()
            }
        }
        try {
            backfill.runFor(cloud)
            throw AssertionError("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            // expected
        }
    }
}
