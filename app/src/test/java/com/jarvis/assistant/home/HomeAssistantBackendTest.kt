package com.jarvis.assistant.home

import com.jarvis.assistant.home.net.HomeHttpTransport
import com.jarvis.assistant.home.net.HomeWebSocket
import com.jarvis.assistant.home.net.HttpResponse
import com.jarvis.assistant.home.providers.ha.HomeAssistantBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The Home Assistant backend against in-memory transport/websocket fakes — no
 * network, no Android. Pins discovery mapping, the action-encoding table, state
 * normalization, URL policy and the best-effort room enrichment.
 */
class HomeAssistantBackendTest {

    private val baseUrl = "https://ha.local:8123"
    private val token = "secret-token"

    private fun backend(
        transport: HomeHttpTransport,
        webSocket: HomeWebSocket = FakeHomeWebSocket(failConnect = true),
        url: String = baseUrl,
        bearer: String? = token,
    ): HomeAssistantBackend = HomeAssistantBackend(
        transport = transport,
        webSocket = webSocket,
        baseUrl = { url },
        token = { bearer },
        nowMs = { FIXED_NOW },
    )

    @Test
    fun `discovery maps entities to devices and skips unknown domains`() = runTest {
        val transport = FakeHomeHttpTransport { HttpResponse(200, statesBody()) }

        val devices = (backend(transport).discover() as HomeResult.Ok).value

        assertEquals(3, devices.size)
        assertTrue(devices.none { it.key.nativeId == "automation.morning" })

        val light = devices.first { it.key.nativeId == "light.kitchen" }
        assertEquals(DeviceKind.LIGHT, light.kind)
        assertEquals("Свет кухни", light.name)
        assertTrue(light.capabilities.containsAll(LIGHT_CAPABILITIES))
        assertTrue(light.verbs.containsAll(setOf(ActionVerb.TURN_ON, ActionVerb.SET_LEVEL, ActionVerb.SET_COLOR)))

        val sensor = devices.first { it.key.nativeId == "sensor.temp" }
        assertEquals(DeviceKind.SENSOR, sensor.kind)
        assertEquals(setOf(Capability.TEMPERATURE), sensor.capabilities)
        assertEquals(setOf(ActionVerb.READ), sensor.verbs)

        val cover = devices.first { it.key.nativeId == "cover.blind" }
        assertEquals(DeviceKind.COVER_BLIND, cover.kind)
        assertEquals(setOf(Capability.COVER_POSITION), cover.capabilities)
    }

    @Test
    fun `applying brightness posts light turn_on with scaled value`() = runTest {
        val transport = FakeHomeHttpTransport { HttpResponse(200, "[]") }
        val action = HomeAction(
            key = HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "light.kitchen"),
            kind = DeviceKind.LIGHT,
            capability = Capability.BRIGHTNESS,
            verb = ActionVerb.SET_LEVEL,
            level = 50.0,
        )

        val outcome = (backend(transport).apply(action) as HomeResult.Ok).value

        assertTrue(outcome.accepted)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("$baseUrl/api/services/light/turn_on", request.url)
        assertEquals("Bearer $token", request.headers["Authorization"])
        val body = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals("light.kitchen", body["entity_id"]?.jsonPrimitive?.content)
        assertEquals(128, body["brightness"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `applying unlock posts lock unlock`() = runTest {
        val transport = FakeHomeHttpTransport { HttpResponse(200, "[]") }
        val action = HomeAction(
            key = HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "lock.front"),
            kind = DeviceKind.LOCK,
            capability = Capability.LOCK,
            verb = ActionVerb.UNLOCK,
        )

        backend(transport).apply(action)

        assertEquals("$baseUrl/api/services/lock/unlock", transport.requests.single().url)
    }

    @Test
    fun `read state normalizes brightness scale and lock state`() = runTest {
        val body = """
            [
              {"entity_id":"light.kitchen","state":"on","attributes":{"brightness":128}},
              {"entity_id":"lock.front","state":"locked","attributes":{}}
            ]
        """.trimIndent()
        val transport = FakeHomeHttpTransport { HttpResponse(200, body) }
        val keys = listOf(
            HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "light.kitchen"),
            HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "lock.front"),
        )

        val states = (backend(transport).readState(keys) as HomeResult.Ok).value

        val light = states.first { it.key.nativeId == "light.kitchen" }
        assertEquals(50.196, (light.values[Capability.BRIGHTNESS] as HomeValue.Level).value, 0.01)
        assertEquals(HomeValue.Bool(true), light.values[Capability.ON_OFF])
        assertEquals("128", light.raw["brightness"])
        assertEquals(FIXED_NOW, light.atMs)

