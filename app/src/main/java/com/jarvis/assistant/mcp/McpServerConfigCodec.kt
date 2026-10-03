package com.jarvis.assistant.mcp

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Outcome of decoding the persisted MCP server list.
 *
 * A blank/null payload is [Empty] — the honest "nothing configured yet" state,
 * NOT an error. Malformed JSON is [Invalid] and never throws: a corrupt pref
 * must not crash startup, it just yields no servers plus a reason for the log.
 */
sealed interface McpServerDecodeResult {
    /** A well-formed list (possibly empty, when the payload was `[]`). */
    data class Ok(val servers: List<McpServerConfig>) : McpServerDecodeResult

    /** No payload at all (null/blank) — equivalent to an empty list. */
    data object Empty : McpServerDecodeResult

    /** The payload was present but not a decodable list. */
    data class Invalid(val reason: String) : McpServerDecodeResult
}

/**
 * Pure list ⇄ JSON codec for [McpServerConfig].
 *
 * The wire format is a bare JSON array of server objects. Unknown fields are
 * ignored on decode (forward compatibility), defaults fill omitted fields, and
 * `encodeDefaults` keeps the output canonical so
 * `encode(decode(encode(x))) == encode(x)`.
 *
 * [decode] NEVER throws: blank input is [McpServerDecodeResult.Empty] and any
 * parse failure is [McpServerDecodeResult.Invalid].
 */
object McpServerConfigCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val listSerializer = ListSerializer(McpServerConfig.serializer())

    /** Serialize [servers] to a canonical JSON array. */
    fun encode(servers: List<McpServerConfig>): String =
        json.encodeToString(listSerializer, servers)

    /**
     * Parse a persisted payload. Null/blank → [McpServerDecodeResult.Empty];
     * a valid array → [McpServerDecodeResult.Ok]; anything else →
     * [McpServerDecodeResult.Invalid] with a human-readable reason.
     */
    fun decode(raw: String?): McpServerDecodeResult {
        val text = raw?.trim()
        if (text.isNullOrEmpty()) return McpServerDecodeResult.Empty
        return try {
            McpServerDecodeResult.Ok(json.decodeFromString(listSerializer, text))
        } catch (e: SerializationException) {
            McpServerDecodeResult.Invalid(e.message ?: "malformed MCP server list")
        } catch (e: IllegalArgumentException) {
            McpServerDecodeResult.Invalid(e.message ?: "malformed MCP server list")
        }
    }
}
