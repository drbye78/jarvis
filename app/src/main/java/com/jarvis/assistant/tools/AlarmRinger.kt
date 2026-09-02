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
 * Shared ringer for alarms/timers: looping alarm-stream sound + vibration.
 * Used by the full-screen ringing activity; stopped on dismiss/snooze and
 * auto-stops after a maximum duration so a missed alarm cannot ring forever.
 *
 * m13: the auto-stop watchdog is a coroutine owned by this object's scope
 * (was an unsupervised daemon Thread per ring) and MediaPlayer.prepare runs
 * on Dispatchers.IO (was the main thread). Cancelling [stop] tears both down
 * deterministically.
 */
object AlarmRinger {

    private const val DEFAULT_MAX_DURATION_MS = 5 * 60 * 1000L

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

    fun start(context: Context, maxDurationMs: Long = DEFAULT_MAX_DURATION_MS) {
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
            delay(maxDurationMs)
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
            val pattern = longArrayOf(0, 500, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, 0)
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
