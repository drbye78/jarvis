package com.jarvis.assistant.service

import android.service.notification.NotificationListenerService

/**
 * Bound notification-listener component giving MediaSessionManager a valid
 * token for ducking. No notification data is read or stored.
 */
class JarvisNotificationListener : NotificationListenerService()
