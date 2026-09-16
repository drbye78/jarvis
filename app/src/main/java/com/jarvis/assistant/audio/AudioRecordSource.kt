package com.jarvis.assistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import com.jarvis.assistant.audio.aec.AecMode
import com.jarvis.assistant.audio.aec.AecProbe
import com.jarvis.assistant.audio.aec.MicProfile
import com.jarvis.assistant.contracts.AudioSource
import com.jarvis.assistant.contracts.AudioSpec
import timber.log.Timber

/**
 * On-device microphone source backed by AudioRecord.
 *
 * Captures 16 kHz / mono / 16-bit PCM in 320-sample (20 ms) frames. [read]
 * ALWAYS returns a private copy of the internal capture buffer (audit #8:
 * the full-frame fast path used to return the reused internal array itself —
 * safe only while every downstream consumer happened to copy; any future
 * EchoCanceller implementation that retains its input would corrupt audio).
 *
 * AEC Phase A: the capture profile is [MicProfile] instead of a hard-wired
 * VOICE_RECOGNITION. HARDWARE mode captures through VOICE_COMMUNICATION and
 * attaches the platform AcousticEchoCanceler via [AecProbe] (probe outcome
 * lands in AecDiag + Settings). OFF/SOFTWARE keep the clean VOICE_RECOGNITION
 * lane (SOFTWARE cancellation happens downstream in [AudioPipeline] — a HW
 * effect on the mic would break the electrical reference's linearity).
 *
 * P1-S #4 (audit 2026-09-16): the record is published through a
 * reader-counted handle ([live] / [readersInFlight] / [awaitingReader]), so
 * [stop] can never release an AudioRecord that another thread is reading in
 * native code. [read] on a closed source throws
 * [IllegalStateException]("AudioRecordSource not started"), which
 * [AudioPipeline] treats as a clean producer exit.
 *
 * P1-S #4(d) test seam: the live capture session is expressed as a
 * [CaptureHandle] built by an injected factory. Production uses
 * [androidHandleFactory] (real AudioRecord + optional HW AEC); JVM tests
 * inject a fake whose read() parks like the blocking native call, so the
 * stop-while-read-in-flight protocol is exercisable without the framework.
 * The public constructor keeps its exact previous fail-fast behavior
 * (degenerate getMinBufferSize throws at construction).
 */
