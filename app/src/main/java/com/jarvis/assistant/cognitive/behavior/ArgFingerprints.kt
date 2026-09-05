package com.jarvis.assistant.cognitive.behavior

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * COGNITIVE_PLAN §8.1: per-tool slot-payload normalization — the ONLY thing
 * telemetry stores about an execution. Free-form utterances are NEVER copied
 * into `command_events`; the fingerprint is a normalized, deterministic
 * projection of the structured arguments so identical intents cluster
 * together across phrasings ("включи джаз" / "поставь джаз" → `q:джаз`).
 *
 * Pure Kotlin — no Android types, fully fixture-testable.
 */
object ArgFingerprints {

    /** Bucket width for numeric levels (volume): 0..100 → 25-wide buckets. */
    const val LEVEL_BUCKET = 25

    /** Hard cap — a runaway string arg must not become a memory hog. */
    private const val MAX_FINGERPRINT = 120

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Normalizes [tool]'s arguments into a stable fingerprint.
     * Unknown tools fall through to a generic sorted key/value form.
     */
    fun of(tool: String, argumentsJson: String?): String {
        val args = argumentsJson?.let { parsed(it) }
        val value = when (tool) {
            "playMusic", "searchMusic" -> args?.stringArg("query")?.let { "q:${normalize(it)}" }
            "getWeather" -> args?.stringArg("city")?.let { "city:${normalize(it)}" }
            "setVolume" -> args?.intArg("level")?.let { "level:${bucket(it)}" }
            "controlPlayback" -> args?.stringArg("action")?.let { "action:${normalize(it)}" }
            else -> null
        }
        if (value != null) return value.take(MAX_FINGERPRINT)

        // Generic fallback: stable sorted key=value projection (or "all" for
        // the argument-free read-only tools: getNowPlaying, listPlaylists, …).
        if (args == null || args.isEmpty()) return "all"
        return args.entries
            .map { (k, v) -> "${normalize(k)}=${normalize(v.toString())}" }
            .sorted()
            .joinToString("&")
            .take(MAX_FINGERPRINT)
    }

    /** 2-hour bucket (§8.2): hour 0..23 → 0..11. */
    fun hourBucket(hour: Int): Int = hour.coerceIn(0, 23) / 2

    /** Volume-style bucketing: floor to [LEVEL_BUCKET] steps. */
    fun bucket(level: Int): Int = (level.coerceIn(0, 100) / LEVEL_BUCKET) * LEVEL_BUCKET

    /**
     * Lowercase, trim, collapse whitespace, strip punctuation — enough
     * clustering for slot values while staying legible in the inspector
     * (no hashing: transparency is a feature, plan §4).
     */
    fun normalize(raw: String): String = raw
        .lowercase()
        .trim()
        .replace(Regex("[\\p{Punct}\\s]+"), " ")
        .trim()

    private fun parsed(json: String): JsonObject? = try {
        Json.parseToJsonElement(json).jsonObject
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.stringArg(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.intArg(key: String): Int? = stringArg(key)?.let {
        it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt()
    }
}
