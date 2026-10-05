package com.jarvis.assistant.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Curated-entity persistence: stable wire values and a tolerant decode. */
class HomeEntityCodecTest {

    private val washer = HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "binary_sensor.washer")
    private val door = HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "binary_sensor.front_door")

    @Test
    fun `round trip preserves the curated set via wire values`() {
        val set = linkedSetOf(washer, door)
        val encoded = HomeEntityCodec.encode(set)
        assertTrue(encoded.contains("ha:binary_sensor.washer"))
        assertEquals(set, HomeEntityCodec.decodeOrEmpty(encoded))
    }

    @Test
    fun `encode deduplicates`() {
        assertEquals(1, HomeEntityCodec.decodeOrEmpty(HomeEntityCodec.encode(listOf(washer, washer))).size)
    }

    @Test
    fun `decode never throws`() {
        assertEquals(emptySet<HomeDeviceKey>(), HomeEntityCodec.decodeOrEmpty(null))
        assertEquals(emptySet<HomeDeviceKey>(), HomeEntityCodec.decodeOrEmpty("  "))
        assertEquals(emptySet<HomeDeviceKey>(), HomeEntityCodec.decodeOrEmpty("{not json"))
    }

    @Test
    fun `unparseable entries are skipped`() {
        val raw = """["ha:binary_sensor.washer","nocolon","bogus:x",""]"""
        assertEquals(setOf(washer), HomeEntityCodec.decodeOrEmpty(raw))
    }
}
