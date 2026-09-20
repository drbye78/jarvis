package com.jarvis.assistant.integration

import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.speech.tts.YandexSpeechTts
import com.jarvis.assistant.speech.tts.YandexVoiceSpec
import io.grpc.ManagedChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
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
 * WHAT THIS PROVES: the `Synthesizer.UtteranceSynthesis` request shape and the
 * `Api-Key` header are accepted by the real service, and the reply is RAW PCM
 * and not a container — the service's default is 22.05 kHz **with a WAV
 * header**, and the playback chain is hardwired to 24 kHz headerless PCM
 * ([com.jarvis.assistant.contracts.AudioSpec.TTS]), so a container reply would
 * be handed to AudioTrack as audio (click + wrong pitch). The in-process tests
 * pin the `RawAudio(LINEAR16_PCM, 24_000)` we SEND; this test pins that no
 * wrapper comes BACK.
 *
 * WHAT THIS DOES NOT PROVE: that the service honored the requested sample
 * RATE. Byte streams cannot show that (22.05 kHz output is equally non-empty
 * and equally sample-aligned) — see [assertNoContainerWrapper] for why adding
 * a second request rate was rejected.
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

        /**
         * Leading bytes of the containers the service could plausibly return
         * instead of raw PCM: WAV/RIFF, OGG (a documented `ContainerAudio`
         * option), MP3 with an ID3 tag, and FLAC. Only offset-0 signatures are
         * listed — ISO-BMFF puts its brand at offset 4, which raw PCM can
         * coincidentally match, so checking it would cost false positives for
         * no real coverage (the service cannot return M4A for a PCM request).
         */
        val CONTAINER_MAGICS: List<Pair<ByteArray, String>> = listOf(
            "RIFF".toByteArray(Charsets.US_ASCII) to "RIFF/WAV",
            "OggS".toByteArray(Charsets.US_ASCII) to "OggS",
            "ID3".toByteArray(Charsets.US_ASCII) to "ID3/MP3",
            "fLaC".toByteArray(Charsets.US_ASCII) to "fLaC",
        )
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
    fun `probe phrase synthesis returns raw non-empty PCM without a container wrapper`() = runBlocking {
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

        // The load-bearing assertion: we asked for RawAudio, so the payload must
        // be headerless PCM. Yandex's DEFAULT is a WAV container, and the client
        // (correctly) does not strip headers — it passes bytes to a player that
        // expects raw 24 kHz PCM. A container reply would therefore be fed to
        // AudioTrack as audio, producing a click plus wrong-pitch speech. Size
        // and non-emptiness cannot catch that; the leading magic bytes can.
        assertNoContainerWrapper(chunks.first())

        // Raw 16-bit mono PCM has 2 bytes per sample, so the stream length must
        // be sample-aligned. An odd length means the byte stream is not what the
        // player is being told it is (wrong encoding/framing), independent of
        // the sample rate.
        assertEquals(
            "raw S16LE mono payload must be sample-aligned (2 bytes/sample), got $totalBytes",
            0,
            totalBytes % 2,
        )
    }

    /**
     * Fails when the reply leads with a known container header.
     *
     * WHAT THIS DOES NOT PROVE: that the service actually honored
     * `sample_rate_hertz = 24_000`. Bytes alone cannot show that — a 22.05 kHz
     * stream is just as non-empty and just as even. The rate is pinned on the
     * REQUEST side by the in-process test (which asserts the serialized
     * `RawAudio(LINEAR16_PCM, 24000)`), and this test proves the response is
     * raw PCM rather than a default WAV container; the service honoring the
     * field is documented contract. Verifying the rate independently would
     * require re-requesting at a second rate, which means exposing a production
     * knob that MUST stay hardwired to 24 kHz to match the playback chain — a
     * worse trade than this honest limit.
     */
    private fun assertNoContainerWrapper(firstChunk: ByteArray) {
        val magic = CONTAINER_MAGICS.firstOrNull { (magic, _) ->
            firstChunk.size >= magic.size &&
                magic.indices.all { firstChunk[it] == magic[it] }
        }
        assertTrue(
            "response leads with a ${magic?.second ?: ""} container header " +
                "(${firstChunk.take(4).joinToString(" ") { "%02X".format(it) }}) — " +
                "the client requested RawAudio and does not strip headers",
            magic == null,
        )
    }
}
