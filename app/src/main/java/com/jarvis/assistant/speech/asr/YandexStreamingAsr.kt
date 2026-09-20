package com.jarvis.assistant.speech.asr

import com.google.protobuf.ByteString
import com.jarvis.assistant.grpc.yandexstt.AlternativeUpdate
import com.jarvis.assistant.grpc.yandexstt.AudioChunk
import com.jarvis.assistant.grpc.yandexstt.AudioFormatOptions
import com.jarvis.assistant.grpc.yandexstt.CodeType
import com.jarvis.assistant.grpc.yandexstt.DefaultEouClassifier
import com.jarvis.assistant.grpc.yandexstt.EouClassifierOptions
import com.jarvis.assistant.grpc.yandexstt.LanguageRestrictionOptions
import com.jarvis.assistant.grpc.yandexstt.RawAudio
import com.jarvis.assistant.grpc.yandexstt.RecognitionModelOptions
import com.jarvis.assistant.grpc.yandexstt.RecognizerGrpc
import com.jarvis.assistant.grpc.yandexstt.StatusCode
import com.jarvis.assistant.grpc.yandexstt.StreamingOptions
import com.jarvis.assistant.grpc.yandexstt.StreamingRequest
import com.jarvis.assistant.grpc.yandexstt.StreamingResponse
import com.jarvis.assistant.speech.yandexApiKeyStub
import io.grpc.Context
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Yandex SpeechKit v3 streaming ASR over the bidi
 * `Recognizer.RecognizeStreaming` RPC.
 *
 * The reliability shape deliberately mirrors [SberStreamingAsr] — the two
 * providers must behave identically as far as the session layer is concerned:
 * - The RPC runs under a cancellable [Context], so [AsrStream.cancel] aborts
 *   the call immediately rather than leaking it until the server gives up.
 * - A gRPC deadline caps the whole utterance ([deadlineMs]).
 * - The server owns end-of-utterance detection; `final` is emitted only once
 *   the server's EOU classifier fires, and maps to [AsrEvent.Final].
 * - A terminal event is emitted AT MOST ONCE, so a late `Failed` can never
 *   follow a `Final`.
 *
 * PROTOCOL ORDER: `session_options` MUST be the first message on the stream
 * (the vendored proto says so explicitly). [start] therefore sends it eagerly
 * before returning, and only then can the session layer begin feeding audio.
 *
 * FORMAT: input is pinned to `LINEAR16_PCM` at 16 kHz mono — exactly
 * [com.jarvis.assistant.contracts.AudioSpec.MIC] — because the frames
 * `AudioPipeline` produces are 16 kHz mono 16-bit. No container is used.
 *
 * WHAT IS NOT SURFACED: `eou_update` (the session already ended the turn on
 * `final`) and `final_refinement` (post-final normalized text arriving after
 * the transcript was delivered) are ignored — the
 * [com.jarvis.assistant.speech.asr.AsrEvent] contract has no event for them.
 * `status_code` is inspected ONLY for a content-free DEBUG warning.
 *
 * NO-SPEECH: there is no client-side silence timer here. The session layer
 * already caps an utterance ([config.maxUtteranceMs] → `finish()` → grace
 * window → NoSpeech), and the Yandex v3 options carry no no-speech timeout to
 * map onto — inventing a second timer would race that cap.
 */
