package com.jarvis.assistant

import com.jarvis.assistant.llm.LlmHttpException
import com.jarvis.assistant.llm.YandexAiStudioClient
import com.jarvis.assistant.llm.YandexFolderResolutionException
import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.model.LlmChunk
import com.jarvis.assistant.model.Message
import com.jarvis.assistant.model.ToolCall
import com.jarvis.assistant.model.ToolDefinition
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hermetic wire test for [YandexAiStudioClient]: the request we EMIT must match
 * the LIVE-VERIFIED Responses API contract (full `gpt://…` model URI, flattened
 * function tools, `{"type":"web_search"}`, `instructions` from system messages,
 * all three `input` item forms) and the lazy folder-resolution rules must hold
 * (manual override wins with no discovery call, discovery parses + caches, a
 * discovery failure is typed). The recorded responses surface as the right
 * [LlmChunk]s.
 */
class YandexWireTest {

    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(
        manualFolderId: String = "b1gfolder",
        webSearch: Boolean = true,
        model: String = "aliceai-llm",
    ): YandexAiStudioClient = YandexAiStudioClient(
        httpClient = OkHttpClient(),
        apiKeyProvider = { "test-key" },
        endpoint = server.url("/v1").toString(),
        defaultModel = model,
        manualFolderId = manualFolderId,
        webSearchEnabled = webSearch,
    )

    private fun toolDef(): ToolDefinition = ToolDefinition(
        name = "get_weather",
        description = "Узнать текущую погоду в городе",
        parameters = json.parseToJsonElement(
            """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""
        ).jsonObject,
    )

    private fun request(withTool: Boolean = true, system: Boolean = true): ChatRequest = ChatRequest(
        messages = buildList {
            if (system) add(Message.system("you are jarvis"))
            add(Message.user("привет"))
        },
        tools = if (withTool) listOf(toolDef()) else emptyList(),
    )

    private fun bodyOf(request: RecordedRequest) =
        json.parseToJsonElement(request.body.readUtf8()).jsonObject

    private fun fixture(resource: String): String =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(resource)) {
            "missing fixture $resource"
        }.bufferedReader().use { it.readText() }

    @Test
    fun `emits the verified responses request shape`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(PLAIN_STREAM))
        val chunks = client().chatStream(request()).toList()

        assertEquals("manual folder must skip discovery", 1, server.requestCount)
        val recorded = server.takeRequest()
        assertEquals("/v1/responses", recorded.path)
        assertEquals("Api-Key test-key", recorded.getHeader("Authorization"))
        assertEquals("text/event-stream", recorded.getHeader("Accept"))

        val body = bodyOf(recorded)
        assertEquals("gpt://b1gfolder/aliceai-llm/latest", body["model"]!!.jsonPrimitive.content)
        assertEquals("you are jarvis", body["instructions"]!!.jsonPrimitive.content)
        assertTrue(body["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(2048, body["max_output_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(0.7, body["temperature"]!!.jsonPrimitive.content.toDouble(), 0.0001)
        assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)

        val tools = body["tools"]!!.jsonArray
        assertEquals(2, tools.size)
        assertEquals("web_search", tools[0].jsonObject["type"]!!.jsonPrimitive.content)
        val fn = tools[1].jsonObject
        assertEquals("function", fn["type"]!!.jsonPrimitive.content)
        assertEquals("get_weather", fn["name"]!!.jsonPrimitive.content)
        assertTrue("tool schema must be FLATTENED, not nested", fn["parameters"]!!.jsonObject.containsKey("type"))
        assertFalse("no [OI]-style nested function object", fn.containsKey("function"))

        val input = body["input"]!!.jsonArray
        assertEquals(1, input.size)
        assertEquals("user", input[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("привет", input[0].jsonObject["content"]!!.jsonPrimitive.content)

        assertEquals("Привет, мир!", chunks.filterIsInstance<LlmChunk.Text>().joinToString("") { it.text })
        assertTrue(chunks.last() is LlmChunk.Done)
    }

    @Test
    fun `history maps assistant call and tool result to flat input items`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(PLAIN_STREAM))
        val history = ChatRequest(
            messages = listOf(
                Message.system("sys"),
                Message.user("погода?"),
                Message(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(
                        ToolCall(id = "call_1", function = FunctionCall("get_weather", """{"city":"Москва"}"""))
                    ),
                ),
                Message(role = "tool", content = """{"temp":12}""", name = "get_weather", toolCallId = "call_1"),
            ),
            tools = listOf(toolDef()),
        )
        client().chatStream(history).toList()

        val input = bodyOf(server.takeRequest())["input"]!!.jsonArray
        assertEquals(3, input.size)

        assertEquals("user", input[0].jsonObject["role"]!!.jsonPrimitive.content)

        val call = input[1].jsonObject
        assertEquals("function_call", call["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", call["call_id"]!!.jsonPrimitive.content)
        assertEquals("get_weather", call["name"]!!.jsonPrimitive.content)
        assertEquals("""{"city":"Москва"}""", call["arguments"]!!.jsonPrimitive.content)

        val result = input[2].jsonObject
        assertEquals("function_call_output", result["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", result["call_id"]!!.jsonPrimitive.content)
        assertEquals("""{"temp":12}""", result["output"]!!.jsonPrimitive.content)
    }

    @Test
    fun `web search can be disabled`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(PLAIN_STREAM))
        client(webSearch = false).chatStream(request()).toList()

        val tools = bodyOf(server.takeRequest())["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("function", tools[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `folder discovery parses the folder and is cached`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"object":"list","data":[{"id":"gpt://b1gdiscovered/aliceai-llm/latest"}]}""")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(PLAIN_STREAM))
        val llm = client(manualFolderId = "")
        llm.chatStream(request()).toList()

