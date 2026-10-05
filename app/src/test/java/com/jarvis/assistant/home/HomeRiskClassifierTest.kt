package com.jarvis.assistant.home

import com.jarvis.assistant.home.providers.ha.HaCapabilityMapper
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Security-critical classifier contract: the tier boundary must not degrade to
 * a constant. Each ecosystem-shaped fixture is asserted to land in the same
 * tier, so a mapper change cannot silently weaken the gate.
 */
class HomeRiskClassifierTest {

    private val ha = HaCapabilityMapper()

    private fun tier(
        verb: ActionVerb,
        kind: DeviceKind = DeviceKind.LIGHT,
        capability: Capability = Capability.ON_OFF,
        level: Double? = null,
        provider: HomeProviderId = HomeProviderId.HOME_ASSISTANT,
    ): HomeTier {
        val key = HomeDeviceKey(provider, "dev")
        return HomeRiskClassifier.classify(HomeAction(key, kind, capability, verb, level))
    }

    @Test
    fun `read is T0`() {
        assertEquals(HomeTier.T0_READ, tier(ActionVerb.READ))
        assertEquals(HomeTier.T0_READ, tier(ActionVerb.READ, DeviceKind.SENSOR, Capability.SENSOR))
        assertEquals(HomeTier.T0_READ, tier(ActionVerb.READ, DeviceKind.SENSOR, Capability.TEMPERATURE))
    }

    @Test
    fun `mapped reversible writes are T1`() {
        assertEquals(HomeTier.T1_REVERSIBLE, tier(ActionVerb.TURN_ON))
        assertEquals(HomeTier.T1_REVERSIBLE, tier(ActionVerb.SET_LEVEL, DeviceKind.LIGHT, Capability.BRIGHTNESS))
        assertEquals(HomeTier.T1_REVERSIBLE, tier(ActionVerb.TURN_OFF, DeviceKind.SOCKET))
        assertEquals(HomeTier.T1_REVERSIBLE, tier(ActionVerb.SET_FAN_SPEED, DeviceKind.FAN, Capability.FAN_SPEED))
    }

    @Test
    fun `a lock write is T2 even for a turn_on-shaped verb`() {
        assertEquals(HomeTier.T2_CRITICAL, tier(ActionVerb.TURN_ON, DeviceKind.LOCK, Capability.LOCK))
        assertEquals(HomeTier.T2_CRITICAL, tier(ActionVerb.LOCK, DeviceKind.LOCK, Capability.LOCK))
    }

    @Test
    fun `unlock open and close verbs are always T2`() {
        assertEquals(HomeTier.T2_CRITICAL, tier(ActionVerb.UNLOCK, DeviceKind.LOCK, Capability.LOCK))
        assertEquals(HomeTier.T2_CRITICAL, tier(ActionVerb.OPEN, DeviceKind.COVER_BLIND, Capability.COVER_POSITION))
        assertEquals(HomeTier.T2_CRITICAL, tier(ActionVerb.CLOSE, DeviceKind.COVER_BLIND, Capability.COVER_POSITION))
    }

    @Test
    fun `critical kinds are T2 for any write`() {
        val critical = listOf(
            DeviceKind.ALARM_PANEL,
            DeviceKind.COVER_GARAGE,
            DeviceKind.COVER_DOOR,
            DeviceKind.APPLIANCE_COOKING,
            DeviceKind.WATER_HEATER,
        )
        for (kind in critical) {
            val msg = "kind $kind must be T2"
            assertEquals(msg, HomeTier.T2_CRITICAL, tier(ActionVerb.TURN_ON, kind))
        }
    }

    @Test
    fun `a target temperature outside the safe band or absent is T2`() {
        val absent = tier(ActionVerb.SET_TEMPERATURE, DeviceKind.CLIMATE, Capability.TARGET_TEMPERATURE)
        val tooHot = tier(ActionVerb.SET_TEMPERATURE, DeviceKind.CLIMATE, Capability.TARGET_TEMPERATURE, 100.0)
        val tooCold = tier(ActionVerb.SET_TEMPERATURE, DeviceKind.CLIMATE, Capability.TARGET_TEMPERATURE, 2.0)
        assertEquals(HomeTier.T2_CRITICAL, absent)
        assertEquals(HomeTier.T2_CRITICAL, tooHot)
        assertEquals(HomeTier.T2_CRITICAL, tooCold)
    }

    @Test
    fun `a target temperature inside the safe band is T1`() {
        assertEquals(HomeTier.T1_REVERSIBLE, safeSetpoint(DeviceKind.CLIMATE, 22.0))
        assertEquals(HomeTier.T1_REVERSIBLE, safeSetpoint(DeviceKind.THERMOSTAT, HomeRiskClassifier.SAFE_MIN_C))
        assertEquals(HomeTier.T1_REVERSIBLE, safeSetpoint(DeviceKind.THERMOSTAT, HomeRiskClassifier.SAFE_MAX_C))
    }

    private fun safeSetpoint(kind: DeviceKind, level: Double): HomeTier =
        tier(ActionVerb.SET_TEMPERATURE, kind, Capability.TARGET_TEMPERATURE, level)

    @Test
    fun `an unknown kind or capability fails closed to T2`() {
        assertEquals(HomeTier.T2_CRITICAL, tier(ActionVerb.TURN_ON, DeviceKind.UNKNOWN, Capability.ON_OFF))
        assertEquals(HomeTier.T2_CRITICAL, tier(ActionVerb.TURN_ON, DeviceKind.LIGHT, Capability.UNKNOWN))
    }

    @Test
    fun `an HA lock dot lock fixture is T2 while light dot turn_on is T1`() {
        val lock = tier(ha.verbForService("lock.lock")!!, ha.deviceKind("lock"), ha.capability("lock", null))
        assertEquals(HomeTier.T2_CRITICAL, lock)
        val light = tier(ha.verbForService("light.turn_on")!!, ha.deviceKind("light"), ha.capability("light", null))
        assertEquals(HomeTier.T1_REVERSIBLE, light)
    }

    @Test
    fun `yandex and tuya shaped capabilities get the same tiers`() {
        assertEquals(HomeTier.T1_REVERSIBLE, tier(ActionVerb.TURN_ON, provider = HomeProviderId.YANDEX))
        val tuyaUnlock = tier(
            verb = ActionVerb.UNLOCK,
            kind = DeviceKind.LOCK,
            capability = Capability.LOCK,
            provider = HomeProviderId.TUYA,
        )
        assertEquals(HomeTier.T2_CRITICAL, tuyaUnlock)
        val tuyaUnknown = tier(
            verb = ActionVerb.TURN_ON,
            kind = DeviceKind.UNKNOWN,
            capability = Capability.UNKNOWN,
            provider = HomeProviderId.TUYA,
        )
        assertEquals(HomeTier.T2_CRITICAL, tuyaUnknown)
    }
}
