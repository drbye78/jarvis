package com.jarvis.assistant.speech.grpc

import com.jarvis.assistant.grpc.yandexstt.Alternative
import com.jarvis.assistant.grpc.yandexstt.AlternativeUpdate
import com.jarvis.assistant.grpc.yandexstt.CodeType
import com.jarvis.assistant.grpc.yandexstt.RecognizerGrpc
import com.jarvis.assistant.grpc.yandexstt.StatusCode
import com.jarvis.assistant.grpc.yandexstt.StreamingRequest
import com.jarvis.assistant.grpc.yandexstt.StreamingResponse
import com.jarvis.assistant.grpc.yandextts.SynthesizerGrpc
import com.jarvis.assistant.grpc.yandextts.UtteranceSynthesisRequest
import com.jarvis.assistant.grpc.yandextts.UtteranceSynthesisResponse
import io.grpc.Status
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import com.jarvis.assistant.grpc.yandextts.AudioChunk as TtsAudioChunk

/**
 * Phase 2 test harness for the Yandex SpeechKit v3 clients: in-process gRPC
 * fakes mirroring [FakeSaluteAsrService] / [FakeSaluteTtsService], reusing the
 * same [InProcessGrpc] transport. No network, no credentials.
 *
 * The fakes extend the GENERATED server bases
 * (`RecognizerGrpc.RecognizerImplBase` / `SynthesizerGrpc.SynthesizerImplBase`),
 * so the wire shapes the production client sees are the real ones — including
 * the request fields the correctness assertions depend on (the pinned 24 kHz
 * output spec, the 16 kHz `session_options`).
 */

/**
 * Scriptable fake of the bidi `speechkit.stt.v3.Recognizer/RecognizeStreaming`
 * stream. Drive the server side with [emitPartial]/[emitFinal]/[emitWarning]/
 * [emitError]/[completeStream]; inspect client traffic via [receivedRequests].
 */
class FakeYandexAsrService : RecognizerGrpc.RecognizerImplBase() {

    private val lock = Any()
    private val received = mutableListOf<StreamingRequest>()
    private var responseObserver: StreamObserver<StreamingResponse>? = null

    private val clientErrorDeferred = CompletableDeferred<Throwable>()
    private val clientCompletedDeferred = CompletableDeferred<Unit>()

    override fun recognizeStreaming(
        responseObserver: StreamObserver<StreamingResponse>,
    ): StreamObserver<StreamingRequest> {
        synchronized(lock) { this.responseObserver = responseObserver }
        return object : StreamObserver<StreamingRequest> {
            override fun onNext(value: StreamingRequest) {
                synchronized(lock) { received.add(value) }
            }

            override fun onError(t: Throwable) {
                clientErrorDeferred.complete(t)
            }

            override fun onCompleted() {
                clientCompletedDeferred.complete(Unit)
            }
        }
    }

    // ---- server → client scripting -------------------------------------------

    /** The live response observer, or a failure if no call was opened yet. */
    private fun observer(): StreamObserver<StreamingResponse> =
        checkNotNull(synchronized(lock) { responseObserver }) {
            "no RecognizeStreaming call opened yet"
        }

    private fun emit(response: StreamingResponse) {
        observer().onNext(response)
    }

    /** Interim hypothesis carrying [text]. */
    fun emitPartial(text: String) {
        emit(StreamingResponse.newBuilder().setPartial(update(text)).build())
    }

    /**
     * EOU final carrying [text]. Yandex sends `final` only once its EOU
     * classifier fired, so this doubles as the utterance boundary.
     */
    fun emitFinal(text: String) {
        emit(StreamingResponse.newBuilder().setFinal(update(text)).build())
    }

    /** Keep-alive WARNING status: must never surface as a transcript. */
    fun emitWarning() {
        emit(
            StreamingResponse.newBuilder()
                .setStatusCode(
                    StatusCode.newBuilder().setCodeType(CodeType.WARNING).setMessage("degraded"),
                )
                .build(),
        )
    }

