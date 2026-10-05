package com.jarvis.assistant.home

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Pure list ⇄ JSON codec for [HomeGrant].
 *
 * Mirrors `McpServerConfigCodec`: a bare JSON array, unknown fields ignored,
 * `encodeDefaults = true` for canonical output. [decodeOrEmpty] NEVER throws —
 * blank or malformed input yields an empty list (no grants = deny everything,
 * which is the safe direction).
 */
object HomeGrantCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val listSerializer = ListSerializer(HomeGrant.serializer())

    fun encode(grants: List<HomeGrant>): String = json.encodeToString(listSerializer, grants)

    /** Null/blank/malformed → empty list. Valid → parsed grants. Never throws. */
    fun decodeOrEmpty(raw: String?): List<HomeGrant> {
        val text = raw?.trim()
        if (text.isNullOrEmpty()) return emptyList()
        return try {
            json.decodeFromString(listSerializer, text)
        } catch (_: SerializationException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }
}
