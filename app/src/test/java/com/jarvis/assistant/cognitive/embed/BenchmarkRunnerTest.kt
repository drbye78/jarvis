package com.jarvis.assistant.cognitive.embed

import com.jarvis.assistant.cognitive.data.MemoryMetaEntity
import com.jarvis.assistant.cognitive.extract.FakeMemoryMetaDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-C (audit HIGH, plan §9.2): the cloud benchmark lane must be gated on
 * the REACTIVE `memory.cloudEnabled` flag, not merely on the presence of a
 * constructed cloud engine. With the flag off, BenchmarkRunner must make
 * ZERO calls on the cloud engine — the entitlement probe itself is HTTP.
 */
class BenchmarkRunnerTest {

    /** Counts every egress surface (probe + embed) of the engine. */
    private class CountingEngine(
        override val engineId: String,
        override val kind: EmbeddingEngine.Kind,
        private val verdict: EmbeddingEngine.Entitlement = EmbeddingEngine.Entitlement.Ok,
    ) : EmbeddingEngine {
        var embedCalls = 0
            private set
        var embeddedTexts = 0
            private set
        var entitlementProbes = 0
            private set

        override val dim = 2

        override suspend fun embed(texts: List<String>): List<FloatArray> {
            embedCalls++
            embeddedTexts += texts.size
            return texts.map { floatArrayOf(1f, 0f) }
        }

        override suspend fun checkEntitlement(): EmbeddingEngine.Entitlement {
            entitlementProbes++
            return verdict
        }
    }

    @Test
    fun `cloud lane fully skipped with cloudEnabled false - zero entitlement probes and zero embed calls`() = runTest {
        val cloud = CountingEngine(EmbeddingEngine.CLOUD_ID, EmbeddingEngine.Kind.CLOUD)
        val meta = FakeMemoryMetaDao()
        val runner = BenchmarkRunner(
            metaDao = meta,
            localEmbedder = CountingEngine(EmbeddingEngine.LOCAL_ID, EmbeddingEngine.Kind.LOCAL),
            cloudEmbedder = cloud,
            cloudEnabled = { false },
        )

        val outcome = runner.run()

        // The audit breach: this used to run checkEntitlement()+embed HTTP
        // with memory.cloudEnabled=false. Every cloud surface must be zero.
        assertEquals(0, cloud.entitlementProbes)
        assertEquals(0, cloud.embedCalls)
        assertNull(outcome.cloudReport)
        // Honest "cloud disabled" state, distinct from "engine not built" (null).
        assertEquals("disabled", outcome.entitlement)
        // The local branch still runs (local-only result).
        assertNotNull(outcome.localReport)
        assertTrue(outcome.winner != EmbeddingEngine.CLOUD_ID)
        // No entitlement meta written either way — the lane never ran.
        assertNull(meta.values[MemoryMetaEntity.KEY_CLOUD_EMBED_ENTITLED])
        assertNull(meta.values[MemoryMetaEntity.KEY_CLOUD_EMBED_UNAVAILABLE])
    }

    @Test
    fun `cloud lane runs when the gate is open`() = runTest {
        val cloud = CountingEngine(EmbeddingEngine.CLOUD_ID, EmbeddingEngine.Kind.CLOUD)
        val runner = BenchmarkRunner(
            metaDao = FakeMemoryMetaDao(),
            localEmbedder = CountingEngine(EmbeddingEngine.LOCAL_ID, EmbeddingEngine.Kind.LOCAL),
            cloudEmbedder = cloud,
            cloudEnabled = { true },
        )

        val outcome = runner.run()

        assertEquals(1, cloud.entitlementProbes)
        assertTrue("cloud branch must embed the probe fixtures", cloud.embedCalls > 0)
        assertEquals("ok", outcome.entitlement)
        assertNotNull(outcome.cloudReport)
    }

    @Test
    fun `gate is a live read - flipping it applies without rebuilding the runner`() = runTest {
        // AGENTS.md convention: every reactive setting ships with a
        // live-toggle regression test.
        val flag = MutableStateFlow(false)
        val cloud = CountingEngine(EmbeddingEngine.CLOUD_ID, EmbeddingEngine.Kind.CLOUD)
        val runner = BenchmarkRunner(
            metaDao = FakeMemoryMetaDao(),
            localEmbedder = CountingEngine(EmbeddingEngine.LOCAL_ID, EmbeddingEngine.Kind.LOCAL),
            cloudEmbedder = cloud,
            cloudEnabled = { flag.value },
        )

        runner.run()
        assertEquals(0, cloud.entitlementProbes)

        flag.value = true
        val second = runner.run()
        assertEquals(1, cloud.entitlementProbes)
        assertEquals("ok", second.entitlement)

        flag.value = false
        val third = runner.run()
        assertEquals(1, cloud.entitlementProbes) // still 1 — the flag re-closed the lane
        assertEquals("disabled", third.entitlement)
    }

