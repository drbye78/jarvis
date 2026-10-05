package com.jarvis.assistant.home.providers.tuya

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Region parsing and the OpenAPI base URLs (compile-time constants, no SSRF). */
class TuyaRegionTest {

    @Test
    fun `ids round-trip`() {
        TuyaRegion.entries.forEach { assertEquals(it, TuyaRegion.fromId(it.id)) }
    }

    @Test
    fun `unknown or blank falls back to central europe`() {
        assertEquals(TuyaRegion.CENTRAL_EUROPE, TuyaRegion.fromId(null))
        assertEquals(TuyaRegion.CENTRAL_EUROPE, TuyaRegion.fromId("  "))
        assertEquals(TuyaRegion.CENTRAL_EUROPE, TuyaRegion.fromId("atlantis"))
    }

    @Test
    fun `parsing is case and whitespace tolerant`() {
        assertEquals(TuyaRegion.WESTERN_AMERICA, TuyaRegion.fromId(" US "))
    }

    @Test
    fun `every region has an https base url`() {
        TuyaRegion.entries.forEach {
            assertTrue("${it.id} must be https", it.baseUrl.startsWith("https://"))
        }
    }
}