class AudioRecordSource internal constructor(
    private val spec: AudioSpec,
    private val handleFactory: () -> CaptureHandle,
) : AudioSource {

    constructor(
        spec: AudioSpec = AudioSpec.MIC,
        profile: MicProfile = MicProfile.forMode(AecMode.OFF),
    ) : this(spec, androidHandleFactory(spec, profile))

    companion object {
        /**
         * Pure decision (m5): fail fast when the framework reports a broken
         * buffer size instead of accepting a source that can never deliver
         * audio. JVM-testable without the framework call itself.
         */
        internal fun validatedBufferSize(raw: Int, sampleRate: Int): Int {
            if (raw <= 0) {
                throw IllegalStateException(
                    "AudioRecord.getMinBufferSize returned $raw at ${sampleRate}Hz/mono/16-bit — microphone source unusable",
                )
            }
            return raw
        }

        /**
         * Production handle factory. The framework call that can never yield a
         * usable buffer is evaluated HERE — at source construction — so the
         * m5 fail-fast timing of the old eager `bufferSize` field is kept;
         * the returned closure only touches the framework again when a real
         * capture session is opened by [AudioRecordSource.start].
         */
        private fun androidHandleFactory(spec: AudioSpec, profile: MicProfile): () -> CaptureHandle {
            val bufferSize = validatedBufferSize(
                android.media.AudioRecord.getMinBufferSize(
                    spec.sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ),
                spec.sampleRate,
            )
            return { openAndroidHandle(spec, profile, bufferSize) }
        }

        /**
         * Builds + starts one real capture session. RECORD_AUDIO is gated by
         * JarvisForegroundService.ensureInitialized before the graph (and this
         * source) can exist; a revoked grant surfaces through the
         * STATE_INITIALIZED check instead of a ctor throw.
         */
        @SuppressLint("MissingPermission")
        private fun openAndroidHandle(spec: AudioSpec, profile: MicProfile, bufferSize: Int): CaptureHandle {
            val record = android.media.AudioRecord(
                profile.androidAudioSource,
                spec.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
            if (record.state != android.media.AudioRecord.STATE_INITIALIZED) {
                record.release()
                error("AudioRecord failed to initialize")
            }
            val canceler = if (profile.attachHardwareAec) {
                // AecProbe records the outcome (AecDiag + persisted for Settings).
                AecProbe.attach(record.audioSessionId)
            } else {
                null
            }
            record.startRecording()
            return AndroidCaptureHandle(record, canceler)
        }
    }

    /**
     * P1-S #4 (audit 2026-09-16): one live capture session plus the effect
     * attached to it, behind the [CaptureHandle] seam. Bundled so a deferred
     * teardown can never release the WRONG record: a reader that is still
     * inside native `read()` when [stop] runs must release exactly the handle
     * it was reading, and a [start] that happens in the meantime publishes a
     * different handle entirely.
     */
    private class AndroidCaptureHandle(
        private val record: android.media.AudioRecord,
        private val canceler: android.media.audiofx.AcousticEchoCanceler?,
    ) : CaptureHandle {
        override fun read(dest: ShortArray, offset: Int, length: Int): Int = record.read(dest, offset, length)

        /**
         * Releases the record and its effect. Never throws out of a teardown path.
         *
         * The generic catch is the contract: OEM `stop()`/`release()` can raise
         * anything at all — including Errors like `UnsatisfiedLinkError` from a
         * wedged native library — and a leaking throw here would abandon the
         * engine (the exact resource class the bounded-release path exists to
         * protect). Narrowing the catch would re-open that hole.
         */
        @Suppress("TooGenericExceptionCaught")
        override fun shutdown() {
            try {
                record.stop()
            } catch (t: Throwable) {
                Timber.w(t, "AudioRecord.stop() failed (release still attempted)")
            } finally {
                try {
                    record.release()
                } finally {
                    handleCancelerRelease()
                }
            }
        }

        // Same teardown contract as shutdown(): a throwing AEC release must not
        // escape the finally block that depends on it.
        @Suppress("TooGenericExceptionCaught")
        private fun handleCancelerRelease() {
            canceler?.let { c ->
                try {
                    c.release()
                } catch (t: Throwable) {
                    Timber.w(t, "AcousticEchoCanceler.release() failed (ignored)")
                }
            }
        }
    }

    /**
     * Guards [live] / [readersInFlight] / [awaitingReader]. Held ONLY for
     * bookkeeping and for the actual stop/release — never across
     * `record.read()`, so a teardown can never block on (or, worse, race) a
     * blocking capture read.
     */
    private val handleLock = Any()

    /** The published handle, or null when stopped / not yet started. */
    private var live: CaptureHandle? = null

    /**
     * Reads currently inside native `AudioRecord.read()`. Releasing a record
     * underneath them is a use-after-free in the HAL (SIGSEGV — not a Java
     * exception), so [stop] defers the teardown to the last exiting reader
     * instead of waiting for it.
     */
    private var readersInFlight = 0

    /** Handles closed by [stop] while readers were in flight, awaiting release. */
    private val awaitingReader = mutableListOf<CaptureHandle>()

    /**
     * Set when a [read] observed a negative AudioRecord error code (HAL
     * failure). A record whose session hit read errors is frequently
     * unrecoverable — every subsequent read keeps returning negatives — so
     * [start] tears it down and builds a FRESH one (this is what makes the
     * service's 15-min watchdog revive actually work after a HAL failure:
     * the old early-return-when-non-null start() reused the broken record
     * forever). Cleared on every successful (re)start.
     */
    @Volatile private var hadReadError = false

    // 20 ms @ 16 kHz == 320 samples, matching the Silero VAD frame size.
    private val frameSize = (spec.sampleRate * 20) / 1000

    private val bufferA = ShortArray(frameSize)
    private val bufferB = ShortArray(frameSize)
    private var useA = true

    /**
     * Opens + publishes one capture session through [handleFactory]. A factory
     * failure (AudioRecord init rejected, bad profile) throws BEFORE anything
     * is published, so [read] can never observe a half-started handle.
     */
    override fun start() {
        val started = synchronized(handleLock) { live != null }
        if (started) {
            if (!hadReadError) return
            // The previous session hit read errors (HAL failure): reuse of the
            // same AudioRecord would keep failing forever. Tear it down so a
            // fresh record is created below — this is the watchdog-revive path.
            Timber.w("AudioRecordSource: recreating AudioRecord after read errors")
            stop()
        }
        val handle = handleFactory()
        synchronized(handleLock) { live = handle }
        hadReadError = false
    }

    override fun read(): ShortArray {
        val handle = acquireHandle()
        val buf = if (useA) bufferA else bufferB
        useA = !useA
        val read = try {
            handle.read(buf, 0, frameSize)
        } finally {
            exitRead()
        }
        if (read < 0) {
            // AudioRecord.read reports HAL failures as a NEGATIVE error code
            // (no exception). This MUST surface as a real failure, not an
            // empty frame: the empty-frame conversion bypassed the pipeline's
            // failure counter entirely (empty frames are skipped without
            // counting and without any delay → hot spin, give-up never set,
            // watchdog never revived). 0 stays "no data yet" — honest, not an
            // error.
            hadReadError = true
            throw java.io.IOException(
                "AudioRecord.read failed with error code $read (${describeReadError(read)})",
            )
        }
        if (read == 0) return ShortArray(0)
        // Audit #8: always hand out a private copy — never the reused internal
        // buffer — so every downstream consumer may safely retain the frame.
        return buf.copyOf(read)
    }

    /** Content-free name for the common negative AudioRecord.read codes. */
    private fun describeReadError(code: Int): String = when (code) {
        android.media.AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
        android.media.AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
        android.media.AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
        else -> "ERROR"
    }

    /**
     * Takes a reference for the duration of one native read, or throws the
     * contract's [IllegalStateException] when nothing is capturing (never
     * started, or [stop] already ran). The lock is NOT held across
     * `record.read()` — see [exitRead].
     */
    private fun acquireHandle(): CaptureHandle = synchronized(handleLock) {
        val handle = live ?: throw IllegalStateException("AudioRecordSource not started")
        readersInFlight++
        handle
    }

    /**
     * Ends one read. When this was the last in-flight reader AND [stop] parked
     * a handle, that handle is released here — exactly once, by the party that
     * can prove no read is touching it any more.
     */
    private fun exitRead() {
        synchronized(handleLock) {
            readersInFlight--
            if (readersInFlight == 0 && awaitingReader.isNotEmpty()) {
                val parked = awaitingReader.toList()
                awaitingReader.clear()
                parked.forEach { it.shutdown() }
            }
        }
    }

    /**
     * Closes the capture session. Returns as soon as the handle is
     * un-published — it never blocks on an in-flight native read (the service
     * calls this from binder/main paths); the record itself is released by the
     * last reader via [exitRead], or inline when no reader holds it.
     */
    override fun stop() {
        synchronized(handleLock) {
            val handle = live
            live = null
            if (handle != null) {
                if (readersInFlight == 0) handle.shutdown() else awaitingReader += handle
            }
        }
    }
}

/**
 * One live capture session (an AudioRecord + its optional hardware effect),
 * behind the P1-S #4(d) seam that lets JVM tests drive the reader-counted
 * teardown protocol of [AudioRecordSource] with a fake whose read() parks
 * exactly like the blocking native call.
 *
 * Contract: [read] maps 1:1 onto `AudioRecord.read` (blocking; negative error
 * code, 0 = no data yet, >0 = samples written); [shutdown] stops + releases
 * the session and NEVER throws (teardown paths must not fail).
 */
internal interface CaptureHandle {
    fun read(dest: ShortArray, offset: Int, length: Int): Int
    fun shutdown()
}
