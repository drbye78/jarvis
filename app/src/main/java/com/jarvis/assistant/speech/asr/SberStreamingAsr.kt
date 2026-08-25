package com.jarvis.assistant.speech.asr

import com.jarvis.assistant.grpc.recognition.RecognitionRequest
import com.jarvis.assistant.grpc.recognition.RecognitionResponse
import com.jarvis.assistant.grpc.recognition.SmartSpeechGrpc
import com.jarvis.assistant.llm.TokenManager
import com.google.protobuf.ByteString
import io.grpc.ClientInterceptors
import io.grpc.Context
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Events emitted by an open ASR stream. */
sealed interface AsrEvent {
    /** Interim hypothesis while the user is still speaking. */
    data class Partial(val text: String) : AsrEvent

    /** End-of-utterance final transcript. */
    data class Final(val text: String) : AsrEvent

    data class Failed(val cause: Throwable) : AsrEvent
}

/**
 * A single live recognition session. Audio is pushed with [send] as it is
 * captured (true streaming — perceived latency no longer grows with
 * utterance length), and results arrive on [events].
 */
interface AsrStream {
    fun send(pcm: ByteArray)
    fun finish()
    fun cancel()
    val events: SharedFlow<AsrEvent>
}

interface StreamingAsrClient {
    suspend fun open(): AsrStream
}

/**
 * SaluteSpeech streaming ASR over the bidi `Recognize` gRPC stream.
 *
 * Reliability decisions:
 * - The RPC runs under a cancellable [Context], so [cancel] aborts the call
 *   immediately (original defect #6 — gRPC calls were never cancelled).
 * - A gRPC deadline caps the whole stream ([deadlineMs]).
 * - The server performs end-of-utterance detection: we send
 *   `enable_partial_results` and `no_speech_timeout`, and treat
 *   `eou == true` as the utterance boundary. `NO_SPEECH_TIMEOUT` EOU with
 *   an empty transcript maps to [AsrEvent.Final] with blank text — the
 *   session layer converts that to NoSpeech.
 */
class SberStreamingAsr(
    private val tokenManager: TokenManager,
    private val channel: ManagedChannel,
    private val deadlineMs: Long = 60_000,
    private val noSpeechTimeoutSec: Long = 7,
) : StreamingAsrClient {

    override suspend fun open(): AsrStream = withContext(Dispatchers.IO) {
        val token = tokenManager.getSaluteToken()
        SberAsrStream(channel, token, deadlineMs, noSpeechTimeoutSec).also { it.start() }
    }

    private class SberAsrStream(
        private val channel: ManagedChannel,
        private val token: String,
        private val deadlineMs: Long,
        private val noSpeechTimeoutSec: Long,
    ) : AsrStream {

        private val _events = MutableSharedFlow<AsrEvent>(
            extraBufferCapacity = 64,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
        override val events: SharedFlow<AsrEvent> = _events.asSharedFlow()

        private val cancellableContext = Context.current().withCancellation()
        private val closed = AtomicBoolean(false)

        @Volatile private var requestObserver: StreamObserver<RecognitionRequest>? = null

        fun start() {
            val headers = Metadata().apply {
                put(
                    Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                    "Bearer $token"
                )
            }
            val intercepted = ClientInterceptors.intercept(
                channel,
                MetadataUtils.newAttachHeadersInterceptor(headers),
            )
            val stub = SmartSpeechGrpc.newStub(intercepted)
                .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)

            val responseObserver = object : StreamObserver<RecognitionResponse> {
                override fun onNext(value: RecognitionResponse) {
                    if (!value.hasTranscription()) return
                    val t = value.transcription
                    val text = t.resultsList.joinToString(" ") { it.text }.trim()
                    if (t.eou) {
                        if (text.isNotBlank()) {
                            _events.tryEmit(AsrEvent.Final(text))
                        } else {
                            // EOU with no speech (e.g. NO_SPEECH_TIMEOUT).
                            _events.tryEmit(AsrEvent.Final(""))
                        }
                    } else if (text.isNotBlank()) {
                        _events.tryEmit(AsrEvent.Partial(text))
                    }
                }

                override fun onError(t: Throwable) {
                    val cause = (t as? StatusException)?.status?.code?.toString() ?: t.message
                    Timber.e(t, "ASR stream error ($cause)")
                    _events.tryEmit(AsrEvent.Failed(t))
                }

                override fun onCompleted() {
                    // Server closed without EOU: treat as final empty if we
                    // never emitted anything; otherwise the session's hard
                    // cap resolves it.
                    _events.tryEmit(AsrEvent.Failed(
                        RuntimeException("ASR stream completed without end-of-utterance")
                    ))
                }
            }

            // The RPC executes under the cancellable context.
            cancellableContext.run {
                requestObserver = stub.recognize(responseObserver)
                requestObserver?.onNext(
                    RecognitionRequest.newBuilder()
                        .setOptions(
                            com.jarvis.assistant.grpc.recognition.RecognitionOptions.newBuilder()
                                .setAudioEncoding(
                                    com.jarvis.assistant.grpc.recognition.RecognitionOptions.AudioEncoding.PCM_S16LE
                                )
                                .setSampleRate(16_000)
                                .setLanguage("ru-RU")
                                .setModel("general")
                                .setEnablePartialResults(
                                    com.jarvis.assistant.grpc.recognition.OptionalBool.newBuilder()
                                        .setEnable(true).build()
                                )
                                .setNoSpeechTimeout(
                                    com.google.protobuf.Duration.newBuilder()
                                        .setSeconds(noSpeechTimeoutSec).build()
                                )
                                .setMaxSpeechTimeout(
                                    com.google.protobuf.Duration.newBuilder()
                                        .setSeconds(90).build()
                                )
                                .build()
                        )
                        .build()
                )
            }
        }

        override fun send(pcm: ByteArray) {
            if (closed.get()) return
            val observer = requestObserver ?: return
            runCatching {
                observer.onNext(
                    RecognitionRequest.newBuilder()
                        .setAudioChunk(ByteString.copyFrom(pcm))
                        .build()
                )
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
    }
}
