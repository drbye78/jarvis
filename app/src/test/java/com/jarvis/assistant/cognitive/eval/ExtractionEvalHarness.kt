package com.jarvis.assistant.cognitive.eval

import com.jarvis.assistant.cognitive.extract.ExtractionParser
import com.jarvis.assistant.cognitive.extract.FactNormalizer
import com.jarvis.assistant.cognitive.model.ValidatedFact
import com.jarvis.assistant.cognitive.recall.SearchTokenizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * COGNITIVE_PLAN §10.1 + Appendix C: the extraction evaluation harness.
 *
 * Runs RECORDED GigaChat responses (fixtures in `cognitive/eval/fixtures/`)
 * through the REAL parser + normalizer and reports precision / recall /
 * hallucination counts. CI-runnable on the JVM; re-run on any model or
 * prompt change — the `autoExtract` default flips on only when the gate
 * (precision ≥ 0.85, recall ≥ 0.7, zero hallucinations) holds on the full
 * 40-fixture set. Until then the switch stays user-off by default (§6.2).
 *
 * Honest scope note: the harness measures the LOCAL VALIDATION PIPELINE
 * against recorded responses, not live GigaChat — it catches regressions in
 * validator rules and prompt-contract drift, and fixtures are expanded from
 * real device transcripts over time.
 */
class ExtractionEvalHarness(
    private val parser: ExtractionParser = ExtractionParser(),
    private val normalizer: FactNormalizer = FactNormalizer(
        nowMs = { 0L },
        newId = { "eval-${EvalSequencer.next()}" },
    ),
) {

    @Serializable
    data class Utterance(val messageId: Long, val text: String)

    @Serializable
    data class ExpectedFact(
        val subject: String = "user",
        val predicate: String,
        val value: String,
    )

    @Serializable
    data class Fixture(
        val id: String,
        val kind: String, // "positive" | "noise" | "correction" | "sensitive" …
        val dialogue: List<Utterance>,
        val recordedResponse: String,
        val expectedFacts: List<ExpectedFact> = emptyList(),
        val forbiddenFacts: List<String> = emptyList(),
    )

    data class Metrics(
        val fixtures: Int,
        val truePositives: Int,
        val falsePositives: Int,
        val falseNegatives: Int,
        val hallucinations: Int,
    ) {
        val precision: Double
            get() {
                val total = truePositives + falsePositives
                return if (total == 0) 1.0 else truePositives.toDouble() / total
            }

        val recall: Double
            get() {
                val total = truePositives + falseNegatives
                return if (total == 0) 1.0 else truePositives.toDouble() / total
            }

        /** Plan §10.1 gate. */
        fun passesGate(): Boolean =
            precision >= GATE_PRECISION && recall >= GATE_RECALL && hallucinations == 0

        override fun toString(): String {
            val fmt = "precision=%.2f recall=%.2f tp=%d fp=%d fn=%d halluc=%d fixtures=%d"
            return fmt.format(
                java.util.Locale.ROOT,
                precision,
                recall,
                truePositives,
                falsePositives,
                falseNegatives,
                hallucinations,
                fixtures,
            )
        }
    }

    fun evaluate(fixtures: List<Fixture>): Metrics {
        val perFixture = fixtures.map { evaluateFixture(it) }
        return Metrics(
            fixtures = fixtures.size,
            truePositives = perFixture.sumOf { it.tp },
            falsePositives = perFixture.sumOf { it.fp },
            falseNegatives = perFixture.sumOf { it.fn },
            hallucinations = perFixture.sumOf { it.hallucinations },
        )
    }

    /** One fixture through parser + normalizer + matching. */
    private fun evaluateFixture(fixture: Fixture): Counts {
        val batch = fixture.dialogue.map { it.messageId to it.text }
        val result = parser.parse(fixture.recordedResponse, batch)
        val extracted: List<ValidatedFact> = when (result) {
            is ExtractionParser.Result.Ok -> result.facts
            is ExtractionParser.Result.ParseError -> emptyList()
        }

        // Normalize against an empty store (fixture scope is self-contained).
        val normalized = extracted.mapNotNull { fact ->
            val decision = normalizer.classify(fact, emptyList())
            when (decision) {
                is com.jarvis.assistant.cognitive.extract.NormalizationDecision.CreateNew -> decision.newFact
                is com.jarvis.assistant.cognitive.extract.NormalizationDecision.Supersede -> decision.newFact
                is com.jarvis.assistant.cognitive.extract.NormalizationDecision.Contest -> decision.newFact
                is com.jarvis.assistant.cognitive.extract.NormalizationDecision.ConfirmExisting -> null
            }
        }

        val expectedKeys = fixture.expectedFacts.map { keyOf(it.subject, it.predicate, it.value) }
        val matchedExpected = mutableSetOf<Int>()
        var tp = 0
        var fp = 0
        var hallucinations = 0

        for (extractedKey in normalized.map { keyOf(it.subject, it.predicate, it.value) }) {
            val matchIdx = expectedKeys.indexOfFirst { matches(extractedKey, it) }
            if (matchIdx >= 0) {
                tp++
                matchedExpected.add(matchIdx)
            } else {
                fp++
                // A fact that is both unexpected AND hits a forbidden topic
                // is a hallucination (the plan's probe set).
                val touchedForbidden = fixture.forbiddenFacts.any { forbidden ->
                    matches(extractedKey, keyOf("user", "*", forbidden)) ||
                        extractedKey.third.contains(SearchTokenizer.normalize(forbidden))
                }
                if (touchedForbidden) hallucinations++
            }
        }
        val fn = expectedKeys.size - matchedExpected.size
        return Counts(tp, fp, fn, hallucinations)
    }

    private data class Counts(val tp: Int, val fp: Int, val fn: Int, val hallucinations: Int)

    /** (subject, predicate, normalized value) with prefix tolerance on value. */
    private fun matches(a: Triple<String, String, String>, b: Triple<String, String, String>): Boolean {
        if (a.second != "*" && b.second != "*" && a.second != b.second) return false
        val av = a.third
        val bv = b.third
        return av == bv || av.startsWith(bv) || bv.startsWith(av) ||
            SearchTokenizer.overlap(av, bv) >= 0.8
    }

    private fun keyOf(subject: String, predicate: String, value: String) =
        Triple(subject, predicate, SearchTokenizer.normalize(value))

    companion object {
        const val GATE_PRECISION = 0.85
        const val GATE_RECALL = 0.7
    }
}

/** Deterministic id source for the harness (shared counter). */
private object EvalSequencer {
    private val counter = java.util.concurrent.atomic.AtomicInteger(0)
    fun next(): Int = counter.incrementAndGet()
}

/** Fixture loading from the test classpath (`cognitive/eval/fixtures/`). */
object EvalFixtures {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<ExtractionEvalHarness.Fixture> {
        val loader = EvalFixtures::class.java.classLoader
        val url = loader.getResource("cognitive/eval/fixtures")
            ?: return emptyList()
        val dir = java.io.File(url.toURI())
        val files = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }
            ?: return emptyList()
        return files.map { f ->
            json.decodeFromString(ExtractionEvalHarness.Fixture.serializer(), f.readText())
        }
    }
}
