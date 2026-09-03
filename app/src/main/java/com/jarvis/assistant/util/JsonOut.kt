package com.jarvis.assistant.util

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Safe JSON construction for tool results. The original code built JSON with
 * string interpolation, which breaks on quotes/special characters in labels
 * and on missing numeric values. All tool output now goes through these
 * helpers.
 */
object JsonOut {

    fun obj(vararg pairs: Pair<String, Any?>): String {
        val map = pairs.associate { (k, v) ->
            k to when (v) {
                // D1: a null value serializes as JSON null, not the STRING
                // "null" — {"x":"null"} mis-types the field for the LLM.
                null -> JsonNull
                is String -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        }
        return JsonObject(map).toString()
    }

    fun error(message: String): String = obj("error" to message)

    fun list(items: List<JsonObject>): String =
        kotlinx.serialization.json.JsonArray(items).toString()
}
