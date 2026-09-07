package com.jarvis.assistant.speech.grpc

import com.jarvis.assistant.grpc.recognition.Hypothesis
import com.jarvis.assistant.grpc.recognition.RecognitionRequest
import com.jarvis.assistant.grpc.recognition.RecognitionResponse
import com.jarvis.assistant.grpc.recognition.Transcription
import com.jarvis.assistant.grpc.synthesis.SynthesisRequest
import com.jarvis.assistant.grpc.synthesis.SynthesisResponse
import com.jarvis.assistant.speech.asr.AsrEvent
import io.grpc.BindableService
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import com.jarvis.assistant.grpc.recognition.SmartSpeechGrpc as RecognitionSmartSpeechGrpc
import com.jarvis.assistant.grpc.synthesis.SmartSpeechGrpc as SynthesisSmartSpeechGrpc

/**
 * P1.1 shared test harness: in-process gRPC for the SaluteSpeech streaming
 * clients (ASR + TTS). No real network, no credentials; fake services script
 * server-side events (results, errors, completion, deadlines).
 *
 * Both fake services extend the GENERATED server bases
 * (`SmartSpeechGrpc.SmartSpeechImplBase` from app/src/main/proto/{recognitionv2,synthesis}.proto),
 * so the wire shapes the production client sees are the real ones.
 *
 * Determinism notes:
 * - The in-process server + channel run on a direct executor: server event
 *   callbacks are invoked on the thread that emitted them, and the client's
 *   response callbacks run on the emitting thread too.
 * - All wait helpers are suspension-based ([CompletableDeferred]/[withTimeout])
 *   with bounded timeouts — safe to call from `runBlocking` without blocking
 *   its event loop, and a broken client FAILS the test instead of hanging it.
 * - Because the client's event flow is a SharedFlow with replay=1, tests that
 *   need a full event SEQUENCE must subscribe before the server emits (the
 *   tests use `async { collect }` + `yield()` for that; emissions made before
 *   a subscription would leave only the last event in the replay cache).
 */

/** A server interceptor that captures the last `authorization` header of every call. */
class BearerCapture : ServerInterceptor {
    @Volatile
    var lastBearer: String? = null
        private set

    override fun <ReqT : Any, RespT : Any> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        lastBearer = headers.get(
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
        )
        return next.startCall(call, headers)
    }
}

/** Builds an in-process server serving [service] and a client channel to it. */
class InProcessGrpc(
    service: BindableService,
    interceptor: ServerInterceptor? = null,
) {
    private val serverName = "salute-test-" + System.nanoTime()
    val bearerCapture = BearerCapture()

    val server: Server = InProcessServerBuilder
        .forName(serverName)
        .directExecutor()
        .addService(service)
        .apply {
            interceptor?.let(::intercept)
            intercept(bearerCapture)
        }
        .build()
        .start()

    val channel: ManagedChannel = InProcessChannelBuilder
        .forName(serverName)
        .directExecutor()
        .build()

    fun shutdown() {
        channel.shutdownNow()
        server.shutdownNow()
    }
}

/**
 * Scriptable fake of the bidi `smartspeech.recognition.v2.SmartSpeech/Recognize` stream.
 * Tests drive the server side via [emitPartial]/[emitFinal]/[emitNoSpeechTimeout]/
 * [emitError]/[completeStream] and inspect client traffic via [receivedRequests] /
 * [awaitClientError] / [awaitClientCompleted].
 */
class FakeSaluteAsrService : RecognitionSmartSpeechGrpc.SmartSpeechImplBase() {

    private val lock = Any()
    private val received = mutableListOf<RecognitionRequest>()
    private var responseObserver: StreamObserver<RecognitionResponse>? = null

    private val clientErrorDeferred = CompletableDeferred<Throwable>()
    private val clientCompletedDeferred = CompletableDeferred<Unit>()

