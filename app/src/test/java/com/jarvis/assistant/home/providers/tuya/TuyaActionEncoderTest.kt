package com.jarvis.assistant.home.providers.tuya

import com.jarvis.assistant.home.ActionVerb
import com.jarvis.assistant.home.Capability
import com.jarvis.assistant.home.DeviceKind
import com.jarvis.assistant.home.HomeAction
import com.jarvis.assistant.home.HomeDeviceKey
import com.jarvis.assistant.home.HomeProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Action → Tuya command, with the device's own codes and the lock refusal. */
class TuyaActionEncoderTest {

    private val key = HomeDeviceKey(HomeProviderId.TUYA, "dev1")
    private val lightFunctions = setOf("switch_led", "bright_value_v2", "temp_value")
    private val lockFunctions = setOf("lock_motor_state", "unlock_switch", "beep")

    private fun action(
        verb: ActionVerb,
        capability: Capability,
        kind: DeviceKind,
        level: Double? = null,
    ) = HomeAction(key, kind, capability, verb, level)

    @Test
    fun `a light on uses switch_led`() {
        val command = TuyaActionEncoder.encode(
            action(ActionVerb.TURN_ON, Capability.ON_OFF, DeviceKind.LIGHT),
            lightFunctions,
        )
        assertEquals(TuyaCommand("switch_led", "true"), command)
    }

    @Test
    fun `an unknown switch code yields no command rather than a invented one`() {
        val command = TuyaActionEncoder.encode(
            action(ActionVerb.TURN_ON, Capability.ON_OFF, DeviceKind.LIGHT),
            setOf("some_custom_code"),
        )
        assertNull(command)
    }

    @Test
    fun `brightness picks v2 and scales onto 0 to 1000`() {
        val command = TuyaActionEncoder.encode(
            action(ActionVerb.SET_LEVEL, Capability.BRIGHTNESS, DeviceKind.LIGHT, level = 50.0),
            lightFunctions,
        )
        assertEquals(TuyaCommand("bright_value_v2", "500"), command)
    }

    @Test
    fun `brightness picks v1 and scales onto 0 to 255`() {
        val command = TuyaActionEncoder.encode(
            action(ActionVerb.SET_LEVEL, Capability.BRIGHTNESS, DeviceKind.LIGHT, level = 100.0),
            setOf("switch", "bright_value"),
        )
        assertEquals(TuyaCommand("bright_value", "255"), command)
    }

    @Test
    fun `a thermostat setpoint uses temp_set`() {
        val command = TuyaActionEncoder.encode(
            action(ActionVerb.SET_TEMPERATURE, Capability.TARGET_TEMPERATURE, DeviceKind.THERMOSTAT, level = 22.0),
            setOf("temp_set", "temp_current"),
        )
        assertEquals(TuyaCommand("temp_set", "22"), command)
    }

    @Test
    fun `a curtain open uses the control enum`() {
        val command = TuyaActionEncoder.encode(
            action(ActionVerb.OPEN, Capability.COVER_POSITION, DeviceKind.COVER_BLIND),
            setOf("control", "percent_control"),
        )
        assertEquals(TuyaCommand("control", "\"open\""), command)
    }

    @Test
    fun `a garage never emits a cover command`() {
        assertNull(
            TuyaActionEncoder.encode(
                action(ActionVerb.OPEN, Capability.COVER_POSITION, DeviceKind.COVER_GARAGE),
                setOf("control"),
            ),
        )
    }

    @Test
    fun `lock and unlock are refused - there is no standard cloud unlock DP`() {
        assertNull(
            TuyaActionEncoder.encode(
                action(ActionVerb.LOCK, Capability.LOCK, DeviceKind.LOCK),
                lockFunctions,
            ),
        )
        assertNull(
            TuyaActionEncoder.encode(
                action(ActionVerb.UNLOCK, Capability.LOCK, DeviceKind.LOCK),
                lockFunctions,
            ),
        )
    }

    @Test
    fun `a read is never a command`() {
        assertNull(
            TuyaActionEncoder.encode(
                action(ActionVerb.READ, Capability.SENSOR, DeviceKind.SENSOR),
                lightFunctions,
            ),
        )
    }
}
