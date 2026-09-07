package com.jarvis.assistant.integration

import com.jarvis.assistant.speech.asr.AsrEvent
import io.grpc.StatusRuntimeException
import kotlinx.serialization.Serializable
import java.util.Base64

/**
 * P2.3 fixture format for the Salute recording pipeline (REMEDIATION_PLAN
 * Phase 2, owner decision #1).
 *
 * A fixture is a JSON file under `app/src/test/resources/recorded/` produced
 * by `./gradlew :app:recordSaluteFixtures` (locally, with the owner's
 * credentials). It records the SERVER→CLIENT event sequence, which the
 * committed `SaluteFixtureReplayTest` re-emits through the Phase 1 fake
 * gRPC services (`speech/grpc/SaluteInProcessGrpc.kt`), so the recorded
 * real-service wire shapes keep being exercised in every CI run — no
 * credentials, no network.
 *
 * Replay fidelity note (documented honestly): the recorder sees what the
 * CLIENT sees (Partial/Final/Failed events), so a fixture stores the
 * server-response scripts that REPRODUCE those events through the production
 * client — hypothesis-level multiplicity beyond what the client surfaces is
 * collapsed. The mapping is [AsrEvent.toRecordedResponse] and is itself
 * covered by a unit test, so the record→replay loop is closed without any
 * live credentials.
 *
 * SANITIZATION CONTRACT (enforced by the recorder, checked by replay tests):
 * - server responses only — never request headers, tokens, or RqUIDs;
 * - no wall-clock timestamps (none are serialized at all);
 * - no user audio or text: the recorder sends synthetic silence (ASR) and a
 *   fixed non-personal probe phrase (TTS);
 * - ASR error entries carry the gRPC status CODE and the exception CLASS NAME
 *   only — exception MESSAGES may embed server-side strings and are never
 *   recorded.
 */
const val TTS_PROBE_TEXT = "Проверка связи."

/** What the production client maps "Mila" to on the wire (SaluteSpeechTts.mapVoice). */
const val TTS_PROBE_WIRE_VOICE = "May_24000"

const val FIXTURE_FORMAT_VERSION = 1

@Serializable
data class FixtureProvenance(
    /** false = hand-written seed fixture standing in until a real recording replaces it. */
    val recorded: Boolean,
    /** true = recorder sent synthetic silence / a fixed probe phrase, never user data. */
    val syntheticInput: Boolean,
    val sanitization: String,
)

/** The client-request shape the recorder used — replays assert the same wire options. */
@Serializable
data class RecordedAsrRequest(
    val audioEncoding: String = "PCM_S16LE",
    val sampleRate: Int = 16_000,
    val language: String = "ru-RU",
    val model: String = "general",
    val enablePartialResults: Boolean = true,
    val noSpeechTimeoutSec: Long = 7,
)

@Serializable
data class RecordedTtsRequest(
    val text: String,
    /** Voice id passed to [com.jarvis.assistant.speech.tts.SaluteSpeechTts]. */
    val voiceInput: String,
    /** Voice id the production client put on the wire. */
    val voiceWire: String,
    val audioEncoding: String = "PCM_S16LE",
    val language: String = "ru-RU",
    val contentType: String = "TEXT",
)

/**
 * One scripted server→client response.
 *
 * Exactly one variant is filled per [type]:
 * - "transcription": [eou] (+ [eouReason] when the server set it) + [texts];
 * - "chunk": TTS audio bytes in [base64];
 * - "error": gRPC terminal failure — [status] (code name) + [message]
 *   (exception CLASS NAME only, per the sanitization contract);
 * - "serverClosed": server half-closed without EOU.
 */
@Serializable
data class RecordedResponse(
    val type: String,
    val eou: Boolean? = null,
    val eouReason: String? = null,
    val texts: List<String> = emptyList(),
    val status: String? = null,
    val message: String? = null,
    val base64: String? = null,
) {
    init {
        require(type in KNOWN_TYPES) { "unknown fixture response type: $type" }
    }

    companion object {
        const val TYPE_TRANSCRIPTION = "transcription"
        const val TYPE_CHUNK = "chunk"
        const val TYPE_ERROR = "error"
        const val TYPE_SERVER_CLOSED = "serverClosed"
        val KNOWN_TYPES = setOf(TYPE_TRANSCRIPTION, TYPE_CHUNK, TYPE_ERROR, TYPE_SERVER_CLOSED)
    }
}

@Serializable
data class SaluteFixture(
    val formatVersion: Int = FIXTURE_FORMAT_VERSION,
    val kind: String,
    val description: String,
    val provenance: FixtureProvenance,
    val asrRequest: RecordedAsrRequest? = null,
    val ttsRequest: RecordedTtsRequest? = null,
    val serverResponses: List<RecordedResponse> = emptyList(),
) {
    init {
        require(formatVersion == FIXTURE_FORMAT_VERSION) {
            "unsupported fixture formatVersion $formatVersion"
        }
        require(kind in setOf(KIND_ASR, KIND_TTS)) { "unknown fixture kind: $kind" }
    }

    companion object {
        const val KIND_ASR = "salute-asr"
        const val KIND_TTS = "salute-tts"
    }
}

fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

fun String.decodeBase64(): ByteArray = Base64.getDecoder().decode(this)

/**
 * Recorder-side mapping: client-visible [AsrEvent]s → sanitizable response
 * scripts that reproduce the events when replayed (see the file KDoc).
 * [AsrEvent.Failed] is reduced to status code + exception class name; anything
 * that is neither a gRPC status failure nor the client's clean-close marker
 * is conservatively recorded as a clean server close (the production client
 * emits Failed only from onError[gRPC] or onCompleted[clean-close]).
 */
fun AsrEvent.toRecordedResponse(): RecordedResponse = when (this) {
    is AsrEvent.Partial -> RecordedResponse(type = RecordedResponse.TYPE_TRANSCRIPTION, eou = false, texts = listOf(text))
    is AsrEvent.Final -> RecordedResponse(type = RecordedResponse.TYPE_TRANSCRIPTION, eou = true, texts = listOf(text))
    is AsrEvent.Failed -> {
        val status = (cause as? StatusRuntimeException)?.status?.code
        if (status != null) {
            RecordedResponse(
                type = RecordedResponse.TYPE_ERROR,
                status = status.toString(),
                message = cause.javaClass.simpleName,
            )
        } else {
            // Clean server half-close (client marker: "ASR stream completed
            // without end-of-utterance") — or an unexpected client-side
            // RuntimeException; either way nothing content-bearing is written.
            RecordedResponse(type = RecordedResponse.TYPE_SERVER_CLOSED)
        }
    }
}