    override fun recognize(
        responseObserver: StreamObserver<RecognitionResponse>,
    ): StreamObserver<RecognitionRequest> {
        synchronized(lock) { this.responseObserver = responseObserver }
        return object : StreamObserver<RecognitionRequest> {
            override fun onNext(value: RecognitionRequest) {
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

    // ---- server → client scripting -------------------------------------------------

    private fun emit(response: RecognitionResponse) {
        val observer = synchronized(lock) { responseObserver }
        checkNotNull(observer) { "no Recognize call opened yet" }
        observer.onNext(response)
    }

    /** Interim hypothesis (eou = false). */
    fun emitPartial(text: String) {
        emit(
            RecognitionResponse.newBuilder()
                .setTranscription(
                    Transcription.newBuilder()
                        .setEou(false)
                        .addResults(Hypothesis.newBuilder().setText(text)),
                )
                .build(),
        )
    }

    /** Final end-of-utterance result with the given transcript. */
    fun emitFinal(text: String) {
        emit(
            RecognitionResponse.newBuilder()
                .setTranscription(
                    Transcription.newBuilder()
                        .setEou(true)
                        .addResults(Hypothesis.newBuilder().setText(text)),
                )
                .build(),
        )
    }

    /** Server no-speech timeout: EOU with an empty results list. */
    fun emitNoSpeechTimeout() {
        emit(
            RecognitionResponse.newBuilder()
                .setTranscription(
                    Transcription.newBuilder()
                        .setEou(true)
                        .setEouReason(com.jarvis.assistant.grpc.recognition.EouReason.NO_SPEECH_TIMEOUT),
                )
                .build(),
        )
    }

    /** A response that does not carry a transcription (heartbeat / garbled / empty). */
    fun emitNonTranscription(response: RecognitionResponse) = emit(response)

    /** Terminal stream error (the gRPC way of saying 401 / transport death / deadline). */
    fun emitError(status: Status) {
        val observer = synchronized(lock) { responseObserver }
        checkNotNull(observer) { "no Recognize call opened yet" }
        observer.onError(status.asRuntimeException())
    }

    /** Server closes the stream without an EOU. */
    fun completeStream() {
        val observer = synchronized(lock) { responseObserver }
        checkNotNull(observer) { "no Recognize call opened yet" }
        observer.onCompleted()
    }

    // ---- client → server inspection ------------------------------------------------

    fun receivedRequests(): List<RecognitionRequest> = synchronized(lock) { received.toList() }

    /** Suspends (bounded) until the client aborts the call; null on timeout. */
    suspend fun awaitClientError(timeoutMs: Long = 5_000): Throwable? =
        runCatching { withTimeout(timeoutMs) { clientErrorDeferred.await() } }.getOrNull()

    /** Suspends (bounded) until the client half-closes the call; false on timeout. */
    suspend fun awaitClientCompleted(timeoutMs: Long = 5_000): Boolean =
        runCatching { withTimeout(timeoutMs) { clientCompletedDeferred.await() } }.isSuccess
}

/**
 * Scriptable fake of the server-streaming `smartspeech.synthesis.v1.SmartSpeech/Synthesize` RPC.
 * Tests script [emitChunk]/[emitError]/[completeStream] and inspect the captured
 * [SynthesisRequest] via [awaitRequest]; barge-in observability via [awaitClientCancelled].
 */
class FakeSaluteTtsService : SynthesisSmartSpeechGrpc.SmartSpeechImplBase() {

    private val lock = Any()
    private var responseObserver: StreamObserver<SynthesisResponse>? = null

    /**
     * Every Synthesize request the fake has seen, in order. A Channel (not a
     * CompletableDeferred) because a swapped deferred loses the wakeup of a
     * parked awaiter (await on D0 vs completion of D1) — with a Channel the
     * receive() always observes the queued request, regardless of arrival
     * order, and multi-RPC tests can await each request in sequence.
     */
    private val receivedRequests = Channel<SynthesisRequest>(Channel.UNLIMITED)

    private val clientCancelledDeferred = CompletableDeferred<Unit>()

    /** The most recent Synthesize request the fake has seen (for multi-RPC tests). */
    @Volatile
    var capturedRequest: SynthesisRequest? = null
        private set

    override fun synthesize(
        request: SynthesisRequest,
        responseObserver: StreamObserver<SynthesisResponse>,
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

    fun emitChunk(bytes: ByteArray) {
        val observer = synchronized(lock) { responseObserver }
        checkNotNull(observer) { "no Synthesize call received yet" }
        observer.onNext(
            SynthesisResponse.newBuilder()
                .setData(com.google.protobuf.ByteString.copyFrom(bytes))
                .build(),
        )
    }

    fun emitError(status: Status) {
        val observer = synchronized(lock) { responseObserver }
        checkNotNull(observer) { "no Synthesize call received yet" }
        observer.onError(status.asRuntimeException())
    }

    fun completeStream() {
        val observer = synchronized(lock) { responseObserver }
        checkNotNull(observer) { "no Synthesize call received yet" }
        observer.onCompleted()
    }

    /** Suspends (bounded) until the next Synthesize request arrives; fails (not hangs) on timeout. */
    suspend fun awaitRequest(timeoutMs: Long = 5_000): SynthesisRequest =
        withTimeout(timeoutMs) { receivedRequests.receive() }

    /** Suspends (bounded) until the downstream collector cancels the RPC; false on timeout. */
    suspend fun awaitClientCancelled(timeoutMs: Long = 5_000): Boolean =
        runCatching { withTimeout(timeoutMs) { clientCancelledDeferred.await() } }.isSuccess
}

// ---- bounded collection helpers ----------------------------------------------------

/** Collect exactly [count] ASR events, failing (not hanging) after [timeoutMs]. */
suspend fun collectEvents(events: Flow<AsrEvent>, count: Int, timeoutMs: Long = 5_000): List<AsrEvent> =
    withTimeout(timeoutMs) { events.take(count).toList() }

/** Await the first event matching [predicate], failing (not hanging) after [timeoutMs]. */
suspend fun awaitEvent(
    events: Flow<AsrEvent>,
    timeoutMs: Long = 5_000,
    predicate: (AsrEvent) -> Boolean = { true },
): AsrEvent =
    withTimeout(timeoutMs) { events.first(predicate) }
