package com.jarvis.assistant.integration

import com.jarvis.assistant.llm.TokenManager
import com.jarvis.assistant.speech.tts.SaluteSpeechTts
import io.grpc.ManagedChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P2.2 — LIVE SaluteSpeech TTS smoke test (REMEDIATION_PLAN Phase 2).
 * LOCAL ONLY: runs via `./gradlew :app:integrationTest` when
 * `local.secrets.properties` (or JARVIS_SALUTE_* env vars) provides the
 * Salute OAuth credentials; skips through a JUnit assumption everywhere else
 * (CI has no credentials and can never fail because of this class).
 *
 * Round trip: a FIXED non-personal probe phrase ([TTS_PROBE_TEXT]) — no user
 * text is ever synthesized — must come back as a NON-EMPTY audio payload that
 * the flow delivers completely (stream terminates normally). Payload size is
 * sanity-capped so a misbehaving service cannot blow the test budget; no
 * content assertions beyond emptiness/size (the voice audio itself is not
 * validated — that is the Android player lane's job).
 */
class SaluteTtsLiveSmokeTest {

    private companion object {
        const val TTS_DEADLINE_MS = 20_000L
        const val ROUND_TRIP_TIMEOUT_MS = 45_000L

        /** 24 kHz 16-bit mono ≈ 48 KB/s; a probe phrase must stay under 4 s. */
        const val MAX_EXPECTED_AUDIO_BYTES = 192_000
    }

    private lateinit var channel: ManagedChannel
    private lateinit var tokenManager: TokenManager

    @Before
    fun requireSaluteCredentials() {
        LiveSecrets.assumeSalute("Salute TTS live smoke test")
        channel = saluteChannel()
        tokenManager = liveTokenManager(LiveSecrets.secrets)
    }

    @After
    fun tearDown() {
        if (::channel.isInitialized) channel.shutdownNow()
    }

    @Test
    fun `probe phrase synthesis returns a non-empty audio payload`() = runBlocking {
        val tts = SaluteSpeechTts(tokenManager, channel, deadlineMs = TTS_DEADLINE_MS)
        val chunks = withTimeout(ROUND_TRIP_TIMEOUT_MS) {
            tts.synthesizeStream(TTS_PROBE_TEXT, voice = "Mila").toList()
        }
        assertTrue("expected at least one audio chunk", chunks.isNotEmpty())
        val totalBytes = chunks.sumOf { it.size }
        assertTrue(
            "expected a non-empty audio payload, got $totalBytes bytes across ${chunks.size} chunks",
            totalBytes > 0,
        )
        assertTrue(
            "probe-phrase payload suspiciously large: $totalBytes bytes (cap $MAX_EXPECTED_AUDIO_BYTES)",
            totalBytes <= MAX_EXPECTED_AUDIO_BYTES,
        )
    }
}