    @Test
    fun `no cloud engine keeps the outcome null-entitled (not disabled)`() = runTest {
        val runner = BenchmarkRunner(
            metaDao = FakeMemoryMetaDao(),
            localEmbedder = CountingEngine(EmbeddingEngine.LOCAL_ID, EmbeddingEngine.Kind.LOCAL),
            cloudEmbedder = null,
            cloudEnabled = { true },
        )
        val outcome = runner.run()
        assertNull(outcome.entitlement)
        assertNull(outcome.cloudReport)
    }

    @Test
    fun `denied entitlement embeds nothing even with the gate open`() = runTest {
        val cloud = CountingEngine(
            EmbeddingEngine.CLOUD_ID,
            EmbeddingEngine.Kind.CLOUD,
            verdict = EmbeddingEngine.Entitlement.Denied(403),
        )
        val meta = FakeMemoryMetaDao()
        val runner = BenchmarkRunner(
            metaDao = meta,
            localEmbedder = CountingEngine(EmbeddingEngine.LOCAL_ID, EmbeddingEngine.Kind.LOCAL),
            cloudEmbedder = cloud,
            cloudEnabled = { true },
        )

        val outcome = runner.run()

        assertEquals(1, cloud.entitlementProbes)
        assertEquals(0, cloud.embedCalls)
        assertEquals("denied:403", outcome.entitlement)
        assertEquals("403", meta.values[MemoryMetaEntity.KEY_CLOUD_EMBED_UNAVAILABLE])
    }

    // ---- F5 (REMEDIATION_PLAN): the two stamps are mutually exclusive ------

    @Test
    fun `denied verdict clears a previous entitlement stamp`() = runTest {
        val cloud = CountingEngine(
            EmbeddingEngine.CLOUD_ID,
            EmbeddingEngine.Kind.CLOUD,
            verdict = EmbeddingEngine.Entitlement.Denied(403),
        )
        val meta = FakeMemoryMetaDao()
        // A previous successful probe stamped entitlement; the account is now
        // revoked. Pre-fix both stamps coexisted and the selector still read
        // "entitled" → facts kept egressing to a denied account.
        meta.values[MemoryMetaEntity.KEY_CLOUD_EMBED_ENTITLED] = "1000"
        val runner = BenchmarkRunner(
            metaDao = meta,
            localEmbedder = CountingEngine(EmbeddingEngine.LOCAL_ID, EmbeddingEngine.Kind.LOCAL),
            cloudEmbedder = cloud,
            cloudEnabled = { true },
        )

        runner.run()

        assertNull(
            "the stale entitlement stamp must be cleared on Denied",
            meta.values[MemoryMetaEntity.KEY_CLOUD_EMBED_ENTITLED],
        )
        assertEquals("403", meta.values[MemoryMetaEntity.KEY_CLOUD_EMBED_UNAVAILABLE])
    }

    @Test
    fun `ok verdict clears a previous unavailability stamp`() = runTest {
        val cloud = CountingEngine(EmbeddingEngine.CLOUD_ID, EmbeddingEngine.Kind.CLOUD)
        val meta = FakeMemoryMetaDao()
        meta.values[MemoryMetaEntity.KEY_CLOUD_EMBED_UNAVAILABLE] = "403"
        val runner = BenchmarkRunner(
            metaDao = meta,
            localEmbedder = CountingEngine(EmbeddingEngine.LOCAL_ID, EmbeddingEngine.Kind.LOCAL),
            cloudEmbedder = cloud,
            cloudEnabled = { true },
        )

        runner.run()

        assertNull(
            "a recovered account must not keep a stale denial stamp",
            meta.values[MemoryMetaEntity.KEY_CLOUD_EMBED_UNAVAILABLE],
        )
        assertNotNull(meta.values[MemoryMetaEntity.KEY_CLOUD_EMBED_ENTITLED])
    }

    @Test
    fun `transient verdict leaves both stamps untouched`() = runTest {
        val cloud = CountingEngine(
            EmbeddingEngine.CLOUD_ID,
            EmbeddingEngine.Kind.CLOUD,
            verdict = EmbeddingEngine.Entitlement.Transient(500),
        )
        val meta = FakeMemoryMetaDao()
        meta.values[MemoryMetaEntity.KEY_CLOUD_EMBED_ENTITLED] = "1000"
        val runner = BenchmarkRunner(
            metaDao = meta,
            localEmbedder = CountingEngine(EmbeddingEngine.LOCAL_ID, EmbeddingEngine.Kind.LOCAL),
            cloudEmbedder = cloud,
            cloudEnabled = { true },
        )

        val outcome = runner.run()

        assertEquals("transient", outcome.entitlement)
        assertEquals(
            "a network hiccup must not erase a real verdict",
            "1000",
            meta.values[MemoryMetaEntity.KEY_CLOUD_EMBED_ENTITLED],
        )
        assertNull(meta.values[MemoryMetaEntity.KEY_CLOUD_EMBED_UNAVAILABLE])
    }
}
