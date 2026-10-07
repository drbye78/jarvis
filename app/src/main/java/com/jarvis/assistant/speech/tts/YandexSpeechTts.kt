package com.jarvis.assistant.speech.tts

import com.jarvis.assistant.grpc.yandextts.AudioFormatOptions
import com.jarvis.assistant.grpc.yandextts.Hints
import com.jarvis.assistant.grpc.yandextts.RawAudio
import com.jarvis.assistant.grpc.yandextts.SynthesizerGrpc
import com.jarvis.assistant.grpc.yandextts.UtteranceSynthesisRequest
import com.jarvis.assistant.grpc.yandextts.UtteranceSynthesisResponse
import com.jarvis.assistant.speech.SpeechBackend
import com.jarvis.assistant.speech.yandexApiKeyStub
import io.grpc.Context
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Yandex SpeechKit v3 TTS over the server-streaming
 * `Synthesizer.UtteranceSynthesis` RPC.
 *
 * AUTH: a service-account API key read lazily per synthesis via
 * [apiKeyProvider] — the vault is never touched at graph-construction time.
 *
 * VOICE SELECTION: the request carries its settings as a repeated `Hints`
 * list, each entry a scalar. Up to three hints are emitted:
 * - the speaker name ([Hints.setVoice]) — always,
 * - optionally a pronunciation ROLE ([Hints.setRole]), and
 * - optionally a speaking RATE ([Hints.setSpeed]).
 *
 * Because the [TtsClient] contract passes a single `voice` string, all three
 * travel in-band as `"<voice>[:<role>][@<speed>]"` (e.g. `marina:whisper@1.5`)
 * — packed and unpacked by [YandexVoiceSpec], the single definition of that
 * convention. A value with no `:` (or an empty role after it) yields the voice
 * hint alone and lets the service apply its default role; a `1.0`/absent speed
 * yields no speed hint. This keeps the provider-neutral contract unchanged
 * while still exposing Yandex's role and speed features.
 *
 * FAIL-CLOSED VOICE AND ROLE: an unknown speaker or an undocumented voice/role
 * pair is a HARD service error, not a fallback, so both are re-validated here
 * (the runtime chokepoint that stops a stale pref or a hand-crafted spec from
 * failing the whole synthesis): the speaker is resolved against
 * [VoiceCatalog.YANDEX_VOICES] via [VoiceResolver] and the role against
 * [VoiceCatalog.validRoleFor].
 *
 * DEGRADATION: if a non-default speaker is nonetheless rejected with
 * [Status.Code.PERMISSION_DENIED] or [Status.Code.INVALID_ARGUMENT] (e.g. the
 * account lacks that voice, or the request shape is rejected), the sentence is
 * retried ONCE with [YandexVoiceSpec.DEFAULT_VOICE]. A barge-in
 * ([Status.Code.CANCELLED]) ALWAYS closes normally — never retried, never
 * masked as an error.
 *
 * SAMPLE-RATE CONSTRAINT (this is the correctness-critical part): Yandex
 * defaults to **22050 Hz LINEAR16 PCM wrapped in a WAV header**. The entire
 * playback chain instead assumes 24 kHz HEADERLESS PCM —
 * [com.jarvis.assistant.contracts.AudioSpec.TTS] is the contract AudioTrack is
 * opened with, and the AEC far-end tap resamples 24 kHz → 16 kHz. Yandex is
 * one of the few providers that lets the rate be requested explicitly, so
 * [synthesizeStream] ALWAYS pins it: an omitted `output_audio_spec` would play
 * ~9 % slow and feed the echo canceller a wrongly-rated reference signal.
 * Unlike [SaluteSpeechTts] — whose proto cannot express a rate at all — there
 * is nothing to detect here, only to request correctly.
 *
 * CANCELLATION: the RPC runs under a cancellable [Context]; a downstream
 * cancellation (barge-in, session end) aborts it server-side instead of
 * leaking the synthesis, and a gRPC [Status.Code.CANCELLED] is treated as a
 * normal close (`awaitClose` cancels the RPC on the way out).
 */
