package com.jarvis.assistant.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exact-tuple grant matching and a tolerant, throw-free codec. */
class HomeGrantStoreTest {

    private val key = HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "light.kitchen")

    private fun action(
        capability: Capability = Capability.ON_OFF,
        verb: ActionVerb = ActionVerb.TURN_ON,
        device: HomeDeviceKey = key,
    ) = HomeAction(device, DeviceKind.LIGHT, capability, verb)

    private val turnOnGrant = HomeGrant(
        provider = "ha",
        nativeId = "light.kitchen",
        capability = Capability.ON_OFF.name,
        verb = ActionVerb.TURN_ON.name,
    )

    @Test
    fun `allows the exact granted tuple`() {
        val store = HomeGrantStore(listOf(turnOnGrant))
        assertTrue(store.allows(action()))
    }

    @Test
    fun `denies a different capability, verb, device or provider`() {
        val store = HomeGrantStore(listOf(turnOnGrant))
        assertFalse(store.allows(action(capability = Capability.BRIGHTNESS)))
        assertFalse(store.allows(action(verb = ActionVerb.TURN_OFF)))
        assertFalse(store.allows(action(device = HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "light.other"))))
        assertFalse(store.allows(action(device = HomeDeviceKey(HomeProviderId.TUYA, "light.kitchen"))))
    }

    @Test
    fun `empty store denies everything`() {
        assertFalse(HomeGrantStore().allows(action()))
    }

    @Test
    fun `of builds the exact grant for an action`() {
        assertEquals(turnOnGrant, HomeGrant.of(action()))
    }

    @Test
    fun `round trip preserves grants`() {
        val grants = listOf(turnOnGrant, HomeGrant("tuya", "plug-1", "ON_OFF", "TURN_OFF"))
        val decoded = HomeGrantCodec.decodeOrEmpty(HomeGrantCodec.encode(grants))
        assertEquals(grants, decoded)
    }

    @Test
    fun `decodeOrEmpty never throws`() {
        assertEquals(emptyList<HomeGrant>(), HomeGrantCodec.decodeOrEmpty(null))
        assertEquals(emptyList<HomeGrant>(), HomeGrantCodec.decodeOrEmpty("   "))
        assertEquals(emptyList<HomeGrant>(), HomeGrantCodec.decodeOrEmpty("[{\"provider\":}]"))
        assertEquals(emptyList<HomeGrant>(), HomeGrantCodec.decodeOrEmpty("42"))
        assertEquals(emptyList<HomeGrant>(), HomeGrantCodec.decodeOrEmpty("{\"a\":1}"))
    }

    @Test
    fun `decode ignores unknown fields and fills defaults`() {
        val raw = """[{"provider":"ha","nativeId":"x","capability":"ON_OFF","verb":"READ","future":1}]"""
        val grant = HomeGrantCodec.decodeOrEmpty(raw).single()
        assertEquals(HomeGrant("ha", "x", "ON_OFF", "READ"), grant)
    }
}
