package com.jarvis.assistant.home

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Pure alias-map ⇄ JSON codec. The on-disk shape is a flat JSON object
 * `{"<alias>": "<provider.id>:<nativeId>"}`; values are the stable
 * [HomeDeviceKey.wire] form so the enum names never appear in persisted data.
 *
 * [decodeOrEmpty] NEVER throws: blank/malformed input is an empty map, and an
 * individual entry with an unparseable value is skipped rather than failing the
 * whole map.
 */
object HomeAliasCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    fun encode(aliases: Map<String, HomeDeviceKey>): String =
        json.encodeToString(mapSerializer, aliases.mapValues { it.value.wire })

    /** Null/blank/malformed → empty map; bad entries skipped. Never throws. */
    fun decodeOrEmpty(raw: String?): Map<String, HomeDeviceKey> {
        val text = raw?.trim()
        if (text.isNullOrEmpty()) return emptyMap()
        return try {
            val decoded = json.decodeFromString(mapSerializer, text)
            val out = linkedMapOf<String, HomeDeviceKey>()
            for ((alias, wire) in decoded) {
                val normalized = HomeNormalizer.normalize(alias)
                val key = HomeDeviceKey.parseOrNull(wire)
                if (key != null && normalized.isNotEmpty()) out[normalized] = key
            }
            out
        } catch (_: SerializationException) {
            emptyMap()
        } catch (_: IllegalArgumentException) {
            emptyMap()
        }
    }
}
