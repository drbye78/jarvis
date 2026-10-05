package com.jarvis.assistant.home.providers.ha

import com.jarvis.assistant.home.DeviceKind
import com.jarvis.assistant.home.HomeDeviceKey
import com.jarvis.assistant.home.HomeProviderId
import com.jarvis.assistant.home.HomeState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/**
 * One `state_changed` event decoded from the HA websocket. [old] is null when
 * the frame carried `null` (first sight / a restored entity).
 */
internal data class HaStateChanged(
    val entityId: String,
    val old: HaEntity?,
    val new: HaEntity?,
)

/**
 * Pure parsing of HA websocket EVENT frames (the `subscribe_events` stream).
 * Total and exception-free, exactly like [HaPayloads]: a malformed frame yields
 * null rather than throwing, so one bad frame cannot kill the subscription.
 */
internal object HaEvents {

    private const val EVENT_TYPE = "state_changed"

    /** True when [frame] is a successful `result` for [id] (the subscribe ack). */
    fun resultOk(frame: String, id: Int, json: Json): Boolean {
        val obj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: return false
        if (HaPayloads.rawText(obj["type"]) != "result") return false
        if (HaPayloads.rawText(obj["id"])?.toIntOrNull() != id) return false
        return HaPayloads.rawText(obj["success"])?.toBooleanStrictOrNull() == true
    }

    /** True when [frame] is the `result` failure for [id]. */
    fun resultFailed(frame: String, id: Int, json: Json): Boolean {
        val obj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: return false
        if (HaPayloads.rawText(obj["type"]) != "result") return false
        if (HaPayloads.rawText(obj["id"])?.toIntOrNull() != id) return false
        return HaPayloads.rawText(obj["success"])?.toBooleanStrictOrNull() == false
    }

    /**
     * Decode a `state_changed` EVENT frame into [HaStateChanged], or null when
     * the frame is not that event (including `state_changed` for an unrelated
     * entity with no new state).
     */
    // Guard-clause parser: each `?: return null` rejects one malformed layer,
    // which reads more clearly than a nesting of `if`s.
    @Suppress("ReturnCount")
    fun stateChanged(frame: String, json: Json): HaStateChanged? {
        val obj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: return null
        if (HaPayloads.rawText(obj["type"]) != "event") return null
        val event = runCatching { obj["event"]?.jsonObject }.getOrNull() ?: return null
        if (HaPayloads.rawText(event["event_type"]) != EVENT_TYPE) return null
        val data = runCatching { event["data"]?.jsonObject }.getOrNull() ?: return null
        val entityId = HaPayloads.rawText(data["entity_id"])?.takeIf { it.isNotBlank() } ?: return null
        return HaStateChanged(
            entityId = entityId,
            old = entityOrNull(data["old_state"]),
            new = entityOrNull(data["new_state"]),
        )
    }

    private fun entityOrNull(element: JsonElement?): HaEntity? {
        if (element == null) return null
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return null
        val id = HaPayloads.rawText(obj["entity_id"])?.takeIf { it.isNotBlank() } ?: return null
        val attributes = runCatching { obj["attributes"]?.jsonObject }.getOrNull().orEmpty()
        return HaEntity(entityId = id, state = HaPayloads.rawText(obj["state"]).orEmpty(), attributes = attributes)
    }

    /** The normalized state for [entity], or null when the frame carried none. */
    fun toState(key: HomeDeviceKey, entity: HaEntity?, atMs: Long): HomeState? =
        entity?.let { HomeState(key, HaStateNormalizer.values(it), it.rawAttributes(), atMs) }

    /** entity_id → device kind via the shared mapper. */
    fun kindOf(entity: HaEntity, mapper: HaCapabilityMapper): DeviceKind =
        mapper.deviceKind(entity.domain, entity.attributeText("device_class"))

    /** Build a key for an entity id. */
    fun keyOf(entityId: String): HomeDeviceKey =
        HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, entityId)
}
