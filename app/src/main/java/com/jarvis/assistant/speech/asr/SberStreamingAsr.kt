package com.jarvis.assistant.speech.asr

import com.google.protobuf.ByteString
import com.jarvis.assistant.grpc.recognition.RecognitionRequest
import com.jarvis.assistant.grpc.recognition.RecognitionResponse
import com.jarvis.assistant.grpc.recognition.SmartSpeechGrpc
import com.jarvis.assistant.llm.TokenManager
import com.jarvis.assistant.speech.bearerStub
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

        // m11: replay=1 redelivers a terminal event emitted in the window
        // between open() and the session's subscription (an instant server
        // error used to be dropped, hanging the session in LISTENING until
        // the 90s cap); extraBufferCapacity keeps early emissions from being
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
         * S-4: audio frames dropped because the RPC was already closing or
         * never opened. Expected in normal teardown, but a silently growing
         * counter would hide a mis-paced producer — so it is observable
         * (content-free DEBUG log, bounded to the first drop then every 50th).
         */
        private val droppedFrames = AtomicLong()

        /**
         * Feed-after-death fix: set by [onError]/[onCompleted] so the feeder
         * stops pushing frames (~50/s) into a dead RPC until the async error
         * propagates. finish()/cancel() set it too; a `Final` deliberately
         * does NOT — the RPC is still alive after EOU and [send] must keep
         * working until the caller half-closes (pinned by the direct suite).
         */
        private val terminalEmitted = AtomicBoolean(false)

        /**
         * Terminal sink for the OBSERVER flow: emits [event] at most once, so
         * a spurious `Failed` can never follow a `Final` (previously only the
         * caller's complete()/cancel() idempotence neutralized it).
         */
        private fun emitTerminal(event: AsrEvent) {
            if (terminalEmitted.getAndSet(true)) return
            _events.tryEmit(event)
        }

        @Volatile private var requestObserver: StreamObserver<RecognitionRequest>? = null

        /**
         * The generic catch is the contract: the cancellable context must be
         * cancelled even when stub construction throws something unexpected
         * (a native `UnsatisfiedLinkError`, a protobuf allocation `Error`) —
         * narrowing it would re-open the context leak S-1 exists to close.
         */
        @Suppress("TooGenericExceptionCaught")
        fun start() {
            try {
                val stub = bearerStub(channel, token, deadlineMs, SmartSpeechGrpc::newStub)

                val responseObserver = object : StreamObserver<RecognitionResponse> {
                    override fun onNext(value: RecognitionResponse) {
                        if (!value.hasTranscription()) return
                        val t = value.transcription
                        val text = t.resultsList.joinToString(" ") { it.text }.trim()
                        if (t.eou) {
                            // Terminal for the OBSERVER flow (a Final ends the
                            // utterance; the send() gate stays open until the
                            // caller half-closes — see [emitTerminal]).
                            if (text.isNotBlank()) {
                                emitTerminal(AsrEvent.Final(text))
                            } else {
                                // EOU with no speech (e.g. NO_SPEECH_TIMEOUT).
                                emitTerminal(AsrEvent.Final(""))
                            }
                        } else if (text.isNotBlank() && !terminalEmitted.get()) {
                            // S-3: a late Partial must not replace the terminal
                            // event already replayed to a late subscriber
                            // (replay=1) — the terminal always wins.
                            _events.tryEmit(AsrEvent.Partial(text))
                        }
                    }

                    override fun onError(t: Throwable) {
                        val cause = (t as? StatusException)?.status?.code?.toString()
                            ?: (t as? io.grpc.StatusRuntimeException)?.status?.code?.toString()
                            ?: t.message
                        Timber.e(t, "ASR stream error ($cause)")
                        // Feed-after-death fix: shut the feeder gate BEFORE the
                        // terminal event — send() must stop feeding the dead RPC.
                        closed.set(true)
                        emitTerminal(AsrEvent.Failed(t))
                    }

                    override fun onCompleted() {
                        // Server closed without EOU: treat as final empty if we
                        // never emitted anything; otherwise the session's hard
                        // cap resolves it. Feed-after-death fix applies here too,
                        // and the terminal gate guarantees a `Failed` is never
                        // emitted after a `Final` already went out.
                        closed.set(true)
                        emitTerminal(
                            AsrEvent.Failed(
                                RuntimeException("ASR stream completed without end-of-utterance")
                            )
                        )
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
            } catch (t: Throwable) {
                // S-1: the cancellable context is a child of the caller's
                // context. If start() throws before the RPC is registered,
                // nothing else ever cancels it (cancel() guards on `closed`,
                // which is still false) and the parent leaks the token — cancel
                // it here, then rethrow so the caller still sees the failure.
                cancellableContext.cancel(Status.CANCELLED.asException())
                throw t
            }
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
                    RecognitionRequest.newBuilder()
                        .setAudioChunk(ByteString.copyFrom(pcm))
                        .build()
                )
            }
        }

        /** S-4: bounded, content-free visibility into the drop path. */
        private fun logDroppedFrame() {
            val n = droppedFrames.incrementAndGet()
            if (n == 1L || n % 50L == 1L) {
                Timber.d("ASR frames dropped before dispatch (count=%d)", n)
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
