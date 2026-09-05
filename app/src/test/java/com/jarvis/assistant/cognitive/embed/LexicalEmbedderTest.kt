package com.jarvis.assistant.cognitive.embed

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LexicalEmbedderTest {

    private val embedder = LexicalEmbedder()

    @Test
    fun `engine metadata is stable`() {
        assertEquals(EmbeddingEngine.LOCAL_ID, embedder.engineId)
        assertEquals(EmbeddingEngine.Kind.LOCAL, embedder.kind)
        assertEquals(LexicalEmbedder.DIM, embedder.dim)
    }

    @Test
    fun `embeddings are deterministic and normalized`() = runBlocking {
        val a = embedder.embed(listOf("меня зовут Алексей, я люблю фильмы Тарковского"))
        val b = embedder.embed(listOf("меня зовут Алексей, я люблю фильмы Тарковского"))
        val v = a.single()
        assertTrue(v.contentEquals(b.single()))
        assertEquals(1f, VectorMath.dot(v, v), 1e-5f)
        assertEquals(LexicalEmbedder.DIM, v.size)
    }

    @Test
    fun `empty text yields zero vector without crashing`() = runBlocking {
        val v = embedder.embed(listOf("", " .. !! ")).first()
        assertTrue(v.all { it == 0f })
    }

    @Test
    fun `morphological variants stay closer than unrelated topics`() = runBlocking {
        val anchor = embedder.embed(listOf("люблю фильмы Тарковского")).first()
        val inflected = embedder.embed(listOf("фильмы Тарковского — лучший режиссёр")).first()
        val unrelated = embedder.embed(listOf("запиши встречу с дантистом на вторник")).first()
        val simInflected = VectorMath.dot(anchor, inflected)
        val simUnrelated = VectorMath.dot(anchor, unrelated)
        assertTrue("inflected=$simInflected unrelated=$simUnrelated", simInflected > simUnrelated)
    }

    @Test
    fun `order of inputs is preserved`() = runBlocking {
        val vs = embedder.embed(listOf("кофе", "чай"))
        assertEquals(2, vs.size)
        assertTrue(vs[0].contentEquals(embedder.embed(listOf("кофе")).first()))
        assertTrue(vs[1].contentEquals(embedder.embed(listOf("чай")).first()))
    }
}
