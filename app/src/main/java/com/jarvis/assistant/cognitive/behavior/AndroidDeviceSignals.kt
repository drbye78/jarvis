package com.jarvis.assistant.cognitive.behavior

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager

/**
 * COGNITIVE_PLAN §8.3 gates 2/4: the production [DeviceSignals] — real
 * Android state. Every read is defensive: a failure degrades to the
 * permissive answer (not-DND, battery-OK, no-media) because the arbiter's
 * other gates (IDLE, presence, cooldowns, quotas) still hold the line.
 */
class AndroidDeviceSignals(context: Context) : DeviceSignals {

    private val appContext = context.applicationContext

    override fun dndActive(): Boolean = try {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        when (nm?.currentInterruptionFilter) {
            null -> false
            NotificationManager.INTERRUPTION_FILTER_ALL,
            NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
            -> false
            else -> true
        }
    } catch (_: Exception) {
        false
    }

    @Suppress("DEPRECATION") // sticky BATTERY_CHANGED is the documented read
    override fun batteryOk(): Boolean = try {
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return true
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        if (charging) {
            true
        } else {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) true else level * 100 / scale > BATTERY_FLOOR_PERCENT
        }
    } catch (_: Exception) {
        true
    }

    override fun mediaActive(): Boolean = try {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        am?.isMusicActive ?: false
    } catch (_: Exception) {
        false
    }

    companion object {
        /** §8.3 gate 2: "battery > 15% or charging". */
        const val BATTERY_FLOOR_PERCENT = 15
    }
}
