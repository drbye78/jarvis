package com.jarvis.assistant.home

import com.jarvis.assistant.home.providers.ha.HaCapabilityMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixture-driven contract for the pure HA domain/device_class mapper. */
class HaCapabilityMapperTest {

    private val mapper = HaCapabilityMapper()

    @Test
    fun `provider is home assistant`() {
        assertEquals(HomeProviderId.HOME_ASSISTANT, mapper.provider)
    }

    @Test
    fun `domains map to kinds`() {
        assertEquals(DeviceKind.LIGHT, mapper.deviceKind("light"))
        assertEquals(DeviceKind.SWITCH, mapper.deviceKind("switch"))
        assertEquals(DeviceKind.FAN, mapper.deviceKind("fan"))
        assertEquals(DeviceKind.CLIMATE, mapper.deviceKind("climate"))
        assertEquals(DeviceKind.LOCK, mapper.deviceKind("lock"))
        assertEquals(DeviceKind.ALARM_PANEL, mapper.deviceKind("alarm_control_panel"))
        assertEquals(DeviceKind.MEDIA, mapper.deviceKind("media_player"))
        assertEquals(DeviceKind.VACUUM, mapper.deviceKind("vacuum"))
        assertEquals(DeviceKind.WATER_HEATER, mapper.deviceKind("water_heater"))
        assertEquals(DeviceKind.SENSOR, mapper.deviceKind("sensor"))
    }

    @Test
    fun `cover device_class splits blind, garage and door`() {
        assertEquals(DeviceKind.COVER_BLIND, mapper.deviceKind("cover"))
        assertEquals(DeviceKind.COVER_BLIND, mapper.deviceKind("cover", "blind"))
        assertEquals(DeviceKind.COVER_GARAGE, mapper.deviceKind("cover", "garage"))
        assertEquals(DeviceKind.COVER_GARAGE, mapper.deviceKind("cover", "garage_door"))
        assertEquals(DeviceKind.COVER_DOOR, mapper.deviceKind("cover", "door"))
    }

    @Test
    fun `thermostat climate is distinguished`() {
        assertEquals(DeviceKind.THERMOSTAT, mapper.deviceKind("climate", "thermostat"))
        assertEquals(DeviceKind.CLIMATE, mapper.deviceKind("climate", "ac"))
    }

    @Test
    fun `entity ids resolve by their domain`() {
        assertEquals(DeviceKind.LIGHT, mapper.deviceKind("light.kitchen_ceiling"))
        assertEquals(Capability.ON_OFF, mapper.capability("light.kitchen_ceiling", null))
    }

    @Test
    fun `unknown domain is unknown`() {
        assertEquals(DeviceKind.UNKNOWN, mapper.deviceKind("bogus"))
        assertEquals(DeviceKind.UNKNOWN, mapper.deviceKind(""))
        assertEquals(Capability.UNKNOWN, mapper.capability("bogus", null))
    }

    @Test
    fun `capabilities map by domain and attribute`() {
        assertEquals(Capability.ON_OFF, mapper.capability("light", null))
        assertEquals(Capability.BRIGHTNESS, mapper.capability("light", "brightness"))
        assertEquals(Capability.COLOR, mapper.capability("light", "rgb"))
        assertEquals(Capability.COLOR_TEMP, mapper.capability("light", "color_temp"))
        assertEquals(Capability.COVER_POSITION, mapper.capability("cover", "current_position"))
        assertEquals(Capability.LOCK, mapper.capability("lock", null))
    }

    @Test
    fun `climate attributes split current from target temperature`() {
        assertEquals(Capability.TEMPERATURE, mapper.capability("climate", "current_temperature"))
        assertEquals(Capability.TARGET_TEMPERATURE, mapper.capability("climate", "temperature"))
        assertEquals(Capability.MODE, mapper.capability("climate", "hvac_mode"))
        assertEquals(Capability.FAN_SPEED, mapper.capability("climate", "fan_mode"))
    }

    @Test
    fun `sensor device_class temperature is temperature`() {
        assertEquals(Capability.TEMPERATURE, mapper.capability("sensor", "temperature"))
        assertEquals(Capability.SENSOR, mapper.capability("binary_sensor", "door"))
        assertEquals(Capability.SENSOR, mapper.capability("sensor", null))
    }

    @Test
    fun `services map to verbs and accept a domain-qualified form`() {
        assertEquals(ActionVerb.TURN_ON, mapper.verbForService("light.turn_on"))
        assertEquals(ActionVerb.TURN_OFF, mapper.verbForService("turn_off"))
        assertEquals(ActionVerb.LOCK, mapper.verbForService("lock.lock"))
        assertEquals(ActionVerb.UNLOCK, mapper.verbForService("lock.unlock"))
        assertEquals(ActionVerb.OPEN, mapper.verbForService("cover.open_cover"))
        assertEquals(ActionVerb.CLOSE, mapper.verbForService("cover.close_cover"))
        assertEquals(ActionVerb.SET_POSITION, mapper.verbForService("cover.set_cover_position"))
        assertEquals(ActionVerb.SET_TEMPERATURE, mapper.verbForService("climate.set_temperature"))
        assertEquals(ActionVerb.SET_LEVEL, mapper.verbForService("fan.set_percentage"))
    }

    @Test
    fun `unknown service is null`() {
        assertNull(mapper.verbForService("homeassistant.restart"))
        assertNull(mapper.verbForService(""))
    }
}
