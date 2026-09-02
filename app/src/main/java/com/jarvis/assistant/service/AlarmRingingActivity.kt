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
import com.jarvis.assistant.data.AppDatabase
import com.jarvis.assistant.tools.AlarmRinger
import com.jarvis.assistant.tools.AlarmReceiver
import com.jarvis.assistant.tools.AndroidAlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Full-screen alarm ringing experience (the original AlarmReceiver only
 * logged a line). Shows over the lock screen with the screen lit, loops the
 * alarm sound, vibrates, and offers Dismiss / Snooze (+10 min).
 *
 * Doubles as the "ringer service": launching this activity IS the ring; it
 * stops ringer + notification in onDestroy, with a 5-minute auto-timeout
 * enforced inside [AlarmRinger].
 *
 * M3: ALL scheduling math is delegated to [AndroidAlarmScheduler]. The daily
 * re-arm happens in onCreate via the IDEMPOTENT scheduler.onFired(id) — not
 * in button handlers — so back, HOME, process death and the auto-timeout
 * path still schedule tomorrow's occurrence.
 */
class AlarmRingingActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = intent.getStringExtra(AlarmReceiver.EXTRA_LABEL) ?: getString(R.string.default_alarm_label)
        val isTimer = intent.getBooleanExtra(AlarmReceiver.EXTRA_IS_TIMER, false)
        val alertId = intent.getIntExtra(AlarmReceiver.EXTRA_ALERT_ID, -1)

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
            stopRingingUi()
            // No scheduling here: onFired already ran below in onCreate.
            finish()
        }

        findViewById<android.widget.Button>(R.id.btnSnooze).setOnClickListener {
            stopRingingUi()
            if (alertId >= 0) {
                ioScope.launch { runCatching { scheduler().snooze(alertId) } }
            }
            finish()
        }

        postRingingNotification(label, alertId)
        AlarmRinger.start(this)

        if (alertId >= 0) {
            ioScope.launch { runCatching { scheduler().onFired(alertId) } }
        }
    }

    private fun scheduler(): AndroidAlarmScheduler =
        AndroidAlarmScheduler(this, AppDatabase.getInstance(this).alarmDao())

    private fun stopRingingUi() {
        AlarmRinger.stop(this)
        stopRingingNotification()
    }

    private fun postRingingNotification(label: String, alertId: Int) {
        // Same identity the AlarmReceiver used (audit #20: per-alert id, so
        // re-posting here UPDATES this alert's notification instead of
        // another alert's, and the cancel below hits the same one).
        val notificationId = AlarmReceiver.ringingNotificationId(alertId)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm == null) {
            Timber.e("NotificationManager unavailable — ringing notification not posted")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    "jarvis_alarm", getString(R.string.channel_alarm),
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }
        val fullScreen = PendingIntent.getActivity(
            this, notificationId, intent,
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
        nm.notify(notificationId, notification)
    }

    private fun stopRingingNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.cancel(
            AlarmReceiver.ringingNotificationId(
                intent.getIntExtra(AlarmReceiver.EXTRA_ALERT_ID, -1),
            ),
        )
    }

    private fun currentTime(): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())

    override fun onDestroy() {
        AlarmRinger.stop(this, quiet = true)
        super.onDestroy()
    }

    companion object {
        /**
         * Application-lifetime scope shared by every ringing instance: a fast
         * dismiss destroys the activity before its launch dispatches, and the
         * idempotent onFired/snooze writes MUST still complete (they are the
         * only re-arm path). Short-lived DB writes only; never cancelled.
         */
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
