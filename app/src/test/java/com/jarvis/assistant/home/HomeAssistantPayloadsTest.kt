package com.jarvis.assistant.home

import com.jarvis.assistant.home.providers.ha.HaActionEncoder
import com.jarvis.assistant.home.providers.ha.HaPayloads
import com.jarvis.assistant.home.providers.ha.HaStateNormalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure HA payload parsing, state normalization and action encoding. */
class HomeAssistantPayloadsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun array(raw: String) = json.parseToJsonElement(raw).jsonArray

    @Test
    fun `parse states keeps entity id, state and attributes`() {
        val body = """
            [
              {"entity_id":"light.kitchen","state":"on","attributes":{"friendly_name":"Свет","brightness":128}},
              {"entity_id":"sensor.temp","state":"22.5","attributes":{"device_class":"temperature"}},
              {"attributes":{"friendly_name":"no id"}},
              "not an object"
            ]
        """.trimIndent()

        val entities = HaPayloads.parseStates(body, json)

        assertEquals(2, entities.size)
        val light = entities.first()
        assertEquals("light.kitchen", light.entityId)
        assertEquals("light", light.domain)
        assertEquals("kitchen", light.objectId)
        assertEquals("on", light.state)
        assertEquals("Свет", light.attributeText("friendly_name"))
    }

    @Test
    fun `parse states degrades malformed input to empty`() {
        assertTrue(HaPayloads.parseStates("{not json", json).isEmpty())
        assertTrue(HaPayloads.parseStates("{}", json).isEmpty())
    }

    @Test
    fun `room index joins entity device and area`() {
        val entities = array(
            """[
                {"entity_id":"light.kitchen","area_id":null,"device_id":"dev1"},
                {"entity_id":"light.hall","area_id":"hall_area","device_id":null},
                {"entity_id":"light.orphan","area_id":null,"device_id":"dev9"}
            ]""",
        )
        val devices = array("""[{"id":"dev1","area_id":"kitchen_area"}]""")
        val areas = array("""[{"area_id":"kitchen_area","name":"Кухня"},{"id":"hall_area","name":"Прихожая"}]""")

        val rooms = HaPayloads.roomIndex(entities, devices, areas)

        assertEquals("Кухня", rooms["light.kitchen"])
        assertEquals("Прихожая", rooms["light.hall"])
        assertNull(rooms["light.orphan"])
    }

    @Test
    fun `result array accepts only a matching successful frame`() {
        val good = """{"id":2,"type":"result","success":true,"result":[{"id":"x"}]}"""
        assertEquals(1, HaPayloads.resultArray(good, 2, json)?.size)

        assertNull(HaPayloads.resultArray("""{"id":2,"type":"result","success":false,"result":[]}""", 2, json))
        assertNull(HaPayloads.resultArray(good, 3, json))
        assertNull(HaPayloads.resultArray("""{"id":2,"type":"event","result":[]}""", 2, json))
    }

    @Test
    fun `frame type reads the websocket type`() {
        assertEquals("auth_required", HaPayloads.frameType("""{"type":"auth_required"}""", json))
        assertNull(HaPayloads.frameType("[]", json))
    }

    @Test
    fun `action encoder scales brightness into HA range`() {
        val call = HaActionEncoder.encode(
            HomeAction(
                key = HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "light.kitchen"),
                kind = DeviceKind.LIGHT,
                capability = Capability.BRIGHTNESS,
                verb = ActionVerb.SET_LEVEL,
                level = 50.0,
            ),
        )

        assertEquals("light", call?.domain)
        assertEquals("turn_on", call?.service)
        assertEquals("light.kitchen", call?.data?.get("entity_id")?.jsonPrimitive?.content)
        assertEquals(128, call?.data?.get("brightness")?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `action encoder maps lock and cover verbs`() {
        val unlock = HaActionEncoder.encode(
            HomeAction(
                HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "lock.front"),
                DeviceKind.LOCK,
                Capability.LOCK,
                ActionVerb.UNLOCK,
            ),
        )
        assertEquals("lock.unlock", "${unlock?.domain}.${unlock?.service}")

        val position = HaActionEncoder.encode(
            HomeAction(
                HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "cover.blind"),
                DeviceKind.COVER_BLIND,
                Capability.COVER_POSITION,
                ActionVerb.SET_POSITION,
                level = 60.0,
            ),
        )
        assertEquals("cover.set_cover_position", "${position?.domain}.${position?.service}")
        assertEquals(60, position?.data?.get("position")?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `action encoder fails closed on unmappable combinations`() {
        // A color value has no representation in HomeAction.
        assertNull(
            HaActionEncoder.encode(
                HomeAction(
                    HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "light.kitchen"),
                    DeviceKind.LIGHT,
                    Capability.COLOR,
                    ActionVerb.SET_COLOR,
                    level = 10.0,
                ),
            ),
        )
        // An unknown kind has no service domain.
        assertNull(
            HaActionEncoder.encode(
                HomeAction(
                    HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "sensor.temp"),
                    DeviceKind.UNKNOWN,
                    Capability.SENSOR,
                    ActionVerb.TURN_ON,
                ),
            ),
        )
    }

    @Test
    fun `state normalizer scales brightness and reads lock`() {
        val light = entity("""{"entity_id":"light.kitchen","state":"on","attributes":{"brightness":128}}""")
        val brightness = HaStateNormalizer.values(light)[Capability.BRIGHTNESS]

        assertTrue(brightness is HomeValue.Level)
        assertEquals(50.196, (brightness as HomeValue.Level).value, 0.01)
        assertEquals(HomeValue.Bool(true), HaStateNormalizer.values(light)[Capability.ON_OFF])

        val lock = entity("""{"entity_id":"lock.front","state":"locked","attributes":{}}""")
        assertEquals(HomeValue.Bool(true), HaStateNormalizer.values(lock)[Capability.LOCK])

        val temp = entity(
            """{"entity_id":"sensor.temp","state":"22.5","attributes":{"device_class":"temperature"}}""",
        )
        assertEquals(HomeValue.Level(22.5), HaStateNormalizer.values(temp)[Capability.TEMPERATURE])
    }

    private fun entity(raw: String) =
        com.jarvis.assistant.home.providers.ha.HaEntity(
            entityId = json.parseToJsonElement(raw).jsonObject["entity_id"]!!.jsonPrimitive.content,
            state = json.parseToJsonElement(raw).jsonObject["state"]!!.jsonPrimitive.content,
            attributes = json.parseToJsonElement(raw).jsonObject["attributes"]!!.jsonObject,
        )
}
