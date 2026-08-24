package com.jarvis.assistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.jarvis.assistant.contracts.AudioSpec
import com.jarvis.assistant.contracts.TtsPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * [TtsPlayer] backed by a single [AudioTrack].
 *
 * All PCM chunks are written from ONE actor coroutine (the [actorJob] loop that
 * owns the [AudioTrack]) so concurrent sentences cannot interleave — satisfying
 * the contract's serialization requirement. [play] enqueues a flow and returns a
 * [Deferred] that completes only after the flow has been fully drained to the
 * speaker, which lets callers `await()` playback completion (defect #5).
 */
class StreamingAudioTrackPlayer(
    private val scope: CoroutineScope,
    private val spec: AudioSpec = AudioSpec.TTS
) : TtsPlayer {

    private data class PlayJob(
        val flow: Flow<ByteArray>,
        val done: CompletableDeferred<Unit>
    )

    private val track: AudioTrack
    private val queue = Channel<PlayJob>(Channel.UNLIMITED)
    private val actorJob: Job
    @Volatile private var currentPlay: Job? = null
    private var trackStarted = false

    init {
        val minBuf = AudioTrack.getMinBufferSize(
            spec.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // FIX #3: 1x minimum buffer for low latency (~100ms at 24 kHz).
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
                val play = launch {
                    try {
                        job.flow.collect { chunk -> writeChunk(chunk) }
                    } catch (e: CancellationException) {
                        throw e // let flush() interrupt playback cleanly
                    } catch (_: Exception) {
                        // ignore playback errors; still unblock the caller
                    }
                }
                currentPlay = play
                try {
                    play.join()
                } catch (_: CancellationException) {
                    // interrupted by flush() (barge-in)
                } finally {
                    currentPlay = null
                    job.done.complete(Unit)
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

    override fun play(pcm: Flow<ByteArray>): Deferred<Unit> {
        val done = CompletableDeferred<Unit>()
        queue.trySend(PlayJob(pcm, done))
        return done
    }

    override fun flush() {
        currentPlay?.cancel() // stop feeding the current TTS stream (barge-in)
        runCatching { track.flush() } // drop buffered PCM
    }

    override fun release() {
        actorJob.cancel()
        runCatching { track.flush() }
        track.release()
    }
}
