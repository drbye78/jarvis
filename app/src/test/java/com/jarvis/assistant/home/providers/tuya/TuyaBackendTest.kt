package com.jarvis.assistant.home.providers.tuya

import com.jarvis.assistant.home.ActionVerb
import com.jarvis.assistant.home.Capability
import com.jarvis.assistant.home.DeviceKind
import com.jarvis.assistant.home.HomeAction
import com.jarvis.assistant.home.HomeDeviceKey
import com.jarvis.assistant.home.HomeError
import com.jarvis.assistant.home.HomeProviderId
import com.jarvis.assistant.home.HomeResult
import com.jarvis.assistant.home.net.HomeHttpTransport
import com.jarvis.assistant.home.net.HttpResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Discovery, batch state read and control over a fake Tuya cloud. */
class TuyaBackendTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val devicesBody = """
        {"success":true,"result":[
          {"id":"d1","name":"Лампа","category":"dj","status":[
             {"code":"switch_led","value":true},{"code":"bright_value_v2","value":500}]},
          {"id":"d2","name":"Датчик","category":"mcs","status":[
             {"code":"doorcontact_state","value":false}]},
          {"id":"d3","name":"Неизвестное","category":"zzz","status":[{"code":"x","value":1}]}
        ]}
    """.trimIndent()

    private class FakeTransport(
        private val responder: (url: String, headers: Map<String, String>) -> HttpResponse,
    ) : HomeHttpTransport {
        val requests = mutableListOf<String>()

        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            requests += url
            return responder(url, headers)
        }

        override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            requests += url
            return responder(url, headers)
        }
    }

    private fun backend(transport: FakeTransport, uid: String? = "u1") = TuyaBackend(
        client = TuyaOpenApiClient(
            transport = transport,
            baseUrl = { TuyaRegion.CENTRAL_EUROPE.baseUrl },
            accessId = { "id" },
            secret = { "secret" },
            nowMs = { 1_000L },
            nonce = { "n" },
            json = json,
        ),
        uid = { uid },
        json = json,
        nowMs = { 2_000L },
    )

    private fun cloud(spec: String = """{"functions":[{"code":"switch_led"}]}"""): FakeTransport =
        FakeTransport { url, _ ->
            when {
                url.contains("/token") ->
                    HttpResponse(200, """{"success":true,"result":{"access_token":"tok","expire_time":7200}}""")

                url.contains("/specification") ->
                    HttpResponse(200, """{"success":true,"result":$spec}""")

                url.contains("/commands") -> HttpResponse(200, """{"success":true,"result":true}""")
                url.contains("/devices") -> HttpResponse(200, devicesBody)
                else -> HttpResponse(404, "{}")
            }
        }

    @Test
    fun `discovery lists known devices and drops the unknown category`() = kotlinx.coroutines.test.runTest {
        val result = backend(cloud()).discover()
        assertTrue(result is HomeResult.Ok)
        val devices = (result as HomeResult.Ok).value
        assertEquals(2, devices.size)
        assertEquals(setOf("d1", "d2"), devices.map { it.key.nativeId }.toSet())
    }

    @Test
    fun `a discovered light exposes on off and brightness`() = kotlinx.coroutines.test.runTest {
        val devices = (backend(cloud()).discover() as HomeResult.Ok).value
        val light = devices.first { it.key.nativeId == "d1" }
        assertEquals(DeviceKind.LIGHT, light.kind)
        assertTrue(Capability.ON_OFF in light.capabilities)
        assertTrue(Capability.BRIGHTNESS in light.capabilities)
        assertTrue(ActionVerb.TURN_ON in light.verbs)
    }

    @Test
    fun `state read filters to the requested keys and normalizes`() = kotlinx.coroutines.test.runTest {
        val keys = listOf(HomeDeviceKey(HomeProviderId.TUYA, "d1"))
        val result = backend(cloud()).readState(keys)
        assertTrue(result is HomeResult.Ok)
        val state = (result as HomeResult.Ok).value.single()
        assertEquals("d1", state.key.nativeId)
        assertEquals(com.jarvis.assistant.home.HomeValue.Bool(true), state.values[Capability.ON_OFF])
    }

    @Test
    fun `state read of an unknown key is not found`() = kotlinx.coroutines.test.runTest {
        val keys = listOf(HomeDeviceKey(HomeProviderId.TUYA, "missing"))
        assertEquals(HomeError.NOT_FOUND, (backend(cloud()).readState(keys) as HomeResult.Err).error)
    }

    @Test
    fun `control fetches the device spec then posts a command`() = kotlinx.coroutines.test.runTest {
        val transport = cloud()
        val action = HomeAction(
            key = HomeDeviceKey(HomeProviderId.TUYA, "d1"),
            kind = DeviceKind.LIGHT,
            capability = Capability.ON_OFF,
            verb = ActionVerb.TURN_ON,
        )
        val result = backend(transport).apply(action)
        assertTrue(result is HomeResult.Ok)
        assertTrue(transport.requests.any { it.contains("/specification") })
        assertTrue(transport.requests.any { it.contains("/commands") })
    }

    @Test
    fun `a lock command is refused as unsupported`() = kotlinx.coroutines.test.runTest {
        val transport = cloud(spec = """{"functions":[{"code":"unlock_switch"}]}""")
        val action = HomeAction(
            key = HomeDeviceKey(HomeProviderId.TUYA, "d1"),
            kind = DeviceKind.LOCK,
            capability = Capability.LOCK,
            verb = ActionVerb.UNLOCK,
        )
        assertEquals(HomeError.UNSUPPORTED, (backend(transport).apply(action) as HomeResult.Err).error)
    }

    @Test
    fun `a missing uid is not configured`() = kotlinx.coroutines.test.runTest {
        assertEquals(HomeError.NOT_CONFIGURED, (backend(cloud(), uid = null).discover() as HomeResult.Err).error)
        assertEquals(HomeError.NOT_CONFIGURED, (backend(cloud(), uid = "").discover() as HomeResult.Err).error)
    }

    @Test
    fun `a failed envelope surfaces its mapped error`() = kotlinx.coroutines.test.runTest {
        val transport = FakeTransport { url, _ ->
            if (url.contains("/token")) {
                HttpResponse(200, """{"success":true,"result":{"access_token":"tok"}}""")
            } else {
                HttpResponse(200, """{"success":false,"code":1106,"msg":"permission deny"}""")
            }
        }
        assertEquals(HomeError.UNSUPPORTED, (backend(transport).discover() as HomeResult.Err).error)
    }
}