class YandexSpeechTts(
    private val apiKeyProvider: () -> String,
    private val channel: ManagedChannel,
    private val deadlineMs: Long = 20_000,
) : TtsClient {

    private companion object {
        /**
         * PCM rate the whole pipeline assumes
         * ([com.jarvis.assistant.contracts.AudioSpec.TTS]). Requested
         * explicitly on every synthesis — see the class KDoc.
         */
        const val TTS_SAMPLE_RATE_HERTZ = 24_000L
    }

    override fun synthesizeStream(text: String, voice: String): Flow<ByteArray> = channelFlow {
        val producerScope = this
        val cancellableContext = Context.current().withCancellation()

        val producer = launch(Dispatchers.IO) {
            val firstFailure = producerScope.attemptOnce(text, voice, cancellableContext)
            if (firstFailure == null) {
                // onCompleted, or the expected CANCELLED barge-in: close
                // normally. A cancel must NEVER be retried or surfaced.
                producerScope.close()
                return@launch
            }

            val code = statusCodeOf(firstFailure)
            val effectiveSpeaker = VoiceResolver.resolve(
                SpeechBackend.YANDEX,
                YandexVoiceSpec.split(voice).voice,
                YandexVoiceSpec.DEFAULT_VOICE,
            )
            val alreadyDefault = effectiveSpeaker.equals(YandexVoiceSpec.DEFAULT_VOICE, ignoreCase = true)
            if (alreadyDefault ||
                (code != Status.Code.PERMISSION_DENIED && code != Status.Code.INVALID_ARGUMENT)
            ) {
                // No degraded retry is possible or warranted: the default
                // itself failed, or the failure is not voice-shaped. Surface it
                // once; the caller's sentence-level catch owns the wording.
                // Content-free: the code only, never the server message.
                Timber.w("Yandex TTS synthesis failed (status=%s)", code)
                producerScope.close(firstFailure)
                return@launch
            }

            // Content-free: the code only, never the voice text. One retry with
            // the backend default so a stale/hand-crafted speaker cannot abort
            // the whole spoken sentence.
            Timber.w("Yandex TTS rejected the configured voice; retrying with the default (status=%s)", code)
            val retryFailure = producerScope.attemptOnce(
                text,
                YandexVoiceSpec.DEFAULT_VOICE,
                cancellableContext,
            )
            if (retryFailure == null) producerScope.close() else producerScope.close(retryFailure)
        }

        awaitClose {
            producer.cancel()
            cancellableContext.cancel(Status.CANCELLED.asException())
        }
    }

    /**
     * Runs exactly ONE `UtteranceSynthesis` RPC and pumps its audio chunks into
     * the enclosing flow.
     *
     * Returns null when the attempt ended normally (`onCompleted`) or was
     * cancelled by the caller ([Status.Code.CANCELLED] — a barge-in, which must
     * close the flow normally, never as an error and never as a retry trigger),
     * or the failure [Throwable] otherwise.
     *
     * A chunk is delivered through a spawned child so the non-suspending gRPC
     * callback can apply backpressure, and the attempt joins the children
     * spawned so far before completing — otherwise a synchronous completion
     * from `onCompleted`/`onError` can outrun a pending `send()` and drop the
     * final audio chunk. gRPC delivers an observer's callbacks serially on one
     * thread, so no new child can appear after `onCompleted`/`onError`. (Same
     * reasoning as [SaluteSpeechTts]; see its long comment for the failed
     * monitor-based alternative.)
     */
    private suspend fun ProducerScope<ByteArray>.attemptOnce(
        text: String,
        voice: String,
        cancellableContext: Context,
    ): Throwable? {
        val outcome = CompletableDeferred<Throwable?>()
        val childJobs = ArrayList<Job>()
        val scope = this

        val responseObserver = object : StreamObserver<UtteranceSynthesisResponse> {
            override fun onNext(value: UtteranceSynthesisResponse) {
                val bytes = value.audioChunk.data
                if (!bytes.isEmpty) {
                    childJobs += scope.launch { scope.send(bytes.toByteArray()) }
                }
            }

            override fun onError(t: Throwable) {
                // The async stub delivers StatusRuntimeException (not
                // StatusException); check both so an expected barge-in cancel
                // does not surface as a flow failure.
                val failure = if (statusCodeOf(t) == Status.Code.CANCELLED) null else t
                scope.launch {
                    childJobs.joinAll()
                    outcome.complete(failure)
                }
            }

            override fun onCompleted() {
                scope.launch {
                    childJobs.joinAll()
                    outcome.complete(null)
                }
            }
        }

        return try {
            val stub = yandexApiKeyStub(
                this@YandexSpeechTts.channel,
                apiKeyProvider(),
                deadlineMs,
                SynthesizerGrpc::newStub,
            )
            cancellableContext.run {
                stub.utteranceSynthesis(attemptRequest(text, voice), responseObserver)
            }
            outcome.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            Timber.e(e, "Yandex TTS network error")
            e
        } catch (e: Exception) {
            e
        }
    }

    /** Builds the request for one attempt: text, validated hints, pinned 24 kHz raw PCM. */
    private fun attemptRequest(text: String, voice: String): UtteranceSynthesisRequest =
        UtteranceSynthesisRequest.newBuilder()
            .setText(text)
            .addAllHints(hintsFor(voice))
            .setOutputAudioSpec(
                AudioFormatOptions.newBuilder().setRawAudio(
                    RawAudio.newBuilder()
                        .setAudioEncoding(RawAudio.AudioEncoding.LINEAR16_PCM)
                        .setSampleRateHertz(TTS_SAMPLE_RATE_HERTZ),
                ),
            )
            .build()

    /** gRPC status of [t], tolerating both `StatusException` and `StatusRuntimeException`. */
    private fun statusCodeOf(t: Throwable): Status.Code? =
        (t as? io.grpc.StatusException)?.status?.code
            ?: (t as? io.grpc.StatusRuntimeException)?.status?.code

    /**
     * Unpacks the `"<voice>[:<role>][@<speed>]"` convention ([YandexVoiceSpec],
     * the single definition of the packing) into the `Hints` list the request
     * needs. `Hints` is a scalar `oneof`, so each field is its own entry:
     * the voice hint is always present; the role hint only when the voice
     * DOCUMENTS that role (fail-closed); the speed hint only when it is set and
     * differs from the service default.
     *
     * The speaker is re-validated against [VoiceCatalog.YANDEX_VOICES] here —
     * the LAST chokepoint before the wire — so a stale pref or a hand-crafted
     * spec can never send a foreign id.
     */
    private fun hintsFor(voice: String): List<Hints> {
        val split = YandexVoiceSpec.split(voice)
        val requested = split.voice.trim()
        val speaker = VoiceResolver.resolve(
            SpeechBackend.YANDEX,
            requested,
            YandexVoiceSpec.DEFAULT_VOICE,
        )
        if (requested.isNotEmpty() &&
            VoiceCatalog.YANDEX_VOICES.none { it.id.equals(requested, ignoreCase = true) }
        ) {
            // Content-free: never log the voice text. A foreign id (e.g. a Sber
            // "Mila") is replaced before it can reach the service.
            Timber.w("Yandex TTS: unknown voice id replaced with the default speaker")
        }

        val hints = ArrayList<Hints>(3)
        hints += Hints.newBuilder().setVoice(speaker).build()

        val role = VoiceCatalog.validRoleFor(speaker, split.role)
        if (role != null) {
            hints += Hints.newBuilder().setRole(role).build()
        } else if (split.role != null) {
            // Content-free: never log the voice or the role text. A stale pref
            // is dropped rather than sent as a hard service error.
            Timber.w("Yandex TTS: dropped an undocumented role for the selected voice")
        }

        val speed = split.speed
        if (speed != null && speed != YandexVoiceSpec.DEFAULT_SPEED) {
            hints += Hints.newBuilder().setSpeed(speed.toDouble()).build()
        }
        return hints
    }
}
