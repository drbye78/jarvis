package com.jarvis.assistant.cognitive.extract

import com.jarvis.assistant.cognitive.model.ValidatedFact
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import timber.log.Timber

/**
 * COGNITIVE_PLAN §6.2: strict local validation of the extraction LLM's
 * response — the evidence-anchored anti-hallucination gate. Nothing reaches
 * the normalizer (and therefore storage) without passing here.
 *
 * Validation rules (plan §6.2 + Appendix A):
 * 1. The response must CONTAIN a JSON object (models sometimes wrap JSON in
 *    prose or ``` fences — the first `{` to the last `}` is parsed; failure
 *    → [Result.ParseError], the queue row is QUARANTINED, never retried
 *    blindly, never a crash).
 * 2. `confidence` clamped to [0, 1]; non-numeric → row dropped.
 * 3. Empty `value` → row dropped.
 * 4. `evidence` must fuzzily occur in the source utterance — every evidence
 *    token (normalized, length ≥ 2) must be present in the utterance token
 *    set. A model-invented "fact" without a textual anchor dies here.
 * 5. `messageId` must reference a member of the batch — a hallucinated
 *    reference is dropped (it has no provenance).
 * 6. Unknown `predicate` → OTHER category (kept, but classified honestly).
 * 7. HEALTH/politics/religion patterns → `sensitive=true`.
 */
class ExtractionParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    sealed interface Result {
        /** Parsed + validated candidates, ready for the normalizer. */
        data class Ok(val facts: List<ValidatedFact>, val droppedCount: Int) : Result

        /** No JSON object found / schema invalid → quarantine the batch. */
        data class ParseError(val detail: String) : Result
    }

    /**
     * @param response raw LLM completion text
     * @param batch the (messageId → utterance) pairs that were sent
     */
    fun parse(response: String, batch: List<Pair<Long, String>>): Result {
        val extracted = extractJsonObject(response)
            ?: return Result.ParseError("no JSON object in response")
        val root = try {
            json.parseToJsonElement(extracted).jsonObject
        } catch (e: Exception) {
            return Result.ParseError("invalid JSON: ${e.message}")
        }
        val factsArray = try {
            root["facts"]?.jsonArray
        } catch (e: Exception) {
            Timber.w(e, "Cognitive: facts field is not an array")
            null
        } ?: return Result.ParseError("missing facts array")

        val utterancesByMessage = batch.toMap()
        val validated = mutableListOf<ValidatedFact>()
        var dropped = 0

        for (element in factsArray) {
            val row = element as? JsonObject
            val fact = row?.let { validateRow(it, utterancesByMessage) }
            if (fact != null) {
                validated.add(fact)
            } else {
                dropped++
            }
        }
        return Result.Ok(validated, dropped)
    }

    /** One row through the full validation gauntlet; null = drop (counted). */
    private fun validateRow(
        row: JsonObject,
        utterancesByMessage: Map<Long, String>,
    ): ValidatedFact? {
        val value = row.str("value")?.trim().orEmpty()
        if (value.isEmpty()) return null

        val messageId = row.long("messageId")
        val utterance = messageId?.let { utterancesByMessage[it] }
        if (messageId == null || utterance == null) return null

        val evidence = row.str("evidence")?.trim().orEmpty()
        if (!evidenceOccursInUtterance(evidence, utterance)) return null

        val confidence = row.double("confidence")?.toFloat()?.coerceIn(0f, 1f) ?: return null

        val subjectRaw = row.str("subject")?.trim().orEmpty()
        val subject = if (subjectRaw.isBlank()) {
            "user"
        } else {
            com.jarvis.assistant.cognitive.recall.SearchTokenizer.normalize(subjectRaw)
        }

        val predicateRaw = row.str("predicate")
        val (category, sensitive) = ExtractionContract.categorize(predicateRaw, value)

        return ValidatedFact(
            subject = subject,
            predicate = predicateRaw?.trim()?.lowercase(java.util.Locale.ROOT) ?: "other",
            value = value,
            confidence = confidence,
            evidence = evidence,
            messageId = messageId,
            category = category,
            sensitive = sensitive,
        )
    }

    /**
     * Evidence-anchoring (rule 4): normalized token containment. Lenient on
     * morphology (the stem-set subset test), strict on invention: an
     * evidence string with NO meaningful tokens or with tokens absent from
     * the utterance fails.
     */
    internal fun evidenceOccursInUtterance(evidence: String, utterance: String): Boolean {
        val normEvidence = com.jarvis.assistant.cognitive.recall.SearchTokenizer.normalize(evidence)
        if (normEvidence.isEmpty()) return false
        val normUtterance = com.jarvis.assistant.cognitive.recall.SearchTokenizer.normalize(utterance)
        // Direct containment after normalization (punctuation/case folded).
        if (normUtterance.contains(normEvidence)) return true

        // Token-subset fallback (ASR punctuation and filler words differ).
        val evTokens = normEvidence.split(' ').filter { it.length >= 2 }
        if (evTokens.isEmpty()) return false
        val utTokens = normUtterance.split(' ').toSet()
        return evTokens.all { it in utTokens }
    }

    /** First `{` … last `}` window; null when absent. */
    internal fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        val end = text.lastIndexOf('}')
        if (end <= start) return null
        return text.substring(start, end + 1)
    }
}

/** Structured summary of one batch outcome for counters/telemetry (plan §6.2). */
data class ExtractionBatchReport(
    val batchId: String,
    val messages: Int,
    val extracted: Int,
    val dropped: Int,
    val quarantined: Boolean,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("batchId", batchId)
        put("messages", messages)
        put("extracted", extracted)
        put("dropped", dropped)
        put("quarantined", quarantined)
    }
}

// --- Local JSON accessors (lenient about the model's formatting quirks) ---

private fun JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

private fun JsonObject.long(key: String): Long? = str(key)?.trim()?.toLongOrNull()

private fun JsonObject.double(key: String): Double? = str(key)?.trim()?.replace(',', '.')?.toDoubleOrNull()
