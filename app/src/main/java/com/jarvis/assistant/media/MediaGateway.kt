package com.jarvis.assistant.media

/**
 * Player-agnostic external media control contracts (MUSIC lane).
 *
 * Goal of this lane: make "Джарвис, включи Bohemian Rhapsody" work by
 * *ordering an installed player app* (Яндекс Музыка by default) to search
 * and play, instead of streaming audio ourselves.
 *
 * Why interfaces: the whole cascade must be JVM-unit-testable. The Android
 * implementation ([AndroidMediaGateway]) is a thin adapter over
 * MediaSessionManager / PackageManager / AudioManager, mirroring how
 * `contracts/WakeWordDetector` keeps the detector logic testable.
 *
 * The control path is the one documented for assistants at
 * https://developer.android.com/media/implement/assistant: with a bound
 * NotificationListenerService the app may call MediaSessionManager
 * .getActiveSessions() and drive another app's MediaSession via
 * MediaController.TransportControls (playFromSearch / play / pause / …).
 * Whether a given player *implements* onPlayFromSearch is app-dependent —
 * that is why [MusicPlaybackOrchestrator] verifies playback actually
 * started and falls back to a deep-link search screen when it did not.
 */

/** A resolved external music app (e.g. Яндекс Музыка → ru.yandex.music). */
data class MediaAppInfo(
    val packageName: String,
    val label: String,
)

/** Snapshot of what a media controller is playing right now. */
data class NowPlaying(
    val title: String? = null,
    val artist: String? = null,
    /** One of PlaybackState.STATE_* ints; 0 when unknown. */
    val state: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
) {
    val isPlaying: Boolean get() = state == STATE_PLAYING || state == STATE_BUFFERING

    companion object {
        // Mirror of android.media.session.PlaybackState constants; re-declared
        // here so the contract layer does not depend on the Android SDK.
        const val STATE_BUFFERING = 6
        const val STATE_PLAYING = 3
        const val STATE_PAUSED = 2
        const val STATE_STOPPED = 4
        const val STATE_NONE = 0
    }
}

/** Media key codes (mirror of android.view.KeyEvent codes we dispatch). */
object MediaKey {
    const val PLAY = 126
    const val PAUSE = 127
    const val PLAY_PAUSE = 85
    const val NEXT = 87
    const val PREVIOUS = 89
    const val STOP = 86
}

/**
 * A live handle to another app's MediaSession. Handles are snapshots: they
 * go stale when the owning app dies or releases its session — every method
 * is best-effort and must never throw (remote apps can vanish mid-call).
 */
interface MediaControllerHandle {
    val packageName: String
    fun snapshot(): NowPlaying
    fun playFromSearch(query: String): Boolean
    fun play(): Boolean
    fun pause(): Boolean
    fun skipToNext(): Boolean
    fun skipToPrevious(): Boolean
    fun stop(): Boolean
}

/**
 * Facade over the Android media stack (MediaSessionManager via the bound
 * notification listener, AudioManager media keys, PackageManager).
 */
interface MediaGateway {
    /** False until the user grants notification-listener access. */
    fun hasNotificationListenerAccess(): Boolean

    /** Fresh snapshot of every ACTIVE media session on the device. */
    fun activeControllers(): List<MediaControllerHandle>

    /**
     * Fire a global media key at the audio stack. Goes to whichever app
     * currently owns media focus — works even without listener access.
     */
    fun dispatchMediaKey(keyCode: Int)

    /** Deep-link the target app into its search screen for [query]. */
    fun openAppSearch(app: MediaAppInfo, query: String): Boolean

    /** Best-effort cold start of the target app. */
    fun launchApp(app: MediaAppInfo): Boolean
}

/** Resolves which installed player a "play music" command should target. */
interface MusicAppResolver {
    /**
     * @param appHint free-form app name from the LLM ("Яндекс", "вк", "звук")
     *   or null when the user did not name a player.
     */
    fun resolve(appHint: String?): MediaAppInfo?
}
