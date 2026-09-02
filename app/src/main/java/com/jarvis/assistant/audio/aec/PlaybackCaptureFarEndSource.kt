package com.jarvis.assistant.audio.aec

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import com.jarvis.assistant.contracts.AudioSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * AEC Phase B far-end lane: captures OTHER APPS' audio playback
 * (Yandex Music etc.) via [AudioPlaybackCapture] (API 29+) and feeds it to
 * the [EchoCanceller] — the wake-word-through-music scenario (audit M7).
 *
 * Requirements & honest limits (RUNBOOK):
 * - Needs a [MediaProjection] obtained from the system consent dialog —
 *   granted per service session (no silent background capture by design).
 * - Only USAGE_MEDIA / GAME / UNKNOWN playback is capturable; players can
 *   opt out; our own TTS is usage ASSISTANT (exempt — covered by the
 *   electrical TTS tap instead).
 * - Some builds deliver zero frames for opted-out players — the pump counts
 *   frames and reports the outcome under AecDiag.
 *
 * Pure decision logic is testable on the JVM; the framework wiring runs on
 * device only.
 */
class PlaybackCaptureFarEndSource(
    private val context: Context,
    private val canceller: EchoCanceller,
    private val scope: CoroutineScope,
) {
    companion object {
        /** Frame size for the capture lane (20 ms @ 16 kHz). */
        const val CAPTURE_FRAME_SAMPLES = 320

        /** 1 s with no frames at all → lane declared dead in AecDiag. */
        const val SILENT_LANE_WARN_MS = 1_000L

        fun apiSupported(): Boolean = Build.VERSION.SDK_INT >= 29

        /**
         * Pure decision (JVM-testable): which usages may be captured — the
         * configuration whitelists MEDIA/GAME/UNKNOWN, mirroring the
         * platform contract.
         */
        val CAPTURABLE_USAGES: List<Int> = listOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN,
        )

        /** Pure: is a given usage eligible for capture? */
        fun usageCapturable(usage: Int): Boolean = usage in CAPTURABLE_USAGES

        /** Far-end lane id for [FarEndMixer]. */
        const val LANE_PLAYBACK = "playback_capture"
    }

    private var record: AudioRecord? = null
    private var pump: Job? = null
    private var projection: MediaProjection? = null

    /** Total frames delivered to the canceller (AecDiag). */
    @Volatile var framesFed: Long = 0L
        private set

    /** True between [start] and [stop]. */
    @Volatile var running: Boolean = false
        private set

    /**
     * Start capturing with a consented projection. The consent flow lives in
     * the Settings UI (button → MediaProjectionManager screen-capture
     * intent); this method only consumes the granted result.
     */
    fun start(resultCode: Int, data: android.content.Intent) {
        if (!apiSupported()) {
            Timber.tag("AecDiag").w("playback capture unavailable: API ${Build.VERSION.SDK_INT} < 29")
            return
        }
        stop()
        try {
            val projectionManager =
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (projectionManager == null) {
                Timber.tag("AecDiag").e("MediaProjectionManager unavailable — playback capture not started")
                return
            }
            val proj = projectionManager.getMediaProjection(resultCode, data)
            projection = proj

            val config = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AudioSpec.MIC.sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                proj.stop()
                projection = null
                Timber.tag("AecDiag").e("playback capture AudioRecord failed to initialize")
                return
            }

            rec.startRecording()
            record = rec
            running = true
            framesFed = 0L
            pump = scope.launch(Dispatchers.IO) {
                val buf = ShortArray(CAPTURE_FRAME_SAMPLES)
                while (isActive && running) {
                    val read = try {
                        rec.read(buf, 0, buf.size)
                    } catch (e: IllegalStateException) {
                        Timber.tag("AecDiag").w(e, "playback capture read failed — lane stopping")
                        break
                    }
                    if (read > 0) {
                        val frame = if (read == buf.size) buf.copyOf() else buf.copyOf(read)
                        canceller.onFarEndFrame(LANE_PLAYBACK, frame)
                        framesFed++
                    }
                }
            }
            Timber.tag("AecDiag").i("playback capture started")
        } catch (e: Exception) {
            Timber.tag("AecDiag").e(e, "playback capture start failed")
            stop()
        }
    }

    fun stop() {
        running = false
        pump?.cancel()
        pump = null
        record?.apply {
            runCatching { stop() }
            runCatching { release() }
        }
        record = null
        projection?.apply { runCatching { stop() } }
        projection = null
    }

    /** One-line status for AecDiag dumps. */
    fun diagLine(): String =
        "captureLane running=$running frames=$framesFed api=${Build.VERSION.SDK_INT}"

    /** MediaProjection consent helper for the Settings UI. */
    fun createConsentIntent(): android.content.Intent? {
        if (!apiSupported()) return null
        val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as? MediaProjectionManager ?: return null
        return pm.createScreenCaptureIntent()
    }
}
