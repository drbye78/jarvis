package com.jarvis.assistant.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Alias-map persistence: stable wire values, tolerant decode, unique-name seeding. */
class HomeAliasCodecTest {

    private val kitchen = HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "light.kitchen")
    private val plug = HomeDeviceKey(HomeProviderId.TUYA, "plug-1")

    @Test
    fun `round trip preserves aliases via the wire form`() {
        val aliases = mapOf("лампочка" to kitchen, "розетка" to plug)
        val encoded = HomeAliasCodec.encode(aliases)
        assertTrue(encoded.contains("ha:light.kitchen"))
        assertTrue(encoded.contains("tuya:plug-1"))
        assertEquals(aliases, HomeAliasCodec.decodeOrEmpty(encoded))
    }

    @Test
    fun `decode never throws`() {
        assertEquals(emptyMap<String, HomeDeviceKey>(), HomeAliasCodec.decodeOrEmpty(null))
        assertEquals(emptyMap<String, HomeDeviceKey>(), HomeAliasCodec.decodeOrEmpty("  "))
        assertEquals(emptyMap<String, HomeDeviceKey>(), HomeAliasCodec.decodeOrEmpty("{not json"))
        assertEquals(emptyMap<String, HomeDeviceKey>(), HomeAliasCodec.decodeOrEmpty("[1,2,3]"))
    }

    @Test
    fun `invalid entries are skipped and keys normalized`() {
        val raw = """{"  ЛАМПОЧКА ":"ha:light.kitchen","broken":"nocolon","bogus":"bogus:x"}"""
        val decoded = HomeAliasCodec.decodeOrEmpty(raw)
        assertEquals(1, decoded.size)
        assertEquals(kitchen, decoded["лампочка"])
    }

    @Test
    fun `seed drops duplicate device names`() {
        val first = device("light.a", "Свет", DeviceKind.LIGHT)
        val second = device("light.b", "Свет", DeviceKind.LIGHT)
        val unique = device("light.c", "Люстра", DeviceKind.LIGHT)
        val seeded = HomeAlias.seed(listOf(first, second, unique))
        assertEquals(1, seeded.size)
        assertEquals(unique.key, seeded["люстра"])
    }

    @Test
    fun `bind replaces a device's previous alias and normalizes the new one`() {
        val bound = HomeAlias.bind(mapOf("старый" to kitchen), "  Люстра ", kitchen)
        assertEquals(setOf("люстра"), bound.keys)
        assertEquals(kitchen, bound["люстра"])
    }

    @Test
    fun `bind with a blank alias clears the binding`() {
        val bound = HomeAlias.bind(mapOf("лампа" to kitchen), "   ", kitchen)
        assertEquals(emptyMap<String, HomeDeviceKey>(), bound)
    }

    @Test
    fun `aliasFor finds the phrase bound to a device`() {
        assertEquals("лампа", HomeAlias.aliasFor(mapOf("лампа" to kitchen, "розетка" to plug), kitchen))
        assertEquals(null, HomeAlias.aliasFor(emptyMap(), kitchen))
    }

    private fun device(nativeId: String, name: String, kind: DeviceKind) = HomeDevice(
        key = HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, nativeId),
        name = name,
        room = null,
        kind = kind,
        capabilities = emptySet(),
        verbs = emptySet(),
    )
}
