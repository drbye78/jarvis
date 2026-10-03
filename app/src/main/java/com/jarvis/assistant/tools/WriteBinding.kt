package com.jarvis.assistant.tools

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/**
 * Pure builder of the stable digest that BINDS a write confirmation to one
 * exact external call: `(serverId, toolName, arguments)`.
 *
 * The confirmation challenge is single-use and scoped to a digest so that a
 * "yes" for one tool call cannot authorize a DIFFERENT call (a bait-and-switch
 * where the model re-invokes the tool with new arguments, or a replayed "yes").
 * The digest is derived only from server-provided identity plus the call's
 * canonical arguments — never from model-authored text.
 *
 * Canonicalization: [canonicalKey] parses the arguments as a JSON object,
 * recursively sorts every object's member keys lexicographically, and
 * re-serializes with kotlinx (no insignificant whitespace). Key ORDER and
 * whitespace therefore do NOT affect the digest, while ANY value difference
 * does. Numeric literals are deliberately NOT normalized — `1` and `1.0` stay
 * distinct (fail closed: an ambiguous equivalence must never silently broaden
 * what a confirmation authorizes).
 *
 * Returns `null` when the arguments are not a well-formed JSON OBJECT
 * (malformed text, an array, or a primitive). The caller fails closed on null.
 */
object WriteBinding {

    private const val HEX = "0123456789abcdef"

    /**
     * SHA-256 hex of `serverId \u0000 originalToolName \u0000 canonical(args)`.
     * The NUL separator prevents field-boundary ambiguity (e.g. `"a" + "bc"`
     * colliding with `"ab" + "c"`). Null when [argumentsJson] is not a valid
     * JSON object.
     */
    fun canonicalKey(serverId: String, originalToolName: String, argumentsJson: String): String? {
        val root = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (root !is JsonObject) return null
        val canonical = canonicalize(root).toString()
        return sha256Hex("$serverId\u0000$originalToolName\u0000$canonical")
    }

    /**
     * Recursively order object member keys; arrays keep their (meaningful)
     * order and primitives keep their exact parsed literal.
     */
    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .sortedBy { it.key }
                .associate { it.key to canonicalize(it.value) },
        )
        is JsonArray -> JsonArray(element.map(::canonicalize))
        else -> element
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val unsigned = byte.toInt() and 0xFF
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0F])
            }
        }
    }
}
