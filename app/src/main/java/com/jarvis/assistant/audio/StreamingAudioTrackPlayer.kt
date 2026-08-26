package com.jarvis.assistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.jarvis.assistant.contracts.AudioSpec
import com.jarvis.assistant.speech.tts.TtsPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.coroutines.coroutineContext

/**
 * Thin seam over [android.media.AudioTrack] (m4) so the player actor is
 * JVM-testable with a fake; production code always gets
 * [AndroidAudioTrackAdapter] via the default constructor argument.
 */
interface AudioTrackAdapter {
    fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int
    fun play()
    fun pause()
    fun flush()
    fun stop()
    fun release()
}

/**
 * Real [AudioTrackAdapter] backed by [AudioTrack]. Builds the track with a
 * sane min-buffer floor: degenerate `getMinBufferSize` results (≤ 0) fall
 * back to the floor instead of producing an unusable buffer, and positive
 * results below the floor are raised to it.
 */
class AndroidAudioTrackAdapter(spec: AudioSpec) : AudioTrackAdapter {
    companion object {
        /** Floor ≈ 4× one 20 ms frame (320 samples × 2 bytes) = 2560 bytes. */
        const val MIN_BUFFER_FLOOR_BYTES: Int = 4 * 320 * 2
    }

    private val track: AudioTrack

