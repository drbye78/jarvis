package com.jarvis.assistant.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.assistant.data.AlarmDao
import com.jarvis.assistant.data.AlarmEntity
import timber.log.Timber
import java.util.Calendar

/** Pure date logic, JVM-testable. */
object AlarmTimes {

    /** Next wall-clock occurrence of hour:minute at or after [nowMillis]. */
    fun nextOccurrence(hour: Int, minute: Int, nowMillis: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= nowMillis) add(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    /** Parses "HH:mm" (or "H:mm") into (hour, minute), null if invalid. */
    fun parseTime(text: String): Pair<Int, Int>? {
        val parts = text.trim().split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }
}

/**
 * Schedules alarms through [AlarmManager.setAlarmClock] — the correct API for
 * user-facing alarms: fires reliably through Doze, and shows the system
 * alarm-clock indicator. The DB row id is the PendingIntent request code, so
 * cancel is always exact (the original hashed label+hour+minute and could
 * collide).
 */
class AndroidAlarmScheduler(
    private val context: Context,
    private val dao: AlarmDao,
) {

    suspend fun schedule(label: String, hour: Int, minute: Int, repeatDaily: Boolean): AlarmEntity {
        val trigger = AlarmTimes.nextOccurrence(hour, minute)
        val entity = AlarmEntity(
            label = label,
            hour = hour,
            minute = minute,
            repeatDaily = repeatDaily,
            triggerMillis = trigger,
        )
        val id = dao.insert(entity)
        scheduleAlarmClock(id, trigger, label, hour, minute)
        Timber.i("Alarm scheduled: '%s' at %02d:%02d (id=%d)", label, hour, minute, id)
        return entity.copy(id = id)
    }

    suspend fun scheduleTimer(label: String, delayMillis: Long) {
        val trigger = System.currentTimeMillis() + delayMillis
        val intent = timerIntent(trigger.toInt(), label)
        val pending = PendingIntent.getBroadcast(
            context,
            TIMER_REQUEST_BASE + trigger.toInt(), // unique per timer
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
    }

    private fun timerIntent(id: Int, label: String): Intent =
        Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_TIMER_FIRED
            putExtra(AlarmReceiver.EXTRA_LABEL, label)
            putExtra(AlarmReceiver.EXTRA_TIMER_ID, id)
        }

    suspend fun cancel(id: Long) {
        cancelPending(id)
        dao.delete(id)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        dao.setEnabled(id, enabled)
        val alarm = dao.byId(id) ?: return
        if (enabled) {
            scheduleAlarmClock(alarm.id, AlarmTimes.nextOccurrence(alarm.hour, alarm.minute), alarm.label, alarm.hour, alarm.minute)
        } else {
            cancelPending(id)
        }
    }

    private fun scheduleAlarmClock(id: Long, triggerAt: Long, label: String, hour: Int, minute: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val fire = fireIntent(id, label)
        val operation = PendingIntent.getBroadcast(
            context, id.toInt(), fire,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val show = showIntent(id)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), operation)
    }

    private fun cancelPending(id: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(PendingIntent.getBroadcast(
            context, id.toInt(), fireIntent(id, ""),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ))
    }

    private fun fireIntent(id: Long, label: String) =
        Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_ALARM_FIRED
            putExtra(AlarmReceiver.EXTRA_ALARM_ID, id)
            putExtra(AlarmReceiver.EXTRA_LABEL, label)
        }

    private fun showIntent(id: Long): PendingIntent =
        PendingIntent.getActivity(
            context, id.toInt(),
            Intent(context, com.jarvis.assistant.service.AlarmRingingActivity::class.java).apply {
                action = AlarmReceiver.ACTION_ALARM_FIRED
                putExtra(AlarmReceiver.EXTRA_ALARM_ID, id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Re-arms every enabled alarm (called from BootReceiver). */
    suspend fun rescheduleAll() {
        val alarms = dao.enabled()
        for (a in alarms) {
            val trigger = if (a.repeatDaily) {
                AlarmTimes.nextOccurrence(a.hour, a.minute)
            } else {
                if (a.triggerMillis > System.currentTimeMillis()) a.triggerMillis else continue
            }
            scheduleAlarmClock(a.id, trigger, a.label, a.hour, a.minute)
        }
        Timber.i("Rescheduled %d alarms after boot", alarms.size)
    }

    companion object {
        const val TIMER_REQUEST_BASE = 500_000
    }
}

/**
 * Fired by AlarmManager when an alarm or timer triggers. Starts the ringing
 * experience (full-screen activity + sound + TTS) instead of the original
 * implementation that logged one line and did nothing.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ALARM_FIRED, ACTION_TIMER_FIRED -> {
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "Будильник"
                val isTimer = intent.action == ACTION_TIMER_FIRED
                val service = Intent(context, com.jarvis.assistant.service.AlarmRingingActivity::class.java).apply {
                    putExtra(EXTRA_LABEL, label)
                    putExtra(EXTRA_IS_TIMER, isTimer)
                    putExtra(EXTRA_ALARM_ID, intent.getLongExtra(EXTRA_ALARM_ID, -1L))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                // Full-screen activity doubles as the ringer; showWhenLocked
                // + turnScreenOn light up the tablet even on the lock screen.
                context.startActivity(service)
            }

            ACTION_SNOOZE -> {
                // Handled by the ringing activity itself (it cancels its own ringer).
            }

            ACTION_DISMISS -> {
                AlarmRinger.stop(context)
            }
        }
    }

    companion object {
        const val ACTION_ALARM_FIRED = "com.jarvis.assistant.ALARM_FIRED"
        const val ACTION_TIMER_FIRED = "com.jarvis.assistant.TIMER_FIRED"
        const val ACTION_SNOOZE = "com.jarvis.assistant.ALARM_SNOOZE"
        const val ACTION_DISMISS = "com.jarvis.assistant.ALARM_DISMISS"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_LABEL = "label"
        const val EXTRA_IS_TIMER = "is_timer"
        const val EXTRA_TIMER_ID = "timer_id"
    }
}
