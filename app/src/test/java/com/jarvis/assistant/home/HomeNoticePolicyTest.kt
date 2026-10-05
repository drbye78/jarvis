package com.jarvis.assistant.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Content-free transition classification + debounce state. */
class HomeNoticePolicyTest {

    private val key = HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "dev")

    private fun state(atMs: Long, vararg values: Pair<Capability, HomeValue>) =
        HomeState(key, values.toMap(), emptyMap(), atMs)

    @Test
    fun `a sensor trip classifies`() {
        val old = state(0, Capability.SENSOR to HomeValue.Bool(false))
        val new = state(1, Capability.SENSOR to HomeValue.Bool(true))
        assertEquals(TransitionClass.SENSOR_TRIPPED, HomeNoticePolicy.classify(DeviceKind.SENSOR, old, new))
    }

    @Test
    fun `a lock change classifies`() {
        val old = state(0, Capability.LOCK to HomeValue.Bool(true))
        val new = state(1, Capability.LOCK to HomeValue.Bool(false))
        assertEquals(TransitionClass.LOCK_CHANGED, HomeNoticePolicy.classify(DeviceKind.LOCK, old, new))
    }

    @Test
    fun `a no-op change returns null`() {
        val old = state(0, Capability.SENSOR to HomeValue.Bool(true))
        val new = state(1, Capability.SENSOR to HomeValue.Bool(true))
        assertNull(HomeNoticePolicy.classify(DeviceKind.SENSOR, old, new))
    }

    @Test
    fun `a light turning off is not an appliance completion`() {
        val old = state(0, Capability.ON_OFF to HomeValue.Bool(true))
        val new = state(1, Capability.ON_OFF to HomeValue.Bool(false))
        assertNull(HomeNoticePolicy.classify(DeviceKind.LIGHT, old, new))
    }

    @Test
    fun `a cooking appliance finishing classifies`() {
        val old = state(0, Capability.ON_OFF to HomeValue.Bool(true))
        val new = state(1, Capability.ON_OFF to HomeValue.Bool(false))
        assertEquals(TransitionClass.APPLIANCE_DONE, HomeNoticePolicy.classify(DeviceKind.APPLIANCE_COOKING, old, new))
    }

    @Test
    fun `a cover opening classifies and a door relabels`() {
        val old = state(0, Capability.COVER_POSITION to HomeValue.Level(0.0))
        val new = state(1, Capability.COVER_POSITION to HomeValue.Level(100.0))
        assertEquals(TransitionClass.COVER_OPENED, HomeNoticePolicy.classify(DeviceKind.COVER_BLIND, old, new))
        assertEquals(TransitionClass.DOOR_OPENED, HomeNoticePolicy.classify(DeviceKind.COVER_DOOR, old, new))
    }

    @Test
    fun `debounce suppresses repeats inside the cooldown`() {
        val debouncer = HomeNoticeDebouncer(cooldownMs = 1_000)
        val notice = HomeNotice(key, DeviceKind.SENSOR, TransitionClass.SENSOR_TRIPPED, 5_000)
        assertTrue(debouncer.accept(notice, nowMs = 5_000))
        assertFalse(debouncer.accept(notice, nowMs = 5_500))
        assertTrue(debouncer.accept(notice, nowMs = 6_000))
    }

    @Test
    fun `interesting tracks exactly the notifiable kinds`() {
        assertTrue(HomeNoticePolicy.interesting(DeviceKind.SENSOR))
        assertTrue(HomeNoticePolicy.interesting(DeviceKind.APPLIANCE_COOKING))
        assertFalse(HomeNoticePolicy.interesting(DeviceKind.LIGHT))
        assertFalse(HomeNoticePolicy.interesting(DeviceKind.CLIMATE))
    }
}
