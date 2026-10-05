package com.jarvis.assistant.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grants editor's structural guarantee: only T1 actions are ever offered,
 * and locks/covers/appliances are excluded by the classifier, not by a hand-kept
 * list.
 */
class HomeGrantablesTest {

    @Test
    fun `a light offers on off and brightness`() {
        val device = device(
            DeviceKind.LIGHT,
            setOf(Capability.ON_OFF, Capability.BRIGHTNESS, Capability.COLOR, Capability.COLOR_TEMP),
        )
        val grants = HomeGrantables.forDevice(device)
        assertTrue(Grantable(Capability.ON_OFF, ActionVerb.TURN_ON) in grants)
        assertTrue(Grantable(Capability.ON_OFF, ActionVerb.TURN_OFF) in grants)
        assertTrue(Grantable(Capability.BRIGHTNESS, ActionVerb.SET_LEVEL) in grants)
        // SET_COLOR is not a fast-path action the tool can build.
        assertFalse(grants.any { it.verb == ActionVerb.SET_COLOR })
    }

    @Test
    fun `a lock offers nothing even though it advertises lock and unlock verbs`() {
        val device = device(DeviceKind.LOCK, setOf(Capability.LOCK))
        assertEquals(emptyList<Grantable>(), HomeGrantables.forDevice(device))
    }

    @Test
    fun `a cover offers nothing because open and close are critical verbs`() {
        val device = device(DeviceKind.COVER_BLIND, setOf(Capability.COVER_POSITION))
        assertEquals(emptyList<Grantable>(), HomeGrantables.forDevice(device))
    }

    @Test
    fun `a garage and a cooking appliance offer nothing`() {
        assertEquals(
            emptyList<Grantable>(),
            HomeGrantables.forDevice(device(DeviceKind.COVER_GARAGE, setOf(Capability.ON_OFF))),
        )
        assertEquals(
            emptyList<Grantable>(),
            HomeGrantables.forDevice(device(DeviceKind.APPLIANCE_COOKING, setOf(Capability.ON_OFF))),
        )
    }

    @Test
    fun `a thermostat offers a safe setpoint but not an out-of-band one`() {
        val device = device(DeviceKind.CLIMATE, setOf(Capability.TARGET_TEMPERATURE))
        val grants = HomeGrantables.forDevice(device)
        assertEquals(listOf(Grantable(Capability.TARGET_TEMPERATURE, ActionVerb.SET_TEMPERATURE)), grants)
    }

    @Test
    fun `an unknown kind offers nothing`() {
        assertEquals(
            emptyList<Grantable>(),
            HomeGrantables.forDevice(device(DeviceKind.UNKNOWN, setOf(Capability.ON_OFF))),
        )
    }

    @Test
    fun `isGrantable matches provider native id capability and verb`() {
        val device = device(DeviceKind.LIGHT, setOf(Capability.ON_OFF))
        assertTrue(
            HomeGrantables.isGrantable(device, HomeGrant("ha", "light.kitchen", "ON_OFF", "TURN_ON")),
        )
        assertTrue(
            HomeGrantables.isGrantable(device, HomeGrant("ha", "light.kitchen", "ON_OFF", "TURN_OFF")),
        )
        // SET_LEVEL is not advertised (no BRIGHTNESS), and the provider disagrees.
        assertFalse(
            HomeGrantables.isGrantable(device, HomeGrant("ha", "light.kitchen", "BRIGHTNESS", "SET_LEVEL")),
        )
        assertFalse(
            HomeGrantables.isGrantable(device, HomeGrant("tuya", "light.kitchen", "ON_OFF", "TURN_ON")),
        )
    }

    private fun device(kind: DeviceKind, capabilities: Set<Capability>) = HomeDevice(
        key = HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "light.kitchen"),
        name = "Свет",
        room = null,
        kind = kind,
        capabilities = capabilities,
        verbs = emptySet(),
    )
}
