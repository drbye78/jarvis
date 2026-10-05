package com.jarvis.assistant.home.providers.tuya

import com.jarvis.assistant.home.Capability
import com.jarvis.assistant.home.HomeValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `status[]` → normalized capability values, including the scale traps. */
class TuyaStateNormalizerTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun status(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
        pairs.associate { (code, raw) -> code to json.parseToJsonElement(raw) }

    @Test
    fun `a switch boolean normalizes`() {
        val values = TuyaStateNormalizer.values(status("switch_led" to "true"))
        assertEquals(HomeValue.Bool(true), values[Capability.ON_OFF])
    }

    @Test
    fun `va_temperature applies scale 1`() {
        val values = TuyaStateNormalizer.values(status("va_temperature" to "235"))
        assertEquals(HomeValue.Level(23.5), values[Capability.TEMPERATURE])
    }

    @Test
    fun `a v2 brightness scales onto 0 to 100`() {
        val values = TuyaStateNormalizer.values(status("bright_value_v2" to "1000"))
        assertEquals(HomeValue.Level(100.0), values[Capability.BRIGHTNESS])
    }

    @Test
    fun `a v1 brightness scales onto 0 to 100`() {
        val values = TuyaStateNormalizer.values(status("bright_value" to "255"))
        assertEquals(HomeValue.Level(100.0), values[Capability.BRIGHTNESS])
    }

    @Test
    fun `a smoke alarm string becomes a trip flag`() {
        assertEquals(
            HomeValue.Bool(true),
            TuyaStateNormalizer.values(status("smoke_sensor_status" to "\"alarm\""))[Capability.SENSOR],
        )
        assertEquals(
            HomeValue.Bool(false),
            TuyaStateNormalizer.values(status("smoke_sensor_status" to "\"normal\""))[Capability.SENSOR],
        )
    }

    @Test
    fun `fahrenheit and humidity are deliberately not mixed in`() {
        val values = TuyaStateNormalizer.values(status("temp_current_f" to "77", "va_humidity" to "450"))
        assertTrue("no misleading temperature from a Fahrenheit reading", Capability.TEMPERATURE !in values)
        assertTrue("humidity must not masquerade as a sensor trip flag", Capability.SENSOR !in values)
    }

    @Test
    fun `cover percent and lock boolean normalize`() {
        val values = TuyaStateNormalizer.values(status("percent_state" to "40", "lock_motor_state" to "true"))
        assertEquals(HomeValue.Level(40.0), values[Capability.COVER_POSITION])
        assertEquals(HomeValue.Bool(true), values[Capability.LOCK])
    }
}
