package com.jarvis.assistant.cognitive.eval

import com.jarvis.assistant.cognitive.embed.EmbedderBenchmark
import com.jarvis.assistant.cognitive.embed.EmbeddingEngine
import com.jarvis.assistant.cognitive.embed.LexicalEmbedder
import com.jarvis.assistant.cognitive.embed.RetrievalGate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * COGNITIVE_PLAN §10.2: the retrieval-quality gate — "50 query→expected-fact
 * pairs; lexical baseline vs hybrid; ship vectors only at ≥ 15 % recall@5
 * improvement; negative result documented otherwise."
 *
 * CI contract (deterministic by construction — hashed engine, fixture
 * order, fixed clock):
 * 1. recompute baseline and LOCAL-hybrid recall@5 over the 50 RU fixtures
 *    (`cognitive/eval/retrieval/fixtures.json`);
 * 2. compare EXACTLY against the recorded artifact
 *    (`results-baseline.json`) — any change to fixtures/engines/scoring
 *    fails here until the verdict is consciously re-recorded;
 * 3. assert [RetrievalGate.LOCAL_BRANCH_SHIPS] equals the artifact verdict
 *    (the constant feeds [com.jarvis.assistant.cognitive.embed.EmbedderSelection]
 *    AUTO fallback).
 *
 * The recorded negative result (if ships=false) is the plan's honest
 * outcome branch: vectors stay OFF by default until the on-device
 * benchmark — where the CLOUD GigaChat branch can be measured — proves a
 * winner (Settings «Проверить качество поиска»).
 */
class RetrievalEvalTest {

    @Serializable
    data class RecordedResult(
        val fixtures: Int,
        val baselineRecallAt5: Double,
        val hybridRecallAt5: Double,
        val improvement: Double,
        val ships: Boolean,
        val recordedAt: String,
    )

    private val json = Json { prettyPrint = true }

    private fun loadFixtures(): List<EmbedderBenchmark.Fixture> {
        val text = javaClass.classLoader
            .getResource("cognitive/eval/retrieval/fixtures.json")?.readText()
            ?: throw AssertionError("fixtures.json missing from test resources")
        return json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(EmbedderBenchmark.Fixture.serializer()),
            text,
        )
    }

    private fun artifactFile(): File = File(
        "src/test/resources/cognitive/eval/retrieval/results-baseline.json",
    )

    @Test
    fun `retrieval gate - recorded verdict matches recomputed numbers`() = runBlocking {
        val fixtures = loadFixtures()
        assertEquals(50, fixtures.size)

        val local = LexicalEmbedder()
        val baseline = EmbedderBenchmark.evaluate(fixtures, engine = null)
        val hybrid = EmbedderBenchmark.evaluate(
            fixtures,
            EmbedderBenchmark.EngineAdapter { texts -> local.embed(texts) },
        )

        val artifact = artifactFile()
        if (!artifact.exists()) {
            // Bootstrap mode: record the freshly computed verdict and stop.
            // The next run enforces it (and this failure is the signal).
            artifact.parentFile.mkdirs()
            artifact.writeText(
                json.encodeToString(
                    RecordedResult.serializer(),
                    RecordedResult(
                        fixtures = fixtures.size,
                        baselineRecallAt5 = baseline.baselineRecallAt5,
                        hybridRecallAt5 = hybrid.hybridRecallAt5,
                        improvement = hybrid.improvement,
                        ships = hybrid.ships(),
                        recordedAt = "bootstrap",
                    ),
                ),
            )
            throw AssertionError(
                "Recorded first verdict to ${artifact.path}: baseline=$baseline hybrid=$hybrid. " +
                    "Review, set RetrievalGate.LOCAL_BRANCH_SHIPS=$hybrid.ships(), re-run.",
            )
        }

        val recorded = json.decodeFromString(RecordedResult.serializer(), artifact.readText())
        assertEquals(recorded.fixtures, fixtures.size)
        assertEquals(recorded.baselineRecallAt5, baseline.baselineRecallAt5, 1e-12)
        assertEquals(recorded.hybridRecallAt5, hybrid.hybridRecallAt5, 1e-12)
        assertEquals(recorded.improvement, hybrid.improvement, 1e-12)
        assertEquals(recorded.ships, hybrid.ships())
        assertEquals(
            "RetrievalGate.LOCAL_BRANCH_SHIPS must match the recorded verdict",
            recorded.ships,
            RetrievalGate.LOCAL_BRANCH_SHIPS,
        )
        assertTrue(
            "report must render human-readable numbers",
            hybrid.toString().contains("engine"),
        )
        // Sanity: the engine id namespace is wired into the selection logic.
        assertEquals("local-lexical-v1", EmbeddingEngine.LOCAL_ID)
    }
}