    init {
        val minBuf = AudioTrack.getMinBufferSize(
            spec.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = if (minBuf <= 0) {
            Timber.w(
                "AudioTrack.getMinBufferSize returned %d at %dHz — using floor %d bytes",
                minBuf, spec.sampleRate, MIN_BUFFER_FLOOR_BYTES,
            )
            MIN_BUFFER_FLOOR_BYTES
        } else {
            maxOf(minBuf, MIN_BUFFER_FLOOR_BYTES)
        }
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(spec.sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    override fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int =
        track.write(audioData, offsetInBytes, sizeInBytes)

    override fun play() = track.play()

    override fun pause() = track.pause()

    override fun flush() = track.flush()

    override fun stop() = track.stop()

    override fun release() = track.release()
}

/**
 * [TtsPlayer] backed by a single writer actor.
 *
 * Generation semantics (barge-in queue defect): playback jobs carry the
 * generation they were enqueued under; flush() bumps the generation and the
 * actor drops any job whose generation is stale — so flushing cancels the
 * current sentence AND every queued one.
 *
 * m4 fixes:
 * - flush()/release() are routed as ACTOR COMMANDS through the same channel
 *   as playback; the hardware buffer is only ever paused/flushed/stopped by
 *   the actor thread, never cross-thread while it writes.
 * - release() completes every queued sentence's Deferred exceptionally and
 *   promptly — callers never hang until their timeout.
 * - A short write (`written <= 0`) aborts the sentence with an error log and
 *   an exceptional Deferred instead of silently truncating audio.
 * - The real adapter enforces a min-buffer floor for degenerate
 *   getMinBufferSize results (see [AndroidAudioTrackAdapter]).
 */
class StreamingAudioTrackPlayer(
    scope: CoroutineScope,
    spec: AudioSpec = AudioSpec.TTS,
    private val adapter: AudioTrackAdapter = AndroidAudioTrackAdapter(spec),
) : TtsPlayer {

    private data class PlayJob(
        val flow: Flow<ByteArray>,
        val done: CompletableDeferred<Unit>,
        val generation: Long,
    )

    /** Actor commands: playback and hardware-buffer control share one queue. */
    private sealed interface Command {
        class Play(val job: PlayJob) : Command
        object Flush : Command
        object Release : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val actorJob: Job

    /** Guards [generation], [released], and command enqueue ordering. */
    private val lock = Any()

    @Volatile private var currentPlay: Job? = null
    @Volatile private var generation = 0L
    @Volatile private var released = false

    // Actor-thread-only state; currentDone is read best-effort by release()
    // under lock, so it must be volatile.
    @Volatile private var currentDone: CompletableDeferred<Unit>? = null
    private var trackStarted = false

    init {
        actorJob = scope.launch {
            try {
                loop@ while (isActive) {
                    val cmd = commands.receiveCatching().getOrNull() ?: break
                    when (cmd) {
                        is Command.Play -> handlePlay(cmd.job)
                        Command.Flush -> handleFlush()
                        Command.Release -> {
                            handleRelease()
                            break@loop
                        }
                    }
                }
            } finally {
                // Scope death or loop exit must never strand queued callers.
                drainQueuedJobsExceptionally()
            }
        }
    }

    private suspend fun CoroutineScope.handlePlay(job: PlayJob) {
        if (job.generation != generation) {
            // Enqueued before a flush() — stale sentence, drop it.
            job.done.cancel()
            return
        }
        val play = launch {
            try {
                job.flow.collect { chunk -> writeChunk(chunk) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "TTS sentence aborted by write failure")
                job.done.completeExceptionally(e)
            }
        }
        currentPlay = play
        currentDone = job.done
        try {
            play.join()
        } catch (_: CancellationException) {
            // actor cancelled
        } finally {
            currentPlay = null
            currentDone = null
            if (job.generation == generation) {
                if (!job.done.isCompleted) job.done.complete(Unit)
            } else {
                job.done.cancel()
            }
        }
    }

    /**
     * Runs on the ACTOR thread: pause+flush is the sequence that actually
     * drops buffered PCM on a playing track; the next sentence re-issues
     * play() via [writeChunk].
     */
    private fun handleFlush() {
        runCatching { adapter.pause() }
        runCatching { adapter.flush() }
        trackStarted = false
    }

    private fun handleRelease() {
        runCatching { adapter.flush() }
        runCatching { adapter.stop() }
        runCatching { adapter.release() }
        trackStarted = false
    }

    private fun drainQueuedJobsExceptionally() {
        while (true) {
            when (val cmd = commands.tryReceive().getOrNull()) {
                null -> return
                is Command.Play -> cmd.job.done.completeExceptionally(releasedError())
                Command.Flush, Command.Release -> {} // duplicates: nothing to do
            }
        }
    }

    private fun releasedError() = IllegalStateException("StreamingAudioTrackPlayer released")

    /** Actor thread only. Aborts the sentence on cooperative cancel or short write. */
    private suspend fun writeChunk(chunk: ByteArray) {
        if (!trackStarted) {
            adapter.play()
            trackStarted = true
        }
        var offset = 0
        while (offset < chunk.size) {
            coroutineContext.ensureActive() // prompt abort between writes once cancelled
            val written = adapter.write(chunk, offset, chunk.size - offset)
            if (written <= 0) {
                throw IllegalStateException(
                    "AudioTrack short write ($written <= 0 at offset $offset of ${chunk.size}) — aborting sentence",
                )
            }
            offset += written
        }
    }

    override fun play(pcm: Flow<ByteArray>): CompletableDeferred<Unit> {
        val done = CompletableDeferred<Unit>()
        synchronized(lock) {
            if (released) {
                done.completeExceptionally(releasedError())
            } else {
                commands.trySend(Command.Play(PlayJob(pcm, done, generation)))
            }
        }
        return done
    }

    override fun flush() {
        synchronized(lock) {
            generation += 1
            currentPlay?.cancel()
            commands.trySend(Command.Flush)
        }
        // Locking guarantees FIFO channel order matches lock order, so a Play
        // carrying the NEW generation can never overtake this Flush command;
        // stale Plays ahead of it are dropped by the actor's generation check.
    }

    override fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            generation += 1
            currentPlay?.cancel()
            currentDone?.completeExceptionally(releasedError())
            // Complete every queued sentence NOW — do not wait for the actor
            // round-trip; awaiting callers must unblock promptly.
            while (true) {
                when (val cmd = commands.tryReceive().getOrNull()) {
                    null -> break
                    is Command.Play -> cmd.job.done.completeExceptionally(releasedError())
                    Command.Flush, Command.Release -> {} // drop duplicates
                }
            }
            commands.trySend(Command.Release)
        }
    }
}