        val lock = states.first { it.key.nativeId == "lock.front" }
        assertEquals(HomeValue.Bool(true), lock.values[Capability.LOCK])
    }

    @Test
    fun `unmappable action returns unsupported without a request`() = runTest {
        val transport = FakeHomeHttpTransport { HttpResponse(200, "[]") }
        val action = HomeAction(
            key = HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "sensor.temp"),
            kind = DeviceKind.UNKNOWN,
            capability = Capability.SENSOR,
            verb = ActionVerb.TURN_ON,
        )

        val result = backend(transport).apply(action)

        assertEquals(HomeError.UNSUPPORTED, (result as HomeResult.Err).error)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `http 401 maps to auth`() = runTest {
        val transport = FakeHomeHttpTransport { HttpResponse(401, "") }

        val result = backend(transport).discover()

        assertEquals(HomeError.AUTH, (result as HomeResult.Err).error)
    }

    @Test
    fun `cleartext url is rejected before any request`() = runTest {
        val transport = FakeHomeHttpTransport { HttpResponse(200, statesBody()) }

        val result = backend(transport, url = "http://ha.local:8123").discover()

        assertEquals(HomeError.UNSUPPORTED, (result as HomeResult.Err).error)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `blank url is not configured`() = runTest {
        val result = backend(FakeHomeHttpTransport(), url = "").discover()

        assertEquals(HomeError.NOT_CONFIGURED, (result as HomeResult.Err).error)
    }

    @Test
    fun `public ip literal is rejected by the lan policy`() = runTest {
        val result = backend(FakeHomeHttpTransport(), url = "https://8.8.8.8:8123").discover()

        assertEquals(HomeError.UNSUPPORTED, (result as HomeResult.Err).error)
    }

    @Test
    fun `missing token is not configured`() = runTest {
        val result = backend(FakeHomeHttpTransport(), bearer = null).discover()

        assertEquals(HomeError.NOT_CONFIGURED, (result as HomeResult.Err).error)
    }

    @Test
    fun `websocket registry failure still yields discovery`() = runTest {
        val transport = FakeHomeHttpTransport { HttpResponse(200, statesBody()) }

        val devices = (
            backend(transport, webSocket = FakeHomeWebSocket(failConnect = true)).discover() as HomeResult.Ok
            ).value

        assertEquals(3, devices.size)
        assertTrue(devices.all { it.room == null })
    }

    @Test
    fun `websocket registry attaches room names`() = runTest {
        val transport = FakeHomeHttpTransport { HttpResponse(200, statesBody()) }

        val devices = (
            backend(transport, webSocket = FakeHomeWebSocket(rooms = true)).discover() as HomeResult.Ok
            ).value

        assertEquals("Кухня", devices.first { it.key.nativeId == "light.kitchen" }.room)
    }

    private fun statesBody(): String = """
        [
          {"entity_id":"light.kitchen","state":"on","attributes":{"friendly_name":"Свет кухни","brightness":128,"supported_color_modes":["color_temp"]}},
          {"entity_id":"cover.blind","state":"open","attributes":{"friendly_name":"Шторы","current_position":60}},
          {"entity_id":"sensor.temp","state":"22.5","attributes":{"friendly_name":"Температура","device_class":"temperature"}},
          {"entity_id":"automation.morning","state":"on","attributes":{"friendly_name":"Утро"}}
        ]
    """.trimIndent()

    private class FakeHomeHttpTransport(
        private val responder: (Recorded) -> HttpResponse = { HttpResponse(200, "[]") },
    ) : HomeHttpTransport {

        data class Recorded(
            val method: String,
            val url: String,
            val headers: Map<String, String>,
            val body: String?,
        )

        val requests = mutableListOf<Recorded>()

        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
            handle("GET", url, headers, null)

        override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResponse =
            handle("POST", url, headers, body)

        private fun handle(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
            val recorded = Recorded(method, url, headers, body)
            requests += recorded
            return responder(recorded)
        }
    }

    private class FakeHomeWebSocket(
        private val failConnect: Boolean = false,
        private val rooms: Boolean = false,
    ) : HomeWebSocket {

        private val frames = MutableSharedFlow<String>(replay = 32, extraBufferCapacity = 32)

        override suspend fun connect(url: String, headers: Map<String, String>) {
            if (failConnect) throw IOException("websocket connect failed")
            frames.tryEmit("""{"type":"auth_required"}""")
        }

        override fun messages(): Flow<String> = frames.asSharedFlow()

        override suspend fun send(text: String) {
            val forId = Json.parseToJsonElement(text).jsonObject
            val type = forId["type"]?.jsonPrimitive?.content ?: return
            val id = forId["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            when (type) {
                "auth" -> frames.tryEmit("""{"type":"auth_ok"}""")
                "config/entity_registry/list" -> if (rooms) {
                    frames.tryEmit(
                        """{"id":$id,"type":"result","success":true,"result":[
                            {"entity_id":"light.kitchen","area_id":null,"device_id":"dev1"}
                        ]}""",
                    )
                }

                "config/device_registry/list" -> if (rooms) {
                    frames.tryEmit(
                        """{"id":$id,"type":"result","success":true,"result":[{"id":"dev1","area_id":"area1"}]}""",
                    )
                }

                "config/area_registry/list" -> if (rooms) {
                    frames.tryEmit(
                        """{"id":$id,"type":"result","success":true,"result":[{"area_id":"area1","name":"Кухня"}]}""",
                    )
                }
            }
        }

        override suspend fun close() = Unit
    }

    private companion object {
        const val FIXED_NOW = 1_000L
        val LIGHT_CAPABILITIES = setOf(Capability.ON_OFF, Capability.BRIGHTNESS, Capability.COLOR_TEMP)
    }
}
