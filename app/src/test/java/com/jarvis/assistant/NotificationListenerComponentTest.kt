package com.jarvis.assistant

import com.jarvis.assistant.media.NotificationListenerComponent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M-6: `enabled_notification_listeners` stores flattened ComponentNames, but
 * the framework has TWO flatten forms (`pkg/full.Class` and `pkg/.Class`) and
 * different OEM ROMs persist different ones. Comparing the stored entry with
 * only the long form silently reported "no notification access" — and with it
 * disabled the whole media lane — on short-form devices.
 */
class NotificationListenerComponentTest {

    private val pkg = "com.jarvis.assistant"
    private val cls = "com.jarvis.assistant.media.JarvisNotificationListener"

    @Test
    fun `matches the long flattened form`() {
        assertTrue(NotificationListenerComponent.matches("$pkg/$cls", pkg, cls))
    }

    @Test
    fun `expands and matches the short flattened form`() {
        assertTrue(
            NotificationListenerComponent.matches(
                "$pkg/.media.JarvisNotificationListener",
                pkg,
                cls,
            ),
        )
    }

    @Test
    fun `is case-insensitive and tolerant of surrounding whitespace`() {
        assertTrue(NotificationListenerComponent.matches("  $pkg/$cls  ", pkg, cls))
        assertTrue(NotificationListenerComponent.matches("COM.JARVIS.ASSISTANT/$cls", pkg, cls))
    }

    @Test
    fun `rejects a different component in the same package`() {
        assertFalse(
            NotificationListenerComponent.matches(
                "$pkg/com.jarvis.assistant.OtherListener",
                pkg,
                cls,
            ),
        )
    }

    @Test
    fun `rejects a different package with the same class suffix`() {
        assertFalse(
            NotificationListenerComponent.matches(
                "com.other.app/com.other.app.SomeListener",
                pkg,
                cls,
            ),
        )
    }

    @Test
    fun `rejects malformed entries`() {
        assertFalse(NotificationListenerComponent.matches("", pkg, cls))
        assertFalse(NotificationListenerComponent.matches(pkg, pkg, cls))
        assertFalse(NotificationListenerComponent.matches("$pkg/", pkg, cls))
        assertFalse(NotificationListenerComponent.matches("/$cls", pkg, cls))
    }
}
