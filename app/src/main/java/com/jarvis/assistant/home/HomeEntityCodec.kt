package com.jarvis.assistant.home

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Pure set ⇄ JSON codec for the curated awareness entity list.
 *
 * On-disk shape is a bare JSON array of [HomeDeviceKey.wire] strings
 * (`["ha:binary_sensor.washer"]`), so the enum names never appear in persisted
 * data. [decodeOrEmpty] NEVER throws: blank/malformed input is an empty set and
 * an unparseable entry is skipped rather than failing the whole list.
 *
 * Semantics of the set live with the awareness lane: an EMPTY set means "no
 * curation" (every discovered device is eligible once awareness is on), a
 * non-empty set is an explicit allow-list. See the H4 awareness design.
 */
object HomeEntityCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val listSerializer = ListSerializer(String.serializer())

    fun encode(keys: Collection<HomeDeviceKey>): String =
        json.encodeToString(listSerializer, keys.map { it.wire }.distinct())

    /** Null/blank/malformed → empty set; bad entries skipped. Never throws. */
    fun decodeOrEmpty(raw: String?): Set<HomeDeviceKey> {
        val text = raw?.trim()
        if (text.isNullOrEmpty()) return emptySet()
        return try {
            json.decodeFromString(listSerializer, text)
                .mapNotNull { HomeDeviceKey.parseOrNull(it) }
                .toSet()
        } catch (_: SerializationException) {
            emptySet()
        } catch (_: IllegalArgumentException) {
            emptySet()
        }
    }
}
