package com.jarvis.assistant

import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.llm.GigaChatNativeClient
import com.jarvis.assistant.llm.TokenManager
import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.model.LlmChunk
import com.jarvis.assistant.model.Message
import com.jarvis.assistant.model.ToolCall
import com.jarvis.assistant.model.ToolDefinition
import com.jarvis.assistant.util.InMemoryVault
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hermetic wire test for [GigaChatNativeClient]: the REQUEST body we emit must
 * match the LIVE-VERIFIED `/v2` contract (content part arrays,
 * `web_search` + `functions.specifications`, `tool_config`, `model_options`,
 * `user_info`), and the recorded responses must surface as the right
 * [LlmChunk]s. A web-search turn is asserted to be pure text + Done with zero
 * pending tool calls — that is what keeps `TurnRunner` unchanged.
 */
class GigaChatNativeWireTest {

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

    private fun tokenManager(): TokenManager = TokenManager(
        context = null,
        httpClient = OkHttpClient(),
        config = JarvisConfig().copy(oauthEndpoint = server.url("/oauth").toString()),
        vaultOverride = InMemoryVault(),
        credentials = { "client-id" to "client-secret" },
    )

    private fun enqueueOAuth() {
        val expirySeconds = System.currentTimeMillis() / 1000 + 3600
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"access_token":"test-token","expires_at":$expirySeconds}""")
        )
    }

    private fun client(
        webSearch: Boolean = true,
        timezone: String? = "Europe/Moscow",
    ): GigaChatNativeClient = GigaChatNativeClient(
        tokenManager = tokenManager(),
        httpClient = OkHttpClient(),
        endpoint = server.url("/v2/chat/completions").toString(),
        defaultModel = "GigaChat-3-Lightning",
        webSearchEnabled = webSearch,
        timezone = { timezone },
    )

    private fun toolDef(): ToolDefinition = ToolDefinition(
        name = "getWeather",
        description = "Узнать погоду",
        parameters = json.parseToJsonElement(
            """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""
        ).jsonObject,
    )

    private fun request(withTool: Boolean) = ChatRequest(
        messages = listOf(Message.system("you are jarvis"), Message.user("привет")),
        tools = if (withTool) listOf(toolDef()) else emptyList(),
    )

    private fun fixture(resource: String): String =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(resource)) {
            "missing fixture $resource"
        }.bufferedReader().use { it.readText() }

    @Test
    fun `emits the verified native request shape`() = runBlocking {
        enqueueOAuth()
        server.enqueue(MockResponse().setResponseCode(200).setBody(PLAIN_STREAM))
        val chunks = client().chatStream(request(withTool = true)).toList()

        val oauth = server.takeRequest()
        assertEquals("/oauth", oauth.path)
        val chat = server.takeRequest()
        assertEquals("/v2/chat/completions", chat.path)
        assertEquals("Bearer test-token", chat.getHeader("Authorization"))

        val body = json.parseToJsonElement(chat.body.readUtf8()).jsonObject
        assertEquals("GigaChat-3-Lightning", body["model"]!!.jsonPrimitive.content)
        assertTrue(body["stream"]!!.jsonPrimitive.content.toBoolean())

        val messages = body["messages"]!!.jsonArray
        val system = messages[0].jsonObject
        assertEquals("system", system["role"]!!.jsonPrimitive.content)
        assertEquals("you are jarvis", system["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        val user = messages[1].jsonObject
        assertEquals("user", user["role"]!!.jsonPrimitive.content)
        assertEquals("привет", user["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)

        val tools = body["tools"]!!.jsonArray
        assertEquals("web_search plus one functions entry", 2, tools.size)
        assertTrue(tools[0].jsonObject.containsKey("web_search"))
        val specs = tools[1].jsonObject["functions"]!!.jsonObject["specifications"]!!.jsonArray
        assertEquals("getWeather", specs[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(specs[0].jsonObject["parameters"]!!.jsonObject.containsKey("type"))

        assertEquals("auto", body["tool_config"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        val options = body["model_options"]!!.jsonObject
        assertEquals(0.7, options["temperature"]!!.jsonPrimitive.content.toDouble(), 0.0001)
        assertEquals(2048, options["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("Europe/Moscow", body["user_info"]!!.jsonObject["timezone"]!!.jsonPrimitive.content)

        assertEquals("Да", chunks.filterIsInstance<LlmChunk.Text>().joinToString("") { it.text })
        assertTrue(chunks.last() is LlmChunk.Done)
        assertTrue(chunks.none { it is LlmChunk.FunctionCallComplete })
    }

    @Test
    fun `history maps assistant call and tool result to v2 parts`() = runBlocking {
        enqueueOAuth()
        server.enqueue(MockResponse().setResponseCode(200).setBody(PLAIN_STREAM))
        val history = ChatRequest(
            messages = listOf(
                Message.system("sys"),
                Message.user("погода?"),
                Message(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(
                        ToolCall(id = "call_1", function = FunctionCall("getWeather", """{"city":"Москва"}"""))
                    ),
                ),
                Message(role = "tool", content = """{"temp":12}""", name = "getWeather"),
            ),
            tools = listOf(toolDef()),
        )
        client().chatStream(history).toList()

        server.takeRequest() // OAuth
        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val messages = body["messages"]!!.jsonArray

        val assistant = messages[2].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        val callObj = assistant["content"]!!.jsonArray[0].jsonObject["function_call"]!!.jsonObject
        assertEquals("getWeather", callObj["name"]!!.jsonPrimitive.content)
        // Domain stores arguments as a JSON string; /v2 wants an object.
        assertEquals("Москва", callObj["arguments"]!!.jsonObject["city"]!!.jsonPrimitive.content)

        val tool = messages[3].jsonObject
        assertEquals("tool", tool["role"]!!.jsonPrimitive.content)
        val resultObj = tool["content"]!!.jsonArray[0].jsonObject["function_result"]!!.jsonObject
        assertEquals("getWeather", resultObj["name"]!!.jsonPrimitive.content)
        assertEquals("""{"temp":12}""", resultObj["result"]!!.jsonPrimitive.content)
    }

    @Test
    fun `function call response surfaces a complete tool call`() = runBlocking {
        enqueueOAuth()
        val func = fixture("recorded/gigachat/func.json")
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("event: response.message.delta\ndata: $func\n\n")
        )
        val chunks = client().chatStream(request(withTool = true)).toList()

        val call = chunks.filterIsInstance<LlmChunk.FunctionCallComplete>().single()
        assertEquals("getWeather", call.call.function.name)
        assertTrue("arguments must stay a JSON string", call.call.function.arguments.contains("Москва"))
        assertTrue(chunks.last() is LlmChunk.Done)
    }

    @Test
    fun `web search turn is text plus done with zero pending tool calls`() = runBlocking {
        enqueueOAuth()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(fixture("recorded/gigachat/search.sse"))
        )
        val chunks = client().chatStream(request(withTool = false)).toList()

        assertTrue(chunks.filterIsInstance<LlmChunk.Text>().joinToString("") { it.text }.contains("84,3969"))
        assertTrue(
            "server-side web_search never yields a client call",
            chunks.none { it is LlmChunk.FunctionCallComplete },
        )
        assertTrue(chunks.last() is LlmChunk.Done)
    }

    @Test
    fun `web search can be disabled and user_info omitted`() = runBlocking {
        enqueueOAuth()
        server.enqueue(MockResponse().setResponseCode(200).setBody(PLAIN_STREAM))
        client(webSearch = false, timezone = null).chatStream(request(withTool = true)).toList()

        server.takeRequest() // OAuth
        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val tools = body["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertFalse(tools[0].jsonObject.containsKey("web_search"))
        assertFalse("user_info must be omitted without a timezone", body.containsKey("user_info"))
        assertEquals("auto", body["tool_config"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
    }

    companion object {
        private const val PLAIN_STREAM =
            "event: response.message.delta\n" +
                "data: {\"messages\":[{\"role\":\"assistant\",\"content\":[{\"text\":\"Да\"}]}]}\n\n" +
                "event: response.message.done\n" +
                "data: {\"finish_reason\":\"stop\"}\n\n" +
                "data: [DONE]\n\n"
    }
}
