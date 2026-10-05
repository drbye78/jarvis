package com.jarvis.assistant.home.providers.ha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * One Home Assistant state object from `GET /api/states`.
 *
 * [attributes] are the raw JSON attributes. [rawAttributes] renders only the
 * primitive ones as text for [com.jarvis.assistant.home.HomeDevice]/`HomeState`
 * diagnostics — never for a prompt or an INFO+ log.
 */
data class HaEntity(
    val entityId: String,
    val state: String,
    val attributes: Map<String, JsonElement>,
) {
    /** The domain part of `<domain>.<object_id>` (blank when the id is bare). */
    val domain: String get() = entityId.substringBefore('.', "")

    /** The object id part of `<domain>.<object_id>`. */
    val objectId: String get() = entityId.substringAfter('.', entityId)

    fun attributeText(name: String): String? = HaPayloads.rawText(attributes[name])

    fun rawAttributes(): Map<String, String> =
        attributes.mapNotNull { (name, value) -> HaPayloads.rawText(value)?.let { name to it } }.toMap()
}

/**
 * Pure Home Assistant JSON parsing. Total and exception-free: malformed input
 * degrades to an empty result rather than throwing, so a broken payload can
 * never take a discovery/state turn down.
 */
object HaPayloads {

    /** Parse an `GET /api/states` body; non-array/malformed → empty. */
    fun parseStates(body: String, json: Json): List<HaEntity> {
        val array = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull(::entityOrNull)
    }

    private fun entityOrNull(element: JsonElement): HaEntity? {
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return null
        val id = rawText(obj["entity_id"])?.takeIf { it.isNotBlank() } ?: return null
        val attributes = runCatching { obj["attributes"]?.jsonObject }.getOrNull().orEmpty()
        return HaEntity(entityId = id, state = rawText(obj["state"]).orEmpty(), attributes = attributes)
    }

    /** The `type` of one websocket frame, or null when it is not a JSON object. */
    fun frameType(frame: String, json: Json): String? =
        runCatching { rawText(json.parseToJsonElement(frame).jsonObject["type"]) }.getOrNull()

    /**
     * Extract the `result` array of a matching websocket `result` frame. Null
     * when the frame is not a successful result for [id] — so the reader keeps
     * scanning rather than mistaking an unrelated frame for the reply.
     */
    fun resultArray(frame: String, id: Int, json: Json): JsonArray? {
        val obj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: return null
        if (rawText(obj["type"]) != "result") return null
        if (rawText(obj["id"])?.toIntOrNull() != id) return null
        if (rawText(obj["success"])?.toBooleanStrictOrNull() != true) return null
        return runCatching { obj["result"]?.jsonArray }.getOrNull()
    }

    /**
     * entity_id → area name, joining `entity_registry` → `device_registry` →
     * `area_registry`. An entity with neither a direct nor a device area is
     * dropped (the caller keeps the device with a null room).
     */
    fun roomIndex(entities: JsonArray, devices: JsonArray, areas: JsonArray): Map<String, String> {
        val areaNames = areas.mapNotNull { areaOrNull(it) }.toMap()
        val deviceAreas = devices.mapNotNull { deviceOrNull(it) }.toMap()
        return entities.mapNotNull { entity ->
            val obj = runCatching { entity.jsonObject }.getOrNull() ?: return@mapNotNull null
            val entityId = rawText(obj["entity_id"]) ?: return@mapNotNull null
            val areaId = rawText(obj["area_id"]) ?: deviceAreas[rawText(obj["device_id"])]
            val name = areaId?.let { areaNames[it] } ?: return@mapNotNull null
            entityId to name
        }.toMap()
    }

    private fun areaOrNull(element: JsonElement): Pair<String, String>? {
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return null
        val id = rawText(obj["area_id"]) ?: rawText(obj["id"]) ?: return null
        val name = rawText(obj["name"]) ?: return null
        return id to name
    }

    private fun deviceOrNull(element: JsonElement): Pair<String, String>? {
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return null
        val id = rawText(obj["id"]) ?: return null
        val areaId = rawText(obj["area_id"]) ?: return null
        return id to areaId
    }

    /** A JSON primitive's textual content, or null for a container/null element. */
    fun rawText(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull
}
