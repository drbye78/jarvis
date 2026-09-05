package com.jarvis.assistant.cognitive.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
