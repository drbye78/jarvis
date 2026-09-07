package com.jarvis.assistant.integration

import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.cognitive.embed.GigaChatEmbedder
import com.jarvis.assistant.llm.GigaChatClient
import com.jarvis.assistant.llm.TokenManager
import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.LlmChunk
import com.jarvis.assistant.model.Message
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * P2.2 — LIVE GigaChat smoke tests (REMEDIATION_PLAN Phase 2). LOCAL ONLY:
 * they run when `local.secrets.properties` (or JARVIS_GIGACHAT_* env vars)
 * provides the OAuth client credentials, via `./gradlew :app:integrationTest`,
 * and SKIP through JUnit assumptions everywhere else — CI never sees a
 * failure from this class because CI has no credentials at all.
 *
 * Quota awareness (owner decision): every call below is deliberately tiny —
 * a one-word prompt capped at 16 output tokens, a short bounded stream, and a
 * single synthetic probe string for embeddings. Nothing user-generated is
 * ever sent from these tests.
 *
 * Assertion hygiene: token values are NEVER printed; assertions check
 * presence/shape only. A failing assertion message contains no response
 * content beyond lengths.
 */
class GigaChatLiveSmokeTest {

    companion object {
        /** 16 output tokens keep the chat smokes negligible on the quota. */
        private const val TINY_MAX_TOKENS = 16
        private const val CHAT_TIMEOUT_MS = 60_000L

        private val tokenManager: TokenManager by lazy {
            liveTokenManager(LiveSecrets.secrets)
        }
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
        }
    }

    @Before
    fun requireGigaChatCredentials() {
        LiveSecrets.assumeGigaChat("GigaChat live smoke test")
    }

    @Test
    fun `oauth token fetch succeeds and is served from the cache afterwards`() = runBlocking {
        val token = withTimeout(CHAT_TIMEOUT_MS) { tokenManager.getGigaChatToken() }
        // Presence only — the value must never surface in output.
        assertTrue("OAuth token must be non-blank", token.isNotBlank())
        // TokenManager caches the token in the vault; the second call must
        // resolve from the cache without a second OAuth round trip and stay
        // stable (asserted against the first value, never printed).
        val cached = withTimeout(CHAT_TIMEOUT_MS) { tokenManager.getGigaChatToken() }
        assertTrue("cached token must equal the first fetch", cached == token)
    }

    @Test
    fun `chatOnce answers a tiny one word prompt`() = runBlocking {
        val client = GigaChatClient(
            tokenManager,
            httpClient,
            endpoint = JarvisConfig().gigaChatEndpoint,
            defaultModel = JarvisConfig().gigaChatModel,
        )
        val answer = withTimeout(CHAT_TIMEOUT_MS) {
            client.chatOnce(
                ChatRequest(
                    messages = listOf(Message.user("Ответь одним словом: работает?")),
                    tools = emptyList(),
                    maxTokens = TINY_MAX_TOKENS,
                    temperature = 0.1,
                ),
            )
        }
        assertTrue("expected a non-empty completion, got ${answer.length} chars", answer.isNotBlank())
        assertTrue("tiny-prompt completion must stay tiny, was ${answer.length} chars", answer.length <= 200)
    }

    @Test
    fun `chatStream emits text chunks and terminates with Done`() = runBlocking {
        val client = GigaChatClient(
            tokenManager,
            httpClient,
            endpoint = JarvisConfig().gigaChatEndpoint,
            defaultModel = JarvisConfig().gigaChatModel,
        )
        val chunks = withTimeout(CHAT_TIMEOUT_MS) {
            client.chatStream(
                ChatRequest(
                    messages = listOf(Message.user("Считай от 1 до 3 цифрами.")),
                    tools = emptyList(),
                    maxTokens = TINY_MAX_TOKENS,
                    temperature = 0.1,
                ),
            ).take(8).toList()
        }
        assertTrue("expected at least one text chunk, got ${chunks.size} chunks", chunks.any { it is LlmChunk.Text })
        // A tiny answer finishes under the 8-chunk cap; then the SSE terminator
        // must be present (SseLlmClient sends Done before closing the channel).
        if (chunks.size < 8) {
            assertTrue("stream must terminate with Done", chunks.last() is LlmChunk.Done)
        }
    }

    @Test
    fun `embedding call returns one 1024-dim vector`() = runBlocking {
        val config = JarvisConfig()
        val embedder = GigaChatEmbedder(
            embeddingsEndpoint = GigaChatEmbedder.endpointFor(config.gigaChatEndpoint),
            postJson = GigaChatEmbedder.gigaChatHttpTransport(httpClient) { tokenManager.getGigaChatToken() },
        )
        val vectors = withTimeout(CHAT_TIMEOUT_MS) { embedder.embed(listOf(GigaChatEmbedder.PROBE_TEXT)) }
        assertTrue("one input → one vector", vectors.size == 1)
        assertTrue(
            "expected ${GigaChatEmbedder.DIM} dims, got ${vectors[0].size}",
            vectors[0].size == GigaChatEmbedder.DIM,
        )
    }
}
