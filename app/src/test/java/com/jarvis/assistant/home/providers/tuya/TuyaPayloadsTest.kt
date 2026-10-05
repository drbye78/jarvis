package com.jarvis.assistant.home.providers.tuya

import com.jarvis.assistant.home.HomeError
import com.jarvis.assistant.home.HomeResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Envelope, token, device-field and status parsing — total, never throws. */
class TuyaPayloadsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a successful envelope yields result`() {
        val body = """{"success":true,"code":0,"result":{"access_token":"abc","expire_time":7200}}"""
        val result = TuyaPayloads.result(body, json)
        assertTrue(result is HomeResult.Ok)
        val element = (result as HomeResult.Ok).value
        assertEquals("abc", TuyaPayloads.accessToken(element))
        assertEquals(7200L, TuyaPayloads.expireSeconds(element))
    }

    @Test
    fun `a failed envelope maps error codes`() {
        assertEquals(HomeError.AUTH, failure(1010))
        assertEquals(HomeError.AUTH, failure(1011))
        assertEquals(HomeError.UNSUPPORTED, failure(1106))
        assertEquals(HomeError.UNSUPPORTED, failure(1004))
        assertEquals(HomeError.UNREACHABLE, failure(2008))
        assertEquals(HomeError.FAILED, failure(9999))
    }

    @Test
    fun `malformed json is a typed failure`() {
        assertTrue(TuyaPayloads.result("{not json", json) is HomeResult.Err)
    }

    @Test
    fun `device fields parse with fallbacks`() {
        val device = json.parseToJsonElement(
            """{"id":"dev1","name":"Лампа","category":"dj","product_name":"Bulb"}""",
        ).let { it as kotlinx.serialization.json.JsonObject }
        assertEquals("dev1", TuyaPayloads.deviceId(device))
        assertEquals("Лампа", TuyaPayloads.deviceName(device))
        assertEquals("dj", TuyaPayloads.category(device))
    }

    @Test
    fun `device name falls back to product name`() {
        val device = json.parseToJsonElement("""{"id":"d","product_name":"Bulb"}""")
            .let { it as kotlinx.serialization.json.JsonObject }
        assertEquals("Bulb", TuyaPayloads.deviceName(device))
    }

    @Test
    fun `status map reads code to value pairs`() {
        val obj = json.parseToJsonElement(
            """{"status":[{"code":"switch_led","value":true},{"code":"bright_value","value":500}]}""",
        ).let { it as kotlinx.serialization.json.JsonObject }
        val status = TuyaPayloads.statusMap(obj)
        assertEquals(JsonPrimitive(true), status["switch_led"])
        assertEquals(JsonPrimitive(500), status["bright_value"])
    }

    @Test
    fun `function codes read the specification`() {
        val spec = json.parseToJsonElement(
            """{"functions":[{"code":"switch_led"},{"code":"bright_value"}],"status":[]}""",
        )
        assertEquals(setOf("switch_led", "bright_value"), TuyaPayloads.functionCodes(spec))
    }

    private fun failure(code: Int): HomeError {
        val body = """{"success":false,"code":$code,"msg":"x"}"""
        return (TuyaPayloads.result(body, json) as HomeResult.Err).error
    }
}
