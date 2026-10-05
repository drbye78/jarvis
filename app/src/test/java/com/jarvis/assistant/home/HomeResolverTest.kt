package com.jarvis.assistant.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic, fail-closed resolution rules for [HomeResolver]. */
class HomeResolverTest {

    private val lightKitchen = device("light.kitchen", "Потолочный светильник", "Кухня", DeviceKind.LIGHT)
    private val lightLiving = device("light.living", "Свет в гостиной", "Гостиная", DeviceKind.LIGHT)
    private val climate = device("climate.living", "Кондиционер", "Гостиная", DeviceKind.CLIMATE)
    private val lock = device("lock.front", "Замок входной", "Прихожая", DeviceKind.LOCK)
    private val blind = device("cover.living", "Шторы гостиной", "Гостиная", DeviceKind.COVER_BLIND)
    private val namedLamp = device("light.named", "Лампочка", null, DeviceKind.LIGHT)

    private val devices = listOf(lightKitchen, lightLiving, climate, lock, blind, namedLamp)

    private fun device(nativeId: String, name: String, room: String?, kind: DeviceKind) = HomeDevice(
        key = HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, nativeId),
        name = name,
        room = room,
        kind = kind,
        capabilities = setOf(Capability.ON_OFF),
        verbs = setOf(ActionVerb.TURN_ON, ActionVerb.TURN_OFF),
    )

    private fun resolve(query: String, mode: ResolveMode = ResolveMode.CONTROL) =
        HomeResolver.resolve(query, devices, emptyMap(), emptySet(), mode)

    @Test
    fun `room plus kind composition resolves uniquely`() {
        assertEquals(HomeResolution.Unique(lightKitchen), resolve("свет на кухне"))
    }

    @Test
    fun `bare kind word resolves uniquely`() {
        assertEquals(HomeResolution.Unique(climate), resolve("кондиционер"))
    }

    @Test
    fun `alias beats exact normalized name`() {
        val aliases = mapOf("лампочка" to lightKitchen.key)
        val result = HomeResolver.resolve("лампочка", devices, aliases, emptySet(), ResolveMode.CONTROL)
        assertEquals(HomeResolution.Unique(lightKitchen), result)
    }

    @Test
    fun `exact normalized name resolves`() {
        assertEquals(HomeResolution.Unique(lightKitchen), resolve("  потолочный   светильник "))
    }

    @Test
    fun `ambiguity in CONTROL is surfaced not guessed`() {
        val second = device("light.kitchen2", "Настенный свет", "Кухня", DeviceKind.LIGHT)
        val result = HomeResolver.resolve(
            "свет на кухне",
            devices + second,
            emptyMap(),
            emptySet(),
            ResolveMode.CONTROL,
        )
        assertTrue(result is HomeResolution.Ambiguous)
        assertEquals(2, (result as HomeResolution.Ambiguous).candidates.size)
    }

    @Test
    fun `READ may surface a fuzzy substring match while CONTROL fails closed`() {
        assertEquals(HomeResolution.NotFound, resolve("входной", ResolveMode.CONTROL))
        assertEquals(HomeResolution.Unique(lock), resolve("входной", ResolveMode.READ))
    }

    @Test
    fun `unknown query is NotFound in both modes`() {
        assertEquals(HomeResolution.NotFound, resolve("нет такого устройства", ResolveMode.CONTROL))
        assertEquals(HomeResolution.NotFound, resolve("нет такого устройства", ResolveMode.READ))
    }

    @Test
    fun `blank query is NotFound`() {
        assertEquals(HomeResolution.NotFound, resolve("   "))
    }

    @Test
    fun `seed aliases only unique device names`() {
        val seeded = HomeAlias.seed(listOf(lightKitchen, lightLiving, namedLamp))
        assertEquals(lightKitchen.key, seeded["потолочный светильник"])
        assertEquals(lightLiving.key, seeded["свет в гостиной"])
        assertEquals(namedLamp.key, seeded["лампочка"])
    }
}
