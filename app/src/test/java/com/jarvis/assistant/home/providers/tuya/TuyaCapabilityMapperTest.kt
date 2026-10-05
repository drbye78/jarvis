package com.jarvis.assistant.home.providers.tuya

import com.jarvis.assistant.home.DeviceKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Category → kind and DP-code → capability, with the fail-closed default. */
class TuyaCapabilityMapperTest {

    private val mapper = TuyaCapabilityMapper()

    @Test
    fun `standard categories map`() {
        assertEquals(DeviceKind.LIGHT, mapper.deviceKind("dj"))
        assertEquals(DeviceKind.LIGHT, mapper.deviceKind("dd"))
        assertEquals(DeviceKind.SWITCH, mapper.deviceKind("kg"))
        assertEquals(DeviceKind.SOCKET, mapper.deviceKind("cz"))
        assertEquals(DeviceKind.FAN, mapper.deviceKind("fs"))
        assertEquals(DeviceKind.CLIMATE, mapper.deviceKind("kt"))
        assertEquals(DeviceKind.THERMOSTAT, mapper.deviceKind("wk"))
        assertEquals(DeviceKind.COVER_BLIND, mapper.deviceKind("cl"))
        assertEquals(DeviceKind.LOCK, mapper.deviceKind("ms"))
        assertEquals(DeviceKind.WATER_HEATER, mapper.deviceKind("rs"))
        assertEquals(DeviceKind.VACUUM, mapper.deviceKind("sd"))
    }

    @Test
    fun `sensor categories map to sensor`() {
        listOf("wsdcg", "mcs", "pir", "ywbj", "rqbj").forEach {
            assertEquals("$it must be a sensor", DeviceKind.SENSOR, mapper.deviceKind(it))
        }
    }

    @Test
    fun `an unknown category is UNKNOWN so the classifier fails closed`() {
        assertEquals(DeviceKind.UNKNOWN, mapper.deviceKind("zzz"))
        assertEquals(DeviceKind.UNKNOWN, mapper.deviceKind(null.orEmpty()))
    }

    @Test
    fun `function codes map to capabilities`() {
        assertEquals(com.jarvis.assistant.home.Capability.ON_OFF, mapper.capability("dj", "switch_led"))
        assertEquals(com.jarvis.assistant.home.Capability.ON_OFF, mapper.capability("kg", "switch_1"))
        assertEquals(com.jarvis.assistant.home.Capability.BRIGHTNESS, mapper.capability("dj", "bright_value_v2"))
        assertEquals(com.jarvis.assistant.home.Capability.TARGET_TEMPERATURE, mapper.capability("wk", "temp_set"))
        assertEquals(com.jarvis.assistant.home.Capability.TEMPERATURE, mapper.capability("wk", "temp_current"))
        assertEquals(com.jarvis.assistant.home.Capability.COVER_POSITION, mapper.capability("cl", "percent_control"))
        assertEquals(com.jarvis.assistant.home.Capability.LOCK, mapper.capability("ms", "lock_motor_state"))
        assertEquals(com.jarvis.assistant.home.Capability.SENSOR, mapper.capability("mcs", "doorcontact_state"))
    }

    @Test
    fun `an unknown DP code is UNKNOWN`() {
        assertEquals(com.jarvis.assistant.home.Capability.UNKNOWN, mapper.capability("dj", "some_custom_code"))
    }

    @Test
    fun `color temp vs color split by code prefix`() {
        assertEquals(com.jarvis.assistant.home.Capability.COLOR_TEMP, mapper.capability("dj", "temp_value"))
        assertEquals(com.jarvis.assistant.home.Capability.COLOR, mapper.capability("dj", "colour_data"))
    }
}
