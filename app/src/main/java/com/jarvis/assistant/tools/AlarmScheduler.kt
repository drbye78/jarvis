package com.jarvis.assistant.tools

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.data.AlarmEntity
import timber.log.Timber
import java.util.Calendar

interface AlarmScheduler {
    fun schedule(label: String, hour: Int, minute: Int): AlarmEntity
    fun cancel(label: String, hour: Int, minute: Int)
}

class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {

    override fun schedule(label: String, hour: Int, minute: Int): AlarmEntity {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_MONTH, 1)
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("label", label)
            putExtra("time", "%02d:%02d".format(hour, minute))
        }
        val requestCode = (label + hour + minute).hashCode()
        val pending = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pending)
        return AlarmEntity(label = label, hour = hour, minute = minute, triggerMillis = calendar.timeInMillis)
    }

    override fun cancel(label: String, hour: Int, minute: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java)
        val requestCode = (label + hour + minute).hashCode()
        val pending = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pending)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra("label") ?: "Будильник"
        val time = intent.getStringExtra("time") ?: ""
        Timber.d("Alarm fired: $label at $time")

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Dedicated channel so the user can give alarms their own importance/sound
        val channel = NotificationChannel(
            ALARM_CHANNEL_ID,
            "Будильники",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Срабатывания будильника Jarvis"
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        notificationManager.createNotificationChannel(channel)

        // Tap-through opens the app
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, ALARM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(label)
            .setContentText(time.ifEmpty { "Будильник" })
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(contentIntent, true)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(ALARM_NOTIFICATION_ID, notification)

        // TODO: route spoken announcement through JarvisForegroundService's TTS
        // pipeline when it is running (needs an ACTION_SPEAK intent contract).
    }

    private companion object {
        const val ALARM_CHANNEL_ID = "jarvis_alarms"
        const val ALARM_NOTIFICATION_ID = 2001
    }
}