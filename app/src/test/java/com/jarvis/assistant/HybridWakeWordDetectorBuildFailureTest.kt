package com.jarvis.assistant

import com.jarvis.assistant.audio.HybridWakeWordDetector
import com.jarvis.assistant.contracts.DetectorState
import com.jarvis.assistant.contracts.WakeWordRequest
import com.jarvis.assistant.util.CredentialsStore
import com.jarvis.assistant.util.InMemoryVault
import com.jarvis.assistant.util.SecretVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D2: the user-facing wake-word failure reason must be HONEST.
 *
 * The old code hardcoded "Sherpa model failed to load (bundled assets
 * missing)" for ANY Sherpa build failure — but the APK actually ships the
 * bundled assets, so that reason was false. The reason is now classified from
 * the captured exception CLASS (content-free) and the Picovoice-key
 * availability, never from a hardcoded cause.
 */
class HybridWakeWordDetectorBuildFailureTest {

    /** Marker with a distinctive type + message so the reason can be checked. */
    private class MarkerBuildFailure : RuntimeException("marker-message-should-not-leak")

    private fun request(engine: String) = WakeWordRequest(
        engine = engine,
        keywordPath = null,
        sherpaModelDir = null,
        sherpaCustomKeyword = "",
        sensitivity = 0.6f,
        stopPhraseEnabled = true,
    )

    private fun failingDetector(engine: String) = HybridWakeWordDetector(
        frames = emptyFlow(),
        context = null,
        initialReq = request(engine),
        engineFactory = { _ -> throw MarkerBuildFailure() },
        engineBuildDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `sherpa failure names the exception class and never claims bundled assets are missing`() {
        val detector = failingDetector("sherpa")
        try {
            val state = detector.state.value
            assertTrue("expected Failed but was $state", state is DetectorState.Failed)
            val reason = (state as DetectorState.Failed).reason
            assertTrue(
                "reason must name the captured failure class: $reason",
                reason.contains("MarkerBuildFailure"),
            )
            assertFalse(
                "reason must NOT claim the bundled assets are missing: $reason",
                reason.contains("bundled assets missing"),
            )
            // Content-free: the exception MESSAGE must not leak.
            assertFalse("reason must not leak the exception message: $reason", reason.contains("marker-message"))
        } finally {
            detector.release()
        }
    }

    @Test
    fun `porcupine failure with a key present reports engine unavailable, not key missing`() {
        // Guarantee the singleton reports a Picovoice key: use the documented
        // JVM test seam, or (if another test already initialised it) write the
        // key into the live store.
        val existing = CredentialsStore.peek()
        if (existing != null) {
            existing.picovoiceKey = "pv-test-key"
        } else {
            CredentialsStore.initForTests(
                InMemoryVault().apply { putString(SecretVault.KEY_PICOVOICE, "pv-test-key") },
            )
        }

        val detector = failingDetector("porcupine")
        try {
            val state = detector.state.value
            assertTrue("expected Failed but was $state", state is DetectorState.Failed)
            val reason = (state as DetectorState.Failed).reason
            assertTrue(
                "a present key + build failure must report the engine unavailable: $reason",
                reason.contains("engine unavailable"),
            )
            assertTrue(
                "reason should still name the failure class: $reason",
                reason.contains("MarkerBuildFailure"),
            )
            assertFalse(
                "a present key must NOT be reported as missing: $reason",
                reason.contains("key is missing"),
            )
        } finally {
            detector.release()
        }
    }
}