    /** Terminal stream error (the gRPC way of saying UNAUTHENTICATED / deadline). */
    fun emitError(status: Status) {
        observer().onError(status.asRuntimeException())
    }

    /** Server closes the stream without an EOU. */
    fun completeStream() {
        observer().onCompleted()
    }

    private fun update(text: String): AlternativeUpdate =
        AlternativeUpdate.newBuilder().addAlternatives(Alternative.newBuilder().setText(text)).build()

    // ---- client → server inspection ------------------------------------------

    fun receivedRequests(): List<StreamingRequest> = synchronized(lock) { received.toList() }

    /** Suspends (bounded) until the client aborts the call; null on timeout. */
    suspend fun awaitClientError(timeoutMs: Long = 5_000): Throwable? =
        runCatching { withTimeout(timeoutMs) { clientErrorDeferred.await() } }.getOrNull()

    /** Suspends (bounded) until the client half-closes the call; false on timeout. */
    suspend fun awaitClientCompleted(timeoutMs: Long = 5_000): Boolean =
        runCatching { withTimeout(timeoutMs) { clientCompletedDeferred.await() } }.isSuccess
}

/**
 * Scriptable fake of `speechkit.tts.v3.Synthesizer/UtteranceSynthesis`.
 * Script with [emitChunk]/[emitError]/[completeStream]; inspect the captured
 * [UtteranceSynthesisRequest] via [awaitRequest] (the pinned output spec is
 * asserted on it) and observe barge-in via [awaitClientCancelled].
 */
class FakeYandexTtsService : SynthesizerGrpc.SynthesizerImplBase() {

    private val lock = Any()
    private var responseObserver: StreamObserver<UtteranceSynthesisResponse>? = null

    /**
     * Every request the fake has seen, in order. A Channel (not a
     * CompletableDeferred) because a swapped deferred loses the wakeup of a
     * parked awaiter — with a Channel `receive()` always observes the queued
     * request regardless of arrival order.
     */
    private val receivedRequests = Channel<UtteranceSynthesisRequest>(Channel.UNLIMITED)

    private val clientCancelledDeferred = CompletableDeferred<Unit>()

    /** The most recent request the fake has seen (for multi-RPC tests). */
    @Volatile
    var capturedRequest: UtteranceSynthesisRequest? = null
        private set

    override fun utteranceSynthesis(
        request: UtteranceSynthesisRequest,
        responseObserver: StreamObserver<UtteranceSynthesisResponse>,
    ) {
        synchronized(lock) { this.responseObserver = responseObserver }
        capturedRequest = request
        receivedRequests.trySend(request)
        // The server-call context is cancelled when the client cancels the RPC
        // (barge-in) — observe it so tests can assert the abort server-side.
        io.grpc.Context.current().addListener(
            { clientCancelledDeferred.complete(Unit) },
            java.util.concurrent.Executor { it.run() },
        )
    }

    /** The live response observer, or a failure if no call was opened yet. */
    private fun observer(): StreamObserver<UtteranceSynthesisResponse> =
        checkNotNull(synchronized(lock) { responseObserver }) {
            "no UtteranceSynthesis call received yet"
        }

    fun emitChunk(bytes: ByteArray) {
        observer().onNext(
            UtteranceSynthesisResponse.newBuilder()
                .setAudioChunk(TtsAudioChunk.newBuilder().setData(com.google.protobuf.ByteString.copyFrom(bytes)))
                .build(),
        )
    }

    fun emitError(status: Status) {
        observer().onError(status.asRuntimeException())
    }

    fun completeStream() {
        observer().onCompleted()
    }

    /** Suspends (bounded) until the next request arrives; fails (not hangs) on timeout. */
    suspend fun awaitRequest(timeoutMs: Long = 5_000): UtteranceSynthesisRequest =
        withTimeout(timeoutMs) { receivedRequests.receive() }

    /** Suspends (bounded) until the downstream collector cancels the RPC; false on timeout. */
    suspend fun awaitClientCancelled(timeoutMs: Long = 5_000): Boolean =
        runCatching { withTimeout(timeoutMs) { clientCancelledDeferred.await() } }.isSuccess
}
