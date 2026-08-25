package com.jarvis.assistant.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.tools.AlarmRinger
import kotlinx.coroutines.launch

/**
 * Full-screen alarm ringing experience (the original AlarmReceiver only
 * logged a line). Shows over the lock screen with the screen lit, loops the
 * alarm sound, vibrates, and offers Dismiss / Snooze (+10 min).
 *
 * Doubles as the "ringer service": launching this activity IS the ring; it
 * stops ringer + notification in onDestroy, with a 5-minute auto-timeout
 * enforced inside [AlarmRinger].
 */
class AlarmRingingActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = intent.getStringExtra("label") ?: "Будильник"
        val isTimer = intent.getBooleanExtra("is_timer", false)
        val alarmId = intent.getLongExtra("alarm_id", -1L)

        setContentView(R.layout.activity_alarm_ringing)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        findViewById<android.widget.TextView>(R.id.alarmLabel).text = label
        findViewById<android.widget.TextView>(R.id.alarmTime).text =
            if (isTimer) getString(R.string.timer_done) else currentTime()

        findViewById<android.widget.Button>(R.id.btnDismiss).setOnClickListener {
            AlarmRinger.stop(this)
            stopRingingNotification()
            // Daily alarms stay scheduled for tomorrow (setAlarmClock is
            // one-shot, so re-arm now).
            if (alarmId >= 0 && !isTimer) {
                rearmDaily(alarmId)
            }
            finish()
        }

        findViewById<android.widget.Button>(R.id.btnSnooze).setOnClickListener {
            AlarmRinger.stop(this)
            stopRingingNotification()
            if (alarmId >= 0 && !isTimer) {
                snooze(alarmId)
            }
            finish()
        }

        postRingingNotification(label)
        AlarmRinger.start(this)
    }

    private fun rearmDaily(alarmId: Long) {
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
        )
        scope.launch {
            try {
                val dao = com.jarvis.assistant.data.AppDatabase
                    .getInstance(this@AlarmRingingActivity).alarmDao()
                val scheduler = com.jarvis.assistant.tools.AndroidAlarmScheduler(
                    this@AlarmRingingActivity, dao
                )
                scheduler.setEnabled(alarmId, true)
            } catch (e: Exception) {
                // Alarm may have been one-shot or deleted — fine.
            }
        }
    }

    private fun snooze(alarmId: Long) {
        val trigger = System.currentTimeMillis() + 10 * 60 * 1000L
        val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = Intent(this, com.jarvis.assistant.tools.AlarmReceiver::class.java).apply {
            action = com.jarvis.assistant.tools.AlarmReceiver.ACTION_ALARM_FIRED
            putExtra("alarm_id", alarmId)
            putExtra("label", getString(R.string.snooze_label))
        }
        val pending = android.app.PendingIntent.getBroadcast(
            this, (alarmId + 100_000).toInt(), intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, trigger, pending)
    }

    private fun postRingingNotification(label: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    "jarvis_alarm", getString(R.string.channel_alarm),
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }
        val fullScreen = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, "jarvis_alarm")
            .setContentTitle(getString(R.string.alarm_notification_title))
            .setContentText(label)
            .setSmallIcon(R.drawable.ic_mic)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreen, true)
            .setOngoing(true)
            .build()
        nm.notify(ALARM_NOTIFICATION_ID, notification)
    }

    private fun stopRingingNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(ALARM_NOTIFICATION_ID)
    }

    private fun currentTime(): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())

    override fun onDestroy() {
        AlarmRinger.stop(this, quiet = true)
        super.onDestroy()
    }

    companion object {
        private const val ALARM_NOTIFICATION_ID = 500
    }
}
