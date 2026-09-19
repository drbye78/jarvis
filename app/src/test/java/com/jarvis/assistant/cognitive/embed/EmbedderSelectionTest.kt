package com.jarvis.assistant.cognitive.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedderSelectionTest {

    @Test
    fun `OFF resolves to nothing`() {
        assertNull(
            EmbedderSelection.resolve(
                EmbedderChoice.OFF,
                benchmarkWinner = EmbeddingEngine.LOCAL_ID,
                cloudUsable = true,
                localShipsByCiGate = true,
            ),
        )
    }

    @Test
    fun `LOCAL always resolves locally`() {
        assertEquals(
            EmbeddingEngine.LOCAL_ID,
            EmbedderSelection.resolve(
                EmbedderChoice.LOCAL,
                benchmarkWinner = null,
                cloudUsable = false,
                localShipsByCiGate = false,
            ),
        )
    }

    @Test
    fun `CLOUD fails closed without entitlement`() {
        assertNull(
            EmbedderSelection.resolve(
                EmbedderChoice.CLOUD,
                benchmarkWinner = null,
                cloudUsable = false,
                localShipsByCiGate = false,
            ),
        )
        assertEquals(
            EmbeddingEngine.CLOUD_ID,
            EmbedderSelection.resolve(
                EmbedderChoice.CLOUD,
                benchmarkWinner = null,
                cloudUsable = true,
                localShipsByCiGate = false,
            ),
        )
    }

    @Test
    fun `AUTO prefers the benchmark winner when usable`() {
        assertEquals(
            EmbeddingEngine.CLOUD_ID,
            EmbedderSelection.resolve(
                EmbedderChoice.AUTO,
                benchmarkWinner = EmbeddingEngine.CLOUD_ID,
                cloudUsable = true,
                localShipsByCiGate = false,
            ),
        )
        assertEquals(
            EmbeddingEngine.LOCAL_ID,
            EmbedderSelection.resolve(
                EmbedderChoice.AUTO,
                benchmarkWinner = EmbeddingEngine.LOCAL_ID,
                cloudUsable = false,
                localShipsByCiGate = false,
            ),
        )
    }

    @Test
    fun `AUTO cloud winner without entitlement falls to the CI verdict`() {
        assertEquals(
            EmbeddingEngine.LOCAL_ID,
            EmbedderSelection.resolve(
                EmbedderChoice.AUTO,
                benchmarkWinner = EmbeddingEngine.CLOUD_ID,
                cloudUsable = false,
                localShipsByCiGate = true,
            ),
        )
    }

    @Test
    fun `AUTO without any verdict follows the CI ship-or-reject decision`() {
        assertNull(
            EmbedderSelection.resolve(
                EmbedderChoice.AUTO,
                benchmarkWinner = null,
                cloudUsable = false,
                localShipsByCiGate = false,
            ),
        )
        assertEquals(
            EmbeddingEngine.LOCAL_ID,
            EmbedderSelection.resolve(
                EmbedderChoice.AUTO,
                benchmarkWinner = null,
                cloudUsable = false,
                localShipsByCiGate = true,
            ),
        )
    }

    @Test
    fun `pref parsing falls back to AUTO on garbage`() {
        assertEquals(EmbedderChoice.AUTO, EmbedderChoice.fromPref(null))
        assertEquals(EmbedderChoice.AUTO, EmbedderChoice.fromPref("bogus"))
        assertEquals(EmbedderChoice.LOCAL, EmbedderChoice.fromPref("LOCAL"))
        assertEquals(EmbedderChoice.OFF, EmbedderChoice.fromPref("OFF"))
    }

    // ---- F5 (REMEDIATION_PLAN): usability comes from the recorded verdict --

    @Test
    fun `cloud is usable only with a constructed engine and a clean success stamp`() {
        assertTrue(CloudEntitlement.isUsable(true, "1000", null))
        assertFalse("no engine constructed", CloudEntitlement.isUsable(false, "1000", null))
        assertFalse("never probed", CloudEntitlement.isUsable(true, null, null))
        assertFalse(
            "a denial stamp contradicts the success stamp",
            CloudEntitlement.isUsable(true, "1000", "403"),
        )
        assertFalse(
            "denied with no entitled stamp",
            CloudEntitlement.isUsable(true, null, "403"),
        )
    }

    @Test
    fun `stamps replace each other rather than accumulating`() {
        val ok = CloudEntitlement.nextStamps(EmbeddingEngine.Entitlement.Ok, now = 42L)
        assertEquals(CloudEntitlement.Stamps(entitledAt = 42L, unavailableCode = null), ok)

        val denied = CloudEntitlement.nextStamps(EmbeddingEngine.Entitlement.Denied(403), now = 42L)
        assertEquals(CloudEntitlement.Stamps(entitledAt = null, unavailableCode = 403), denied)

        assertNull(
            "transient is not a verdict — nothing may be written",
            CloudEntitlement.nextStamps(EmbeddingEngine.Entitlement.Transient(500), now = 42L),
        )
    }

    /**
     * The end-to-end consequence: an existence-only `cloudUsable` (the old
     * `cloudEmbedder != null`) routed facts to a DENIED account through both
     * the explicit CLOUD selector and an AUTO cloud winner.
     */
    @Test
    fun `a denied account resolves to no vectors instead of the cloud`() {
        val usable = CloudEntitlement.isUsable(
            engineConstructed = true,
            entitledStamp = null,
            unavailableStamp = "403",
        )
        assertNull(
            EmbedderSelection.resolve(
                choice = EmbedderChoice.CLOUD,
                benchmarkWinner = EmbeddingEngine.CLOUD_ID,
                cloudUsable = usable,
                localShipsByCiGate = false,
            ),
        )
        assertNull(
            EmbedderSelection.resolve(
                choice = EmbedderChoice.AUTO,
                benchmarkWinner = EmbeddingEngine.CLOUD_ID,
                cloudUsable = usable,
                localShipsByCiGate = false,
            ),
        )
    }
}
