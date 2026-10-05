package com.jarvis.assistant.home

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Outcome of decoding the persisted provider-config list. Mirrors
 * `McpServerDecodeResult`: blank is [Empty] (the honest "nothing configured"
 * state), malformed is [Invalid] and never throws.
 */
sealed interface HomeConfigDecodeResult {
    data class Ok(val configs: List<HomeProviderConfig>) : HomeConfigDecodeResult

    data object Empty : HomeConfigDecodeResult

    data class Invalid(val reason: String) : HomeConfigDecodeResult
}

/**
 * Pure list ⇄ JSON codec for [HomeProviderConfig], mirroring
 * `McpServerConfigCodec`. Unknown fields are ignored on decode, defaults fill
 * omitted fields, and `encodeDefaults` keeps output canonical.
 */
object HomeConfigCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val listSerializer = ListSerializer(HomeProviderConfig.serializer())

    fun encode(configs: List<HomeProviderConfig>): String = json.encodeToString(listSerializer, configs)

    /** Null/blank → [HomeConfigDecodeResult.Empty]; valid → Ok; else Invalid. Never throws. */
    fun decode(raw: String?): HomeConfigDecodeResult {
        val text = raw?.trim()
        if (text.isNullOrEmpty()) return HomeConfigDecodeResult.Empty
        return try {
            HomeConfigDecodeResult.Ok(json.decodeFromString(listSerializer, text))
        } catch (e: SerializationException) {
            HomeConfigDecodeResult.Invalid(e.message ?: "malformed home provider list")
        } catch (e: IllegalArgumentException) {
            HomeConfigDecodeResult.Invalid(e.message ?: "malformed home provider list")
        }
    }
}
