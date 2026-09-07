package com.jarvis.assistant.integration

import com.jarvis.assistant.speech.asr.AsrEvent
import io.grpc.Status
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Closes the record→replay loop WITHOUT live credentials: the recorder's
 * event→fixture mapping ([AsrEvent.toRecordedResponse]) must produce
 * schema-valid, sanitization-safe responses that survive a JSON round trip
 * byte-for-byte. The end-to-end replay (fixture → fake service → production
 * client) is covered by `SaluteFixtureReplayTest` in the normal CI suite.
 */
class RecordedFixtureMappingTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `asr events map to sanitizable responses that survive a JSON round trip`() {
        val events = listOf(
            AsrEvent.Partial("при"),
            AsrEvent.Partial("привет"),
            AsrEvent.Final("привет как дела"),
            AsrEvent.Failed(Status.UNAVAILABLE.asRuntimeException()),
            AsrEvent.Failed(RuntimeException("ASR stream completed without end-of-utterance")),
        )

        val responses = events.map { it.toRecordedResponse() }

        assertEquals(
            listOf("transcription", "transcription", "transcription", "error", "serverClosed"),
            responses.map { it.type },
        )
        assertEquals(false, responses[0].eou)
        assertEquals(listOf("при"), responses[0].texts)
        assertEquals(true, responses[2].eou)
        assertEquals(listOf("привет как дела"), responses[2].texts)

        // Sanitization: error entries carry the status CODE and exception CLASS
        // NAME only — the exception message ("UNAVAILABLE: details from the
        // server") must never appear.
        val error = responses[3]
        assertEquals("UNAVAILABLE", error.status)
        assertEquals("StatusRuntimeException", error.message)
        assertEquals(false, error.message!!.contains(":"))

        // JSON round trip through the exact schema the replay tests consume.
        val fixture = SaluteFixture(
            kind = SaluteFixture.KIND_ASR,
            description = "mapping test fixture",
            provenance = FixtureProvenance(
                recorded = false,
                syntheticInput = true,
                sanitization = "test",
            ),
            asrRequest = RecordedAsrRequest(),
            serverResponses = responses,
        )
        val decoded = json.decodeFromString(
            SaluteFixture.serializer(),
            json.encodeToString(SaluteFixture.serializer(), fixture),
        )
        assertEquals(fixture, decoded)
        assertEquals(responses, decoded.serverResponses)
    }
}
