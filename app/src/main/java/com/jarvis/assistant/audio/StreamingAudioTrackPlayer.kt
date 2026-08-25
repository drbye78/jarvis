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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * [TtsPlayer] backed by a single [AudioTrack] writer actor.
 *
 * Fix for the barge-in queue defect: [flush] uses a monotonically increasing
 * GENERATION counter. Playback jobs carry the generation they were enqueued
 * under; the actor drops any job whose generation is stale, and flush()
 * synchronously (a) cancels the current playback, (b) drains every queued
 * job, cancelling its Deferred, and (c) flushes the AudioTrack buffer.
 * Previously only the currently-playing sentence was cancelled and queued
 * sentences kept playing over the new session.
 */
class StreamingAudioTrackPlayer(
    scope: CoroutineScope,
    spec: AudioSpec = AudioSpec.TTS,
) : TtsPlayer {

    private data class PlayJob(
        val flow: Flow<ByteArray>,
        val done: CompletableDeferred<Unit>,
        val generation: Long,
    )

    private val track: AudioTrack
    private val queue = Channel<PlayJob>(Channel.UNLIMITED)
    private val actorJob: Job
    private val lock = Any()

    @Volatile private var currentPlay: Job? = null
    @Volatile private var generation = 0L
    private var trackStarted = false

    init {
        val minBuf = AudioTrack.getMinBufferSize(
            spec.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = if (minBuf > 0) minBuf else spec.sampleRate * 2
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

        actorJob = scope.launch {
            for (job in queue) {
                if (job.generation != generation) {
                    // Enqueued before a flush() — stale sentence, drop it.
                    job.done.cancel()
                    continue
                }
                val play = launch {
                    try {
                        job.flow.collect { chunk -> writeChunk(chunk) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Playback errors unblock the caller; sentence skipped.
                    }
                }
                currentPlay = play
                try {
                    play.join()
                } catch (_: CancellationException) {
                    // interrupted by flush()
                } finally {
                    currentPlay = null
                    if (job.generation == generation) {
                        job.done.complete(Unit)
                    } else {
                        job.done.cancel()
                    }
                }
            }
        }
    }

    private fun writeChunk(chunk: ByteArray) {
        if (!trackStarted) {
            track.play()
            trackStarted = true
        }
        var offset = 0
        while (offset < chunk.size) {
            val written = track.write(chunk, offset, chunk.size - offset)
            if (written <= 0) break
            offset += written
        }
    }

    override fun play(pcm: Flow<ByteArray>): CompletableDeferred<Unit> {
        val done = CompletableDeferred<Unit>()
        val gen = synchronized(lock) { generation }
        queue.trySend(PlayJob(pcm, done, gen))
        return done
    }

    override fun flush() {
        val newGen: Long = synchronized(lock) { generation += 1; generation }
        // 1) Cancel whatever is currently playing.
        currentPlay?.cancel()
        // 2) Drop every queued sentence (they carry the old generation).
        while (true) {
            val dropped = queue.tryReceive().getOrNull() ?: break
            dropped.done.cancel()
        }
        // 3) Drop PCM already buffered in the AudioTrack.
        runCatching { track.flush() }
        // A job could slip in between the generation bump and the drain
        // check inside the actor; the actor's generation check catches it.
    }

    override fun release() {
        synchronized(lock) { generation += 1 }
        currentPlay?.cancel()
        queue.close()
        actorJob.cancel()
        runCatching { track.flush() }
        runCatching { track.stop() }
        track.release()
    }
}
