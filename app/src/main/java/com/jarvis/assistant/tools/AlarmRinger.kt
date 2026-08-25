package com.jarvis.assistant.tools

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import timber.log.Timber

/**
 * Shared ringer for alarms/timers: looping alarm-stream sound + vibration.
 * Used by the full-screen ringing activity; stopped on dismiss/snooze and
 * auto-stops after a maximum duration so a missed alarm cannot ring forever.
 */
object AlarmRinger {

    @Volatile private var player: MediaPlayer? = null
    @Volatile private var vibrating = false
    private var vibrator: Vibrator? = null

    fun start(context: Context, maxDurationMs: Long = 5 * 60 * 1000L) {
        stop(context, quiet = true)
        val appContext = context.applicationContext
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val mp = MediaPlayer()
            mp.setDataSource(appContext, uri)
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            Timber.e(e, "Alarm ringer failed to start")
        }

        try {
            val v = appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
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

        // Safety: never ring longer than maxDuration.
        Thread {
            try {
                Thread.sleep(maxDurationMs)
                stop(appContext)
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop(context: Context, quiet: Boolean = false) {
        try {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {
        }
        player = null
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        vibrating = false
        if (!quiet) Timber.i("Alarm ringer stopped")
    }

    fun isRinging(): Boolean = try {
        player?.isPlaying == true || vibrating
    } catch (_: Exception) {
        false
    }
}
