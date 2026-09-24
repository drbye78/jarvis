package com.jarvis.assistant.integration

import com.jarvis.assistant.llm.YandexAiStudioClient
import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.LlmChunk
import com.jarvis.assistant.model.Message
import com.jarvis.assistant.model.ToolDefinition
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * LIVE Yandex AI Studio Responses API smoke test. LOCAL ONLY: runs via
 * `./gradlew :app:integrationTest` when `local.secrets.properties` (or
 * `JARVIS_YANDEX_API_KEY`) provides the Cloud API key; skips through a JUnit
 * assumption everywhere else (CI has no credentials and can never fail
 * because of this class — the shared `*LiveSmokeTest*` exclusion also keeps it
 * out of the unit gate).
 *
 * WHAT THIS PROVES end-to-end against the real service:
 * - a plain turn returns non-empty text (model URI + `Api-Key` + folder
 *   discovery accepted);
 * - a web-search turn returns grounded non-empty text (server-executed
 *   `web_search`, no client round-trip);
 * - a function-tool prompt yields a [LlmChunk.FunctionCallComplete] with the
 *   tool name (client function calling + `call_id`).
 *
 * QUOTA (owner decision): exactly THREE short calls, tiny token caps, fixed
 * non-personal probe prompts. Token values are never printed and assertions
 * check presence/shape only.
 *
 * The endpoint is the verified literal (the app's `JarvisConfig` value); the
 * folder id is discovered from `GET /v1/models` — also a smoke of that path.
 */
class YandexLlmLiveSmokeTest {

    private companion object {
        const val ENDPOINT = "https://ai.api.cloud.yandex.net/v1"
        const val MODEL = "aliceai-llm"
        const val TIMEOUT_MS = 60_000L
        const val TINY_MAX_TOKENS = 48
        const val TINY_SEARCH_TOKENS = 128

        val WEATHER_TOOL: ToolDefinition = ToolDefinition(
            name = "get_weather",
            description = "Узнать текущую погоду в городе",
            parameters = Json.parseToJsonElement(
                """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""
            ).jsonObject,
        )

        fun newClient(webSearch: Boolean): YandexAiStudioClient = YandexAiStudioClient(
            // Plain client: the Yandex host chains to a public CA, so the
            // default trust store works (no SberTrust override).
            httpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .build(),
            apiKeyProvider = { LiveSecrets.secrets.yandexApiKey.orEmpty() },
            endpoint = ENDPOINT,
            defaultModel = MODEL,
            manualFolderId = "",
            webSearchEnabled = webSearch,
        )

        /** One instance per mode: folder discovery runs once and is cached. */
        val noSearchClient: YandexAiStudioClient by lazy { newClient(webSearch = false) }
        val searchClient: YandexAiStudioClient by lazy { newClient(webSearch = true) }
    }

    @Before
    fun requireYandexCredentials() {
        LiveSecrets.assumeYandex("Yandex AI Studio LLM smoke test")
    }

    @Test
    fun `plain call returns non-empty text`() = runBlocking {
        val answer = withTimeout(TIMEOUT_MS) {
            noSearchClient.chatOnce(
                ChatRequest(
                    messages = listOf(Message.user("Ответь одним словом: работает?")),
                    tools = emptyList(),
                    maxTokens = TINY_MAX_TOKENS,
                    temperature = 0.1,
                ),
            )
        }
        assertTrue("expected a non-empty completion, got ${answer.length} chars", answer.isNotBlank())
        assertTrue("tiny-prompt completion must stay tiny, was ${answer.length} chars", answer.length <= 400)
    }

    @Test
    fun `web search call returns grounded non-empty text`() = runBlocking {
        val chunks = withTimeout(TIMEOUT_MS) {
            searchClient.chatStream(
                ChatRequest(
                    messages = listOf(Message.user("Какая сейчас погода в Москве? Ответь коротко.")),
                    tools = emptyList(),
                    maxTokens = TINY_SEARCH_TOKENS,
                    temperature = 0.1,
                ),
            ).toList()
        }
        val text = chunks.filterIsInstance<LlmChunk.Text>().joinToString("") { it.text }
        assertTrue("expected grounded non-empty search text, got ${text.length} chars", text.isNotBlank())
        assertTrue("web-search turn must terminate with Done", chunks.last() is LlmChunk.Done)
        // Server-executed search never yields a client call.
        assertTrue(
            "a web-search turn has no pending client tool calls",
            chunks.none { it is LlmChunk.FunctionCallComplete },
        )
    }

    @Test
    fun `function tool prompt yields a function call`() = runBlocking {
        val chunks = withTimeout(TIMEOUT_MS) {
            noSearchClient.chatStream(
                ChatRequest(
                    messages = listOf(
                        Message.system("Для погоды обязательно вызывай инструмент get_weather."),
                        Message.user("Какая сейчас погода в Казани?"),
                    ),
                    tools = listOf(WEATHER_TOOL),
                    maxTokens = TINY_MAX_TOKENS,
                    temperature = 0.1,
                ),
            ).toList()
        }
        val call = chunks.filterIsInstance<LlmChunk.FunctionCallComplete>().firstOrNull()
        assertTrue("expected a get_weather function call, got none", call != null)
        assertTrue("expected get_weather, got ${call!!.call.function.name}", call.call.function.name == "get_weather")
        assertTrue("call_id must be non-blank for the round-trip", call.call.id.isNotBlank())
        assertTrue("arguments must be a non-blank JSON string", call.call.function.arguments.isNotBlank())
    }
}
