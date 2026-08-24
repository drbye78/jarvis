package com.jarvis.assistant.service

import android.service.notification.NotificationListenerService

/**
 * Empty [NotificationListenerService] subclass.
 *
 * Its only purpose is to be a bound notification-listener component so that
 * [android.media.session.MediaSessionManager.getActiveSessions] (used by
 * [JarvisForegroundService] for ducking) has a valid token. No notification
 * handling is performed.
 */
class JarvisNotificationListener : NotificationListenerService()
