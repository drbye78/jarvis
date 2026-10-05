package com.jarvis.assistant.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Codec contract for the persisted smart-home provider list. */
class HomeConfigCodecTest {

    private fun config(
        id: String = "cfg-1",
        provider: String = "ha",
        enabled: Boolean = true,
        baseUrl: String = "http://homeassistant.local:8123",
        order: Int = 0,
    ) = HomeProviderConfig(id, provider, enabled, baseUrl, order)

    @Test
    fun `round trip is stable`() {
        val original = listOf(config(), config(id = "cfg-2", provider = "tuya", order = 1))
        val decoded = HomeConfigCodec.decode(HomeConfigCodec.encode(original))
        assertTrue(decoded is HomeConfigDecodeResult.Ok)
        assertEquals(original, (decoded as HomeConfigDecodeResult.Ok).configs)
        assertEquals(HomeConfigCodec.encode(original), HomeConfigCodec.encode(decoded.configs))
    }

    @Test
    fun `blank input decodes to empty`() {
        assertEquals(HomeConfigDecodeResult.Empty, HomeConfigCodec.decode(null))
        assertEquals(HomeConfigDecodeResult.Empty, HomeConfigCodec.decode(""))
        assertEquals(HomeConfigDecodeResult.Empty, HomeConfigCodec.decode("  \n"))
    }

    @Test
    fun `malformed input degrades to invalid without throwing`() {
        val malformed = listOf("{not json", "[{\"id\":}]", "42", "\"a string\"")
        for (raw in malformed) {
            val decoded = HomeConfigCodec.decode(raw)
            assertTrue("expected Invalid for <$raw>", decoded is HomeConfigDecodeResult.Invalid)
            assertTrue((decoded as HomeConfigDecodeResult.Invalid).reason.isNotBlank())
        }
    }

    @Test
    fun `unknown fields are ignored and defaults applied`() {
        val decoded = HomeConfigCodec.decode("""[{"id":"only-id","provider":"ha","future":true}]""")
        assertTrue(decoded is HomeConfigDecodeResult.Ok)
        val cfg = (decoded as HomeConfigDecodeResult.Ok).configs.single()
        assertEquals("only-id", cfg.id)
        assertEquals("ha", cfg.provider)
        assertTrue(cfg.enabled)
        assertEquals("", cfg.baseUrl)
        assertEquals(0, cfg.order)
    }

    @Test
    fun `a missing required provider is an honest invalid`() {
        assertTrue(HomeConfigCodec.decode("""[{"id":"only-id"}]""") is HomeConfigDecodeResult.Invalid)
    }

    @Test
    fun `create generates a stable id and appends order`() {
        val first = HomeProviderConfig.create(HomeProviderId.HOME_ASSISTANT, "http://ha.local:8123")
        val second = HomeProviderConfig.create(HomeProviderId.TUYA, "", existing = listOf(first))
        assertNotEquals(first.id, second.id)
        assertEquals(0, first.order)
        assertEquals("ha", first.provider)
        assertEquals(1, second.order)
        assertEquals(6, HomeProviderConfig.nextOrder(listOf(second, config(order = 5))))
    }
}
