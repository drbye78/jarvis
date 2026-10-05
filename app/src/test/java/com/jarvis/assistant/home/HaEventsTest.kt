package com.jarvis.assistant.home

import com.jarvis.assistant.home.providers.ha.HaCapabilityMapper
import com.jarvis.assistant.home.providers.ha.HaEntity
import com.jarvis.assistant.home.providers.ha.HaEvents
import com.jarvis.assistant.home.providers.ha.HaWs
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure parsing of the HA `subscribe_events` frames and the shared handshake
 * helpers — no socket, no coroutines.
 */
class HaEventsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun stateFrame(
        entityId: String,
        oldState: String?,
        newState: String?,
        attributes: String = """{"device_class":"door"}""",
    ): String {
        fun state(value: String?) = if (value == null) {
            "null"
        } else {
            """{"entity_id":"$entityId","state":"$value","attributes":$attributes}"""
        }
        return """{"id":1,"type":"event","event":{"event_type":"state_changed","data":{
            "entity_id":"$entityId","old_state":${state(oldState)},"new_state":${state(newState)}
        }}}"""
    }

    @Test
    fun `a state_changed event decodes`() {
        val changed = HaEvents.stateChanged(stateFrame("binary_sensor.washer", "off", "on"), json)
        requireNotNull(changed)
        assertEquals("binary_sensor.washer", changed.entityId)
        assertEquals("off", changed.old?.state)
        assertEquals("on", changed.new?.state)
    }

    @Test
    fun `a null old state is tolerated`() {
        val changed = HaEvents.stateChanged(stateFrame("cover.garage", null, "open"), json)
        requireNotNull(changed)
        assertNull(changed.old)
        assertEquals("open", changed.new?.state)
    }

    @Test
    fun `a non-event frame is not a state change`() {
        assertNull(HaEvents.stateChanged("""{"type":"auth_required"}""", json))
        assertNull(HaEvents.stateChanged("{not json", json))
        assertNull(HaEvents.stateChanged("""{"type":"event","event":{"event_type":"other"}}""", json))
    }

    @Test
    fun `the subscribe ack is recognised`() {
        val ok = """{"id":1,"type":"result","success":true,"result":null}"""
        assertTrue(HaEvents.resultOk(ok, 1, json))
        assertFalse(HaEvents.resultFailed(ok, 1, json))
        assertFalse(HaEvents.resultOk(ok, 2, json))
        // A registry-array result (resultArray shape) is not our id-1 ack.
        assertFalse(HaEvents.resultOk("""{"id":2,"type":"result","success":true,"result":[]}""", 1, json))
    }

    @Test
    fun `a failed subscribe result is recognised`() {
        val fail = """{"id":1,"type":"result","success":false,"error":{"code":"x","message":"y"}}"""
        assertTrue(HaEvents.resultFailed(fail, 1, json))
        assertFalse(HaEvents.resultOk(fail, 1, json))
    }

    @Test
    fun `kind and state are derived from the entity`() {
        val entity = HaEntity("cover.garage", "open", mapOf("device_class" to Json.parseToJsonElement("\"garage\"")))
        assertEquals(DeviceKind.COVER_GARAGE, HaEvents.kindOf(entity, HaCapabilityMapper()))
        val state = HaEvents.toState(HaEvents.keyOf("cover.garage"), entity, 7L)
        requireNotNull(state)
        assertEquals(7L, state.atMs)
    }

    @Test
    fun `websocket url is derived only from https or wss`() {
        assertEquals("wss://ha.local/api/websocket", HaWs.webSocketUrl("https://ha.local"))
        assertEquals("wss://ha.local/api/websocket", HaWs.webSocketUrl("https://ha.local/"))
        assertEquals("wss://ha.local/api/websocket", HaWs.webSocketUrl("wss://ha.local"))
        assertNull(HaWs.webSocketUrl("http://ha.local"))
    }
}
