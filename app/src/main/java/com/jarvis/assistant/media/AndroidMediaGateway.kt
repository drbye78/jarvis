package com.jarvis.assistant.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.provider.Settings
import android.view.KeyEvent
import com.jarvis.assistant.service.JarvisNotificationListener
import timber.log.Timber
import java.net.URLEncoder

/**
 * Android adapter for the MUSIC lane contracts. Thin on purpose: every
 * decision (strategy cascade, verification heuristics) lives in
 * [MusicPlaybackOrchestrator] so it stays JVM-testable.
 *
 * Permission model:
 *  - getActiveSessions() requires OUR NotificationListenerService to be
 *    enabled by the user (requested during onboarding; the same access the
 *    ducking logic already uses).
 *  - media keys need no permission.
 *  - launching/deep-linking other apps needs no permission; package
 *    visibility on Android 11+ is covered by the manifest <queries> block
 *    (launcher-intent query + explicit Yandex Music package names).
 */
class AndroidMediaGateway(private val context: Context) : MediaGateway {

    private val appContext = context.applicationContext
    private val listenerComponent = ComponentName(appContext, JarvisNotificationListener::class.java)

    // ------------------------------------------------------------------
    // MediaGateway
    // ------------------------------------------------------------------

    override fun hasNotificationListenerAccess(): Boolean = runCatching {
        val enabled = Settings.Secure.getString(
            appContext.contentResolver,
            "enabled_notification_listeners",
        ) ?: return@runCatching false
        enabled.split(':').any { it.equals(listenerComponent.flattenToString(), ignoreCase = true) }
    }.getOrDefault(false)

    override fun activeControllers(): List<MediaControllerHandle> = runCatching {
        val msm = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        msm.getActiveSessions(listenerComponent).map { AndroidControllerHandle(it) }
    }.getOrElse { e ->
        // SecurityException = listener not enabled; remote process hiccups
        // also land here. Callers treat an empty list as "no sessions".
        Timber.w(e, "getActiveSessions failed")
        emptyList()
    }

    override fun dispatchMediaKey(keyCode: Int) {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }.onFailure { Timber.w(it, "dispatchMediaKeyEvent(%d) failed", keyCode) }
    }

    override fun openAppSearch(app: MediaAppInfo, query: String): Boolean {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchIntents = when (app.packageName) {
            // Yandex Music moved to ru.yandex.music; com.yandex.music is the
            // legacy id some sideloaded APKs still use.
            "ru.yandex.music", "com.yandex.music" -> listOf(
                uriIntent("yandexmusic://search?query=$encoded"),
                uriIntent("https://music.yandex.ru/search/$encoded"),
            )
            else -> emptyList()
        }
        for (intent in searchIntents) {
            if (startActivitySafely(intent)) return true
        }
        return false
    }

    override fun launchApp(app: MediaAppInfo): Boolean {
        val launch = appContext.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startActivitySafely(launch)
    }

    private fun uriIntent(uri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun startActivitySafely(intent: Intent): Boolean = runCatching {
        appContext.startActivity(intent)
        true
    }.getOrElse { e ->
        Timber.w(e, "Cannot start %s", intent)
        false
    }

    // ------------------------------------------------------------------
    // MusicAppResolver
    // ------------------------------------------------------------------

    /**
     * Resolution order: brand-known package ids (Yandex first — the project's
     * target player), then any launchable app whose label looks like a music
     * player. An LLM-provided hint ("вк", "яндекс", "звук") pins the brand.
     */
    val resolver: MusicAppResolver = MusicAppCatalog(::installedLaunchables)

    /** Launchable apps as (packageName, label) — the input for [MusicAppCatalog]. */
    private fun installedLaunchables(): List<Pair<String, String>> = runCatching {
        val pm = appContext.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
    }.getOrDefault(emptyList())
}

/**
 * Wraps a framework [MediaController]. Remote apps can die between any two
 * calls — every method is best-effort and returns a safe fallback.
 */
private class AndroidControllerHandle(
    private val controller: MediaController,
) : MediaControllerHandle {

    override val packageName: String get() = controller.packageName

    override fun snapshot(): NowPlaying = runCatching {
        val pb = controller.playbackState
        val md = controller.metadata
        NowPlaying(
            title = md?.getText(android.media.MediaMetadata.METADATA_KEY_TITLE)?.toString(),
            artist = md?.getText(android.media.MediaMetadata.METADATA_KEY_ARTIST)?.toString()
                ?: md?.getText(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.toString(),
            state = pb?.state ?: NowPlaying.STATE_NONE,
            positionMs = pb?.position ?: 0L,
            durationMs = md?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
        )
    }.getOrDefault(NowPlaying(state = NowPlaying.STATE_NONE))

    private fun transport(block: (android.media.session.MediaController.TransportControls) -> Unit): Boolean =
        runCatching {
            block(controller.transportControls)
            true
        }.getOrDefault(false)

    override fun playFromSearch(query: String) =
        transport { it.playFromSearch(query, null) }

    override fun play() = transport { it.play() }
    override fun pause() = transport { it.pause() }
    override fun skipToNext() = transport { it.skipToNext() }
    override fun skipToPrevious() = transport { it.skipToPrevious() }
    override fun stop() = transport { it.stop() }
}
