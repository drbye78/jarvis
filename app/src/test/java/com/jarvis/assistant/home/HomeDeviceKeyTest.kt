package com.jarvis.assistant.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Wire form + tolerant provider parsing for [HomeDeviceKey]. */
class HomeDeviceKeyTest {

    @Test
    fun `wire form uses the stable provider id`() {
        val key = HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "light.kitchen")
        assertEquals("ha:light.kitchen", key.wire)
        assertEquals("yandex:lamp-1", HomeDeviceKey(HomeProviderId.YANDEX, "lamp-1").wire)
    }

    @Test
    fun `parse round trips every provider`() {
        for (provider in HomeProviderId.entries) {
            val key = HomeDeviceKey(provider, "native.42")
            assertEquals(key, HomeDeviceKey.parseOrNull(key.wire))
        }
    }

    @Test
    fun `parse keeps colons inside the native id`() {
        assertEquals(
            HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "sensor:temp"),
            HomeDeviceKey.parseOrNull("ha:sensor:temp"),
        )
    }

    @Test
    fun `parse tolerates surrounding whitespace and case in the provider`() {
        assertEquals(
            HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "light.kitchen"),
            HomeDeviceKey.parseOrNull("  HA:light.kitchen  "),
        )
    }

    @Test
    fun `parse returns null on garbage`() {
        val garbage = listOf<String?>(null, "", "   ", ":light", "ha:", "bogus:light", "nocolon", ":")
        for (raw in garbage) {
            assertNull("expected null for <$raw>", HomeDeviceKey.parseOrNull(raw))
        }
    }

    @Test
    fun `fromId parses known ids and rejects unknown ones`() {
        assertEquals(HomeProviderId.HOME_ASSISTANT, HomeProviderId.fromId("ha"))
        assertEquals(HomeProviderId.YANDEX, HomeProviderId.fromId("YANDEX"))
        assertEquals(HomeProviderId.TUYA, HomeProviderId.fromId(" tuya "))
        assertNull(HomeProviderId.fromId("homekit"))
        assertNull(HomeProviderId.fromId(null))
    }
}