        val models = server.takeRequest()
        assertEquals("/v1/models", models.path)
        val model = bodyOf(server.takeRequest())["model"]!!.jsonPrimitive.content
        assertEquals("gpt://b1gdiscovered/aliceai-llm/latest", model)

        // Second turn on the SAME client: the cached folder means NO second
        // /models request (the cache is per-instance, in-memory).
        server.enqueue(MockResponse().setResponseCode(200).setBody(PLAIN_STREAM))
        llm.chatStream(request()).toList()
        assertEquals("/v1/responses", server.takeRequest().path)
        assertEquals("discovery must be cached", 3, server.requestCount)
    }

    @Test
    fun `folder discovery failure is typed and never an empty answer`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(500)
                .setBody("""{"title":"Internal","status":500,"detail":"boom"}""")
        )
        val error = runCatching {
            client(manualFolderId = "").chatStream(request()).toList()
        }.exceptionOrNull()

        assertTrue("expected a typed folder failure, got $error", error is YandexFolderResolutionException)
    }

    @Test
    fun `manual folder makes a discovery failure survivable`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.endsWith("/models")) {
                    MockResponse().setResponseCode(500).setBody("nope")
                } else {
                    MockResponse().setResponseCode(200).setBody(PLAIN_STREAM)
                }
        }
        val chunks = client(manualFolderId = "b1gmanual").chatStream(request()).toList()

        assertEquals("Привет, мир!", chunks.filterIsInstance<LlmChunk.Text>().joinToString("") { it.text })
        assertTrue(chunks.last() is LlmChunk.Done)
    }

    @Test
    fun `non-2xx surfaces a typed http exception carrying the rfc7807 detail`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400," +
                    "\"detail\":\"Invalid model URI: x\",\"instance\":\"/v1/responses\"}"
            )
        )
        val error = runCatching { client().chatStream(request()).toList() }.exceptionOrNull()

        assertTrue("expected LlmHttpException, got $error", error is LlmHttpException)
        assertEquals(400, (error as LlmHttpException).code)
        assertTrue("detail must be included", error.message!!.contains("Invalid model URI"))
    }

    @Test
    fun `chatOnce posts non-streaming and extracts top level output_text`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"output_text":"Париж."}"""))
        val answer = client().chatOnce(request(withTool = false))

        assertEquals("Париж.", answer)
        val recorded = server.takeRequest()
        val body = bodyOf(recorded)
        assertFalse(
            "chatOnce must ask for a non-streaming response",
            body["stream"]!!.jsonPrimitive.content.toBoolean(),
        )
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"))
    }

    @Test
    fun `chatOnce falls back to the first output_text part`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture("recorded/yandex/plain.json")))
        val answer = client().chatOnce(request(withTool = false))

        assertEquals("Париж.", answer)
    }

    companion object {
        private const val PLAIN_STREAM =
            "data:{\"type\":\"response.output_text.delta\",\"delta\":\"Привет\"}\n\n" +
                "data:{\"type\":\"response.output_text.delta\",\"delta\":\", мир!\"}\n\n" +
                "data:{\"type\":\"response.completed\"}\n\n"
    }
}
