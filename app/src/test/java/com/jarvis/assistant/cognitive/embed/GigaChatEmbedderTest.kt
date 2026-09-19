package com.jarvis.assistant.cognitive.embed

import com.jarvis.assistant.llm.LlmHttpException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GigaChatEmbedderTest {

    private fun okBody(vectors: List<List<Float>>, size: Int): String {
        val data = vectors.mapIndexed { i, v ->
            """{"object":"embedding","index":$i,"embedding":${v.joinToString(",", "[", "]")}}"""
        }
        return """{"data":${data.joinToString(",", "[", "]")},"model":"Embeddings","usage":{"prompt_tokens":$size}}"""
    }

    @Test
    fun `embed parses the OpenAI-style reply and restores request order`() = runBlocking {
        // The reply comes back deliberately shuffled: index 1 first.
        val reply = """{"data":[
            {"index":1,"embedding":[0f,2f]},
            {"index":0,"embedding":[1f,0f]}
        ]}""".replace("0f", "0").replace("2f", "2")
        val embedder = GigaChatEmbedder(
            embeddingsEndpoint = "https://x/embeddings",
            postJson = { _, _ -> GigaChatEmbedder.TransportReply(200, reply) },
            dim = 2,
        )
        val out = embedder.embed(listOf("первый", "второй"))
        assertEquals(2, out.size)
        assertEquals(1f, out[0][0], 1e-6f) // index 0 vector first
        assertEquals(0f, out[0][1], 1e-6f)
        assertEquals(1f, VectorMath.dot(out[1], out[1]), 1e-6f) // normalized
    }

    @Test
    fun `non-2xx raises LlmHttpException with the code`() = runBlocking {
        val embedder = GigaChatEmbedder(
            embeddingsEndpoint = "https://x/embeddings",
            postJson = { _, _ -> GigaChatEmbedder.TransportReply(403, null) },
        )
        try {
            embedder.embed(listOf("текст"))
            throw AssertionError("expected LlmHttpException")
        } catch (e: LlmHttpException) {
            assertEquals(403, e.code)
        }
    }

    @Test
    fun `malformed reply raises IOException`() = runBlocking {
        val embedder = GigaChatEmbedder(
            embeddingsEndpoint = "https://x/embeddings",
            postJson = { _, _ -> GigaChatEmbedder.TransportReply(200, "not json at all") },
        )
        try {
            embedder.embed(listOf("текст"))
            throw AssertionError("expected IOException")
        } catch (expected: IOException) {
            // expected
        }
    }

    @Test
    fun `dimension mismatch is rejected`() = runBlocking {
        val embedder = GigaChatEmbedder(
            embeddingsEndpoint = "https://x/embeddings",
            postJson = { _, _ -> GigaChatEmbedder.TransportReply(200, okBody(listOf(listOf(1f, 0f)), 1)) },
            dim = 4,
        )
        try {
            embedder.embed(listOf("текст"))
            throw AssertionError("expected IOException for dim mismatch")
        } catch (expected: IOException) {
            // expected
        }
    }

    @Test
    fun `entitlement probe classifies ok denied transient`() = runBlocking {
        val ok = GigaChatEmbedder(
            "e",
            { _, _ -> Transport(200, okBody(listOf(List(GigaChatEmbedder.DIM) { 0.1f }), 1)) },
        )
        assertEquals(EmbeddingEngine.Entitlement.Ok, ok.checkEntitlement())

        val denied = GigaChatEmbedder("e", { _, _ -> Transport(403, null) })
        assertTrue(denied.checkEntitlement() is EmbeddingEngine.Entitlement.Denied)

        val transient = GigaChatEmbedder("e", { _, _ -> Transport(500, null) })
        assertTrue(transient.checkEntitlement() is EmbeddingEngine.Entitlement.Transient)
    }

    /**
     * F6 (REMEDIATION_PLAN): the probe's contract is "always a verdict". A 200
     * reply that is structurally broken trips `require(...)` inside [embed] —
     * an IllegalArgumentException, NOT an IOException. Pre-fix that escaped
     * checkEntitlement and aborted the whole Settings benchmark with a crash
     * instead of an honest "service problem".
     */
    @Test
    fun `entitlement probe returns transient instead of throwing on a malformed 200`() = runBlocking {
        // Valid JSON envelope, but the entry has no `embedding` array (IOException path).
        val malformed = GigaChatEmbedder(
            "e",
            { _, _ -> Transport(200, """{"data":[{"index":0}]}""") },
        )
        assertTrue(malformed.checkEntitlement() is EmbeddingEngine.Entitlement.Transient)

        // index out of range → require(...) → IllegalArgumentException: the
        // exception class that used to escape checkEntitlement entirely.
        val badIndex = GigaChatEmbedder(
            "e",
            { _, _ -> Transport(200, """{"data":[{"index":5,"embedding":[0.1]}]}""") },
        )
        assertTrue(badIndex.checkEntitlement() is EmbeddingEngine.Entitlement.Transient)

        // Non-numeric vector entry → NumberFormatException (same escape class).
        val nonNumeric = GigaChatEmbedder(
            "e",
            { _, _ -> Transport(200, """{"data":[{"index":0,"embedding":["abc"]}]}""") },
        )
        assertTrue(nonNumeric.checkEntitlement() is EmbeddingEngine.Entitlement.Transient)
    }

    private fun Transport(code: Int, body: String?) = GigaChatEmbedder.TransportReply(code, body)

    @Test
    fun `endpointFor derives the embeddings url from the chat endpoint`() {
        assertEquals(
            "https://gigachat.devices.sberbank.ru/api/v1/embeddings",
            GigaChatEmbedder.endpointFor("https://gigachat.devices.sberbank.ru/api/v1/chat/completions"),
        )
    }
}
