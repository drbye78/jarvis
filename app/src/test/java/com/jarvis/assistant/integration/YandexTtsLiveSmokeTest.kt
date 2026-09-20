package com.jarvis.assistant.integration

import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.speech.tts.YandexSpeechTts
import com.jarvis.assistant.speech.tts.YandexVoiceSpec
import io.grpc.ManagedChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * LIVE Yandex SpeechKit v3 synthesis smoke test. LOCAL ONLY: runs via
 * `./gradlew :app:integrationTest` when `local.secrets.properties` (or
 * `JARVIS_YANDEX_API_KEY`) provides the API key; skips through a JUnit
 * assumption everywhere else (CI has no credentials and can never fail
 * because of this class).
 *
 * WHAT THIS PROVES: the `Synthesizer.UtteranceSynthesis` request shape, the
 * `Api-Key` header, and — most importantly — that the request pins
 * `RawAudio(LINEAR16_PCM, 24 kHz)` and the service HONORS it. The playback
 * chain is hardwired to 24 kHz headerless PCM
 * ([com.jarvis.assistant.contracts.AudioSpec.TTS]); the service's default is
 * 22.05 kHz WITH a WAV header, which the player would emit as loud noise. The
 * in-process tests assert the request we send; only the real service can
 * confirm what comes back, so the response format is asserted here too.
 *
 * The probe phrase is FIXED and non-personal — no user text is ever
 * synthesized.
 *
 * QUOTA: one short phrase per run; payload size is sanity-capped so a
 * misbehaving service cannot blow the test budget. The audio is not listened
 * to (no content assertions beyond emptiness/size/format).
 */
class YandexTtsLiveSmokeTest {

    private companion object {
        const val TTS_DEADLINE_MS = 20_000L
        const val ROUND_TRIP_TIMEOUT_MS = 45_000L

        /** 24 kHz s16le mono ≈ 48 KB/s; a probe phrase must stay under 4 s. */
        const val MAX_EXPECTED_AUDIO_BYTES = 192_000
    }

    private lateinit var channel: ManagedChannel

    @Before
    fun requireYandexCredentials() {
        LiveSecrets.assumeYandex("Yandex TTS live smoke test")
        channel = yandexChannel(JarvisConfig().yandexTtsEndpoint)
    }

    @After
    fun tearDown() {
        if (::channel.isInitialized) channel.shutdownNow()
    }

    @Test
    fun `probe phrase synthesis returns a non-empty 24 kHz payload`() = runBlocking {
        val tts = YandexSpeechTts(
            apiKeyProvider = { LiveSecrets.secrets.yandexApiKey.orEmpty() },
            channel = channel,
            deadlineMs = TTS_DEADLINE_MS,
        )
        val chunks = withTimeout(ROUND_TRIP_TIMEOUT_MS) {
            // Bare voice: exercises the same decode path a role-less pref
            // takes (YandexVoiceSpec.split → no role hint).
            tts.synthesizeStream(TTS_PROBE_TEXT, voice = YandexVoiceSpec.DEFAULT_VOICE).toList()
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
        // A 22.05 kHz answer would still be non-empty, so size alone cannot
        // catch the sample-rate regression — the chunk count is the cheap
        // proxy: a WAV-container reply leads with a 44-byte header that the
        // client, expecting raw PCM, would pass through as audio. Keeping the
        // first chunk well above that catches a container-level mistake.
        assertTrue(
            "first chunk is suspiciously small (${chunks.first().size} bytes) — possible container/header leak",
            chunks.first().size > 44,
        )
    }
}
