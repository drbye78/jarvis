package com.jarvis.assistant.llm

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The ONE cancellable-HTTP primitive for this codebase (M5).
 *
 * Awaits an OkHttp [Call] without blocking a thread: the response is delivered
 * through OkHttp's async queue, and cancelling the awaiting coroutine cancels
 * the underlying call (`Call.cancel()` closes the socket), so barge-in aborts
 * in-flight requests — including token fetches — instead of downloading to
 * completion or hanging until the read timeout.
 *
 * The resume is guarded to be atomic-once:
 *  - [done] (an [AtomicBoolean]) ensures at most one of onResponse/onFailure
 *    ever attempts to resume the continuation.
 *  - When cancellation has already completed the continuation, the late
 *    callback does NOT resume (a resumed-when-cancelled continuation is a
 *    no-op at best, an IllegalStateException at worst) and, for a successful
 *    response, closes the body so it is never leaked.
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    val done = AtomicBoolean(false)

    cont.invokeOnCancellation {
        // Abort the socket so a blocked read unblocks promptly (barge-in).
        runCatching { cancel() }
    }

    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (done.compareAndSet(false, true)) {
                if (cont.isActive) {
                    cont.resume(response)
                } else {
                    // Cancellation won the race: nobody will consume this body.
                    runCatching { response.close() }
                }
            } else {
                // A prior callback already resumed; drop this late response.
                runCatching { response.close() }
            }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (done.compareAndSet(false, true)) {
                // A failed call has no body to release. If we were already
                // cancelled the continuation is completed, so this is a no-op.
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    })
}
