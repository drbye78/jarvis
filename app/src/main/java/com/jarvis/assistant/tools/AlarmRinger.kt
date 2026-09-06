package com.jarvis.assistant.tools

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Pure decision core of [AlarmRinger] (REMEDIATION_PLAN P1.5): the ring-loop
 * timing, auto-stop threshold and vibration pattern, extracted so the JVM
 * suite can pin them. The MediaPlayer/vibrator WIRING itself stays in
 * [AlarmRinger] and is device-only (unit tests cover the decisions, not the
 * media plumbing).
 */
object AlarmRingerPolicy {

    /**
     * Auto-stop threshold: a missed alarm must not ring forever — the ringer
     * stops itself this long after [AlarmRinger.start]. Each start() restarts
     * the full window (it stops any previous ring first), so re-ringing the
     * same alert always gets a fresh watchdog budget.
     */
    const val DEFAULT_MAX_DURATION_MS = 5 * 60 * 1000L

    /**
     * Effective watchdog delay for a ring started with [maxDurationMs].
     * Defensive guard for a caller passing a non-positive duration: the
     * platform `delay()` tolerates it, but the policy says a ring NEVER
     * auto-stops instantly — the smallest legal budget is "no auto-stop
     * earlier than requested" and a 0/negative budget clamps to 0 (immediate
     * stop) rather than hanging forever.
     */
    fun autoStopDelayMs(maxDurationMs: Long = DEFAULT_MAX_DURATION_MS): Long =
        maxDurationMs.coerceAtLeast(0)

    /**
     * Vibration waveform: off 0 ms → vibrate 500 ms → pause 500 ms, repeated
     * from the start ([VIBRATION_REPEAT_INDEX]) for as long as the ringer is
     * alive. Non-pulse-0 pattern; the leading 0 keeps the waveform aligned
     * with the sound start.
     */
    const val VIBRATION_REPEAT_INDEX = 0

    fun vibrationPattern(): LongArray = longArrayOf(0, 500, 500)
}

/**
 * Shared ringer for alarms/timers: looping alarm-stream sound + vibration.
 * Used by the full-screen ringing activity; stopped on dismiss/snooze and
 * auto-stops after a maximum duration so a missed alarm cannot ring forever.
 *
 * m13: the auto-stop watchdog is a coroutine owned by this object's scope
 * (was an unsupervised daemon Thread per ring) and MediaPlayer.prepare runs
 * on Dispatchers.IO (was the main thread). Cancelling [stop] tears both down
 * deterministically.
 *
 * Decision math (auto-stop threshold, vibration pattern, repeat index) lives
 * in [AlarmRingerPolicy] — the JVM-testable core; everything touching
 * MediaPlayer/RingtoneManager/Vibrator below is device-only by nature.
 */
object AlarmRinger {

    /**
     * Process-scoped coroutine scope (singleton object — lives as long as the
     * process).  Intentional for this always-on appliance profile: the ringer
     * must survive configuration changes and activity lifecycle events; there
     * is no "destroy" path for the singleton itself.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    @Volatile private var vibrating = false
    private var autoStopJob: Job? = null

    fun start(context: Context, maxDurationMs: Long = AlarmRingerPolicy.DEFAULT_MAX_DURATION_MS) {
        stop(context, quiet = true)
        val appContext = context.applicationContext

        autoStopJob = scope.launch {
            val prepared = try {
                // Blocking prepare() stays off the caller thread (m13).
                withContext(Dispatchers.IO) { preparePlayer(appContext) }
            } catch (e: Exception) {
                Timber.e(e, "Alarm ringer failed to start")
                null
            }
            synchronized(lock) {
                if (!isActive) {
                    // stop() won the race while we were preparing.
                    prepared?.release()
                    return@synchronized
                }
                player = prepared
                prepared?.start()
            }
            delay(AlarmRingerPolicy.autoStopDelayMs(maxDurationMs))
            stop(appContext)
        }

        startVibration(appContext)
    }

    private fun preparePlayer(context: Context): MediaPlayer {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val mp = MediaPlayer()
        mp.setDataSource(context, uri)
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        mp.isLooping = true
        mp.prepare()
        return mp
    }

    private fun startVibration(appContext: Context) {
        try {
            // Audit #12: null-safe lookup — skip vibration, keep the sound.
            val v = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            vibrator = v
            vibrating = true
            val pattern = AlarmRingerPolicy.vibrationPattern()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, AlarmRingerPolicy.VIBRATION_REPEAT_INDEX))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, AlarmRingerPolicy.VIBRATION_REPEAT_INDEX)
            }
        } catch (e: Exception) {
            Timber.w(e, "Alarm vibration unavailable")
        }
    }

    fun stop(context: Context, quiet: Boolean = false) {
        autoStopJob?.cancel()
        autoStopJob = null
        synchronized(lock) {
            player?.let {
                try {
                    if (it.isPlaying) it.stop()
                } catch (_: Exception) {
                }
                try {
                    it.release()
                } catch (_: Exception) {
                }
            }
            player = null
        }
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        vibrating = false
        if (!quiet) Timber.i("Alarm ringer stopped")
    }

    fun isRinging(): Boolean = synchronized(lock) {
        try {
            player?.isPlaying == true || vibrating
        } catch (_: Exception) {
            false
        }
    }
}
