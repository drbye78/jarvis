package com.jarvis.assistant.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.data.RingSessionEntity
import com.jarvis.assistant.service.AlarmRingingActivity
import com.jarvis.assistant.util.NotificationIds
import timber.log.Timber

/**
 * Ring notification channel (REMEDIATION_PLAN P3.3). The old code created
 * `jarvis_alarm` inline as a bare `NotificationChannel(..., IMPORTANCE_HIGH)`
 * with NO sound/vibration attributes, so the FSI-denied / notification-only
 * path was silently mute. Channel attributes are IMMUTABLE after creation, so
 * a fresh id is the only way an existing install picks up the alarm
 * sound/vibration — hence `jarvis_alarm_v2`.
 */
object NotificationChannels {
    const val ALARM_CHANNEL_ID = "jarvis_alarm_v2"

    /** Idempotent; safe to call on every ring. */
    fun ensureAlarmChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: run {
                Timber.e("NotificationManager unavailable — alarm channel not ensured")
                return
            }
        val channel = NotificationChannel(
            ALARM_CHANNEL_ID,
            context.getString(R.string.channel_alarm),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.let { uri ->
                setSound(
                    uri,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            }
            enableVibration(true)
            vibrationPattern = AlarmRingerPolicy.vibrationPattern()
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        // Never clobbers user customizations: createNotificationChannel is a
        // no-op when the channel already exists with the same id.
        nm.createNotificationChannel(channel)
    }
}

/**
 * THE one ringing-notification builder (REMEDIATION_PLAN P3.3): the receiver
 * and the ringing activity used to build near-identical notifications in two
 * places (the activity's re-post was the duplicate). It carries:
 *  - `contentIntent` -> [AlarmRingingActivity];
 *  - explicit **Dismiss** / **Snooze** broadcast actions (the previously
 *    orphaned [AlarmReceiver.ACTION_DISMISS] / [AlarmReceiver.ACTION_SNOOZE]);
 *  - `setDeleteIntent` -> dismiss (swipe = dismiss);
 *  - `setAutoCancel(false)` + `setOngoing(false)` (the alert can be swiped,
 *    and the delete intent still runs — no un-dismissable ongoing ring);
 *  - an alarm channel with explicit sound/vibration/importance.
 *
 * When `canUseFullScreenIntent()` is denied the notification cannot launch the
 * activity over the lock screen; instead of pretending, it deep-links the FSI
 * settings screen. Lockscreen privacy is preserved with VISIBILITY_PRIVATE +
 * a generic public version (the user-set label is content).
 */
object RingingNotifier {

    /**
     * Posts the ringing notification for [session]. Returns true when it was
     * handed to the NotificationManager. False means notifications are
     * disabled (or no NotificationManager), i.e. there is no notification
     * control surface — the caller must degrade honestly.
     *
     * @param fullScreenAllowed `NotificationManager.canUseFullScreenIntent()`
     *        (or true below API 34). When false the FSI is omitted and a
     *        deep-link action to the FSI settings is added.
     */
    fun post(context: Context, session: RingSessionEntity, fullScreenAllowed: Boolean): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Timber.w("Notifications disabled — ringing notification NOT posted for alert %d", session.alertId)
            return false
        }
        NotificationChannels.ensureAlarmChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: run {
                Timber.e("NotificationManager unavailable — ringing notification not posted")
                return false
            }

        val notificationId = NotificationIds.ringingId(session.alertId)
        val contentIntent = activityPendingIntent(context, session)
        val dismissIntent = broadcastPendingIntent(context, session, AlarmReceiver.ACTION_DISMISS)
        val snoozeIntent = broadcastPendingIntent(context, session, AlarmReceiver.ACTION_SNOOZE)

        val publicVersion = NotificationCompat.Builder(context, NotificationChannels.ALARM_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.alarm_notification_title))
            .setContentText(context.getString(R.string.alarm_public_text))
            .setSmallIcon(R.drawable.ic_mic)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()

        val builder = NotificationCompat.Builder(context, NotificationChannels.ALARM_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.alarm_notification_title))
            .setContentText(session.label)
            .setSmallIcon(R.drawable.ic_mic)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(contentIntent)
            .setDeleteIntent(dismissIntent)
            .setAutoCancel(false)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .addAction(0, context.getString(R.string.dismiss), dismissIntent)
            .addAction(0, context.getString(R.string.snooze), snoozeIntent)

        if (fullScreenAllowed) {
            builder.setFullScreenIntent(contentIntent, true)
        } else {
            // Honest degrade (P3.4): no over-lock-screen launch. Offer the
            // exact settings screen that restores it instead of pretending.
            // The FSI permission only exists on API 34+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                flagsSettingsIntent(context, session)?.let { settings ->
                    builder.addAction(
                        0,
                        context.getString(R.string.ring_allow_full_screen),
                        settings,
                    )
                }
            }
            Timber.w("Full-screen intent denied — ringing notification cannot launch over lock screen")
        }

        nm.notify(notificationId, builder.build())
        return true
    }

    /** Cancels the ring notification (dismiss/snooze/cancel-tool cleanup). */
    fun cancel(context: Context, alertId: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.cancel(NotificationIds.ringingId(alertId))
    }

    private fun activityPendingIntent(context: Context, session: RingSessionEntity): PendingIntent =
        PendingIntent.getActivity(
            context,
            // Raw row id — parity with the AlarmManager/FSI request codes.
            NotificationIds.ringingRequestCode(session.alertId),
            ringActivityIntent(context, session),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun broadcastPendingIntent(
        context: Context,
        session: RingSessionEntity,
        action: String,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            NotificationIds.ringingRequestCode(session.alertId),
            Intent(context, AlarmReceiver::class.java).apply {
                this.action = action
                putExtra(AlarmReceiver.EXTRA_ALERT_ID, session.alertId)
                putExtra(AlarmReceiver.EXTRA_RING_TOKEN, session.token)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * API 34+ FSI settings (`ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`).
     * Returns null when the OEM ROM cannot resolve the action (honest: omit
     * the action rather than add one that crashes on tap).
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun flagsSettingsIntent(context: Context, session: RingSessionEntity): PendingIntent? =
        try {
            PendingIntent.getActivity(
                context,
                NotificationIds.ringingRequestCode(session.alertId),
                Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:${context.packageName}"),
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } catch (e: Exception) {
            Timber.w(e, "Full-screen-intent settings screen unavailable")
            null
        }

    /** Shared activity intent: [AlarmRingingActivity] + the ring-session token. */
    fun ringActivityIntent(context: Context, session: RingSessionEntity): Intent =
        Intent(context, AlarmRingingActivity::class.java).apply {
            action = if (session.isTimer) AlarmReceiver.ACTION_TIMER_FIRED else AlarmReceiver.ACTION_ALARM_FIRED
            putExtra(AlarmReceiver.EXTRA_ALERT_ID, session.alertId)
            putExtra(AlarmReceiver.EXTRA_LABEL, session.label)
            putExtra(AlarmReceiver.EXTRA_IS_TIMER, session.isTimer)
            putExtra(AlarmReceiver.EXTRA_RING_TOKEN, session.token)
            putExtra(AlarmReceiver.EXTRA_TRIGGER_AT, session.firedTriggerMillis)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
}