class YandexStreamingAsr(
    private val apiKeyProvider: () -> String,
    private val channel: ManagedChannel,
    private val deadlineMs: Long = 90_000,
) : StreamingAsrClient {

    override suspend fun open(): AsrStream = withContext(Dispatchers.IO) {
        YandexAsrStream(channel, apiKeyProvider(), deadlineMs).also { it.start() }
    }

    private class YandexAsrStream(
        private val channel: ManagedChannel,
        private val apiKey: String,
        private val deadlineMs: Long,
    ) : AsrStream {

        private companion object {
            /**
             * Input rate the mic lane produces
             * ([com.jarvis.assistant.contracts.AudioSpec.MIC]).
             */
            const val MIC_SAMPLE_RATE_HERTZ = 16_000L

            /** Cloud recognition model — the real-time general-purpose one. */
            const val ASR_MODEL = "general"

            const val LANGUAGE_TAG = "ru-RU"
        }

        // replay=1 redelivers a terminal event emitted in the window between
        // open() and the session's subscription — an instant server error
        // would otherwise be dropped and hang the session in LISTENING until
        // the hard cap. extraBufferCapacity keeps early emissions from being
        // lost before the collector subscribes.
        private val _events = MutableSharedFlow<AsrEvent>(
            replay = 1,
            extraBufferCapacity = 16,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
        override val events: SharedFlow<AsrEvent> = _events.asSharedFlow()

        private val cancellableContext = Context.current().withCancellation()
        private val closed = AtomicBoolean(false)

        /**
         * Audio frames dropped because the RPC was already closing or never
         * opened. Expected during teardown, but a silently growing counter
         * would hide a mis-paced producer — hence bounded, content-free
         * visibility (first drop, then every 50th).
         */
        private val droppedFrames = AtomicLong()

        /**
         * Set by [onError]/[onCompleted] so the feeder stops pushing frames
         * (~50/s) into a dead RPC until the async error propagates.
         * `finish()`/`cancel()` set it too; a `Final` deliberately does NOT —
         * the RPC is still alive after EOU and [send] must keep working until
         * the caller half-closes.
         */
        private val terminalEmitted = AtomicBoolean(false)

        /** Emits [event] at most once, so a spurious `Failed` never follows a `Final`. */
        private fun emitTerminal(event: AsrEvent) {
            if (terminalEmitted.getAndSet(true)) return
            _events.tryEmit(event)
        }

        @Volatile private var requestObserver: StreamObserver<StreamingRequest>? = null

        /**
         * The generic catch is the contract: the cancellable context must be
         * cancelled even when stub construction throws something unexpected
         * (a native `UnsatisfiedLinkError`, a protobuf allocation `Error`) —
         * narrowing it would re-open the context leak this guards against.
         */
        @Suppress("TooGenericExceptionCaught")
        fun start() {
            try {
                val stub = yandexApiKeyStub(
                    channel,
                    apiKey,
                    deadlineMs,
                    RecognizerGrpc::newStub,
                )

                val responseObserver = object : StreamObserver<StreamingResponse> {
                    override fun onNext(value: StreamingResponse) {
                        when {
                            value.hasFinal() -> {
                                // `final` is sent only once the server's EOU
                                // classifier fired, so it is the utterance
                                // boundary — same meaning Sber attaches to
                                // `eou == true`. A blank transcript becomes
                                // Final("") and the session maps it to NoSpeech.
                                emitTerminal(AsrEvent.Final(firstAlternativeText(value.getFinal())))
                            }

                            value.hasPartial() -> {
                                val text = firstAlternativeText(value.getPartial())
                                // A late Partial must not replace the terminal
                                // event already replayed to a late subscriber
                                // (replay=1) — the terminal always wins.
                                if (text.isNotBlank() && !terminalEmitted.get()) {
                                    _events.tryEmit(AsrEvent.Partial(text))
                                }
                            }

                            value.hasStatusCode() -> logStatusCode(value.getStatusCode())

                            // eou_update / final_refinement / unset: nothing the
                            // AsrEvent contract can express.
                            else -> Unit
                        }
                    }

                    override fun onError(t: Throwable) {
                        val cause = (t as? StatusException)?.status?.code?.toString()
                            ?: (t as? io.grpc.StatusRuntimeException)?.status?.code?.toString()
                            ?: t.message
                        Timber.e(t, "Yandex ASR stream error ($cause)")
                        // Shut the feeder gate BEFORE the terminal event so
                        // send() stops feeding the dead RPC.
                        closed.set(true)
                        emitTerminal(AsrEvent.Failed(t))
                    }

                    override fun onCompleted() {
                        // Server closed without EOU. The terminal gate
                        // guarantees this never masks a Final that already
                        // went out.
                        closed.set(true)
                        emitTerminal(
                            AsrEvent.Failed(
                                RuntimeException("Yandex ASR stream completed without end-of-utterance")
                            )
                        )
                    }
                }

                // The RPC executes under the cancellable context.
                cancellableContext.run {
                    requestObserver = stub.recognizeStreaming(responseObserver)
                    // MUST be the first message on the stream.
                    requestObserver?.onNext(
                        StreamingRequest.newBuilder().setSessionOptions(sessionOptions()).build()
                    )
                }
            } catch (t: Throwable) {
                // If start() throws before the RPC is registered nothing else
                // ever cancels the context (cancel() guards on `closed`, still
                // false) and the parent leaks it — cancel here, then rethrow so
                // the caller still sees the failure.
                cancellableContext.cancel(Status.CANCELLED.asException())
                throw t
            }
        }

        /** The `session_options` payload; see the class KDoc for the format choices. */
        private fun sessionOptions(): StreamingOptions {
            val model = RecognitionModelOptions.newBuilder()
                .setModel(ASR_MODEL)
                .setAudioFormat(
                    AudioFormatOptions.newBuilder().setRawAudio(
                        RawAudio.newBuilder()
                            .setAudioEncoding(RawAudio.AudioEncoding.LINEAR16_PCM)
                            .setSampleRateHertz(MIC_SAMPLE_RATE_HERTZ)
                            .setAudioChannelCount(1),
                    ),
                )
                .setLanguageRestriction(
                    LanguageRestrictionOptions.newBuilder()
                        .setRestrictionType(LanguageRestrictionOptions.LanguageRestrictionType.WHITELIST)
                        .addLanguageCode(LANGUAGE_TAG),
                )
                // REAL_TIME → emit partials/finals as soon as possible rather
                // than after all audio was received.
                .setAudioProcessingType(RecognitionModelOptions.AudioProcessingType.REAL_TIME)
                .build()

            // Server-side EOU, stated explicitly rather than left to default.
            val eouClassifier = EouClassifierOptions.newBuilder()
                .setDefaultClassifier(
                    DefaultEouClassifier.newBuilder()
                        .setType(DefaultEouClassifier.EouSensitivity.DEFAULT),
                )
                .build()

            return StreamingOptions.newBuilder()
                .setRecognitionModel(model)
                .setEouClassifier(eouClassifier)
                .build()
        }

        override fun send(pcm: ByteArray) {
            if (closed.get()) {
                logDroppedFrame()
                return
            }
            val observer = requestObserver ?: run {
                logDroppedFrame()
                return
            }
            runCatching {
                observer.onNext(
                    StreamingRequest.newBuilder()
                        .setChunk(AudioChunk.newBuilder().setData(ByteString.copyFrom(pcm)))
                        .build()
                )
            }
        }

        /** Bounded, content-free visibility into the drop path. */
        private fun logDroppedFrame() {
            val n = droppedFrames.incrementAndGet()
            if (n == 1L || n % 50L == 1L) {
                Timber.d("Yandex ASR frames dropped before dispatch (count=%d)", n)
            }
        }

        override fun finish() {
            if (closed.getAndSet(true)) return
            runCatching { requestObserver?.onCompleted() }
        }

        override fun cancel() {
            if (closed.getAndSet(true)) return
            cancellableContext.cancel(Status.CANCELLED.asException())
        }

        /** The top hypothesis of an update, trimmed; empty when there is none. */
        private fun firstAlternativeText(update: AlternativeUpdate): String =
            update.alternativesList.firstOrNull()?.text.orEmpty().trim()

        /**
         * A WARNING status means the service degraded (e.g. the audio was not
         * arriving in real time). Content-free: the server's free-text
         * `message` is deliberately NOT logged.
         */
        private fun logStatusCode(status: StatusCode) {
            if (status.codeType == CodeType.WARNING) {
                Timber.d("Yandex ASR session emitted WARNING status")
            }
        }
    }
}
