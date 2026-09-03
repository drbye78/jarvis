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
    val album: String? = null,
    val genre: String? = null,
    /** One of PlaybackState.STATE_* ints; 0 when unknown. */
    val state: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Tier 2: queue placement (-1/0 = unknown/empty). */
    val queueIndex: Int = -1,
    val queueSize: Int = 0,
    /** Tier 2: current playback speed (1.0 = normal). */
    val speed: Float = 1.0f,
    /** Tier 2: current repeat / shuffle modes (mirror constants). */
    val repeatMode: Int = 0,
    val shuffleMode: Int = 0,
) {
    val isPlaying: Boolean get() = state == STATE_PLAYING || state == STATE_BUFFERING

    companion object {
        // Mirror of android.media.session.PlaybackState constants; re-declared
        // here so the contract layer does not depend on the Android SDK.
        const val STATE_BUFFERING = 6
        const val STATE_PLAYING = 3
        const val STATE_PAUSED = 2
        // Framework STATE_STOPPED is 1 — value 4 is STATE_FAST_FORWARDING.
        // The wrong mirror made MusicDiag log a stopped session as "state1"
        // and a fast-forwarding one as "STOPPED".
        const val STATE_STOPPED = 1
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

    /**
     * Tier 0: capabilities decoded from this session's current
     * PlaybackState action mask. Default [MediaCapabilities.UNKNOWN] keeps
     * pure-JVM fakes permissive; the Android adapter reads the real mask.
     */
    fun capabilities(): MediaCapabilities = MediaCapabilities.UNKNOWN

    fun playFromSearch(query: String): Boolean

    /**
     * Tier 1: the Assistant voice-search contract — playFromSearch with a
     * structured extras Bundle (focus entry type + artist/album/playlist/
     * genre/title slots, see [SearchCommand]). Default: degrade to the flat
     * call (pure-JVM fakes and players that never read extras).
     */
    fun playFromSearchStructured(command: SearchCommand): Boolean =
        playFromSearch(command.query)

    fun play(): Boolean
    fun pause(): Boolean
    fun skipToNext(): Boolean
    fun skipToPrevious(): Boolean
    fun stop(): Boolean

    // ------------------------------------------------------------------
    // Tier 2: compat-protocol transport. Defaults are FALSE (unsupported)
    // — pure fakes opt in per test; the Android adapter implements them via
    // MediaControllerCompat. The orchestrator gates every call on the
    // session's capability bits FIRST, so an honest "не поддерживает"
    // answer needs no dispatch attempt at all.
    // ------------------------------------------------------------------

    /** Absolute seek within the current track. */
    fun seekTo(positionMs: Long): Boolean = false

    /** Jump to a queue item (id from a previous snapshot's queue). */
    fun skipToQueueItem(queueId: Long): Boolean = false

    /** Heart rating — only meaningful when ratingType == RATING_HEART. */
    fun like(): Boolean = false

    /** mode: one of the MediaCapabilities.REPEAT_MODE_* mirrors. */
    fun setRepeatMode(mode: Int): Boolean = false

    fun setShuffleMode(enabled: Boolean): Boolean = false

    /** Framework path requires API 29+ (see the orchestrator's speed gate). */
    fun setPlaybackSpeed(speed: Float): Boolean = false
}

/**
 * Facade over the Android media stack (MediaSessionManager via the bound
 * notification listener, AudioManager media keys, PackageManager).
 */
interface MediaGateway {
    /** False until the user grants notification-listener access. */
    fun hasNotificationListenerAccess(): Boolean

    /**
     * M2: whether OUR UI is visible right now. Android 10+ silently blocks
     * background activity starts (a foreground service is not an exemption),
     * so launch/deep-link "success" is only believable in the foreground —
     * the orchestrator phrases launch outcomes as attempts otherwise.
     * Default true keeps pure-JVM fakes optimistic unless a test opts out.
     */
    fun isUiVisible(): Boolean = true

    /** Fresh snapshot of every ACTIVE media session on the device. */
    fun activeControllers(): List<MediaControllerHandle>

    /**
     * Fire a global media key at the audio stack. Goes to whichever app
     * currently owns media focus — works even without listener access.
     */
    fun dispatchMediaKey(keyCode: Int)

    /** Deep-link the target app into its search screen for [query]. */
    fun openAppSearch(app: MediaAppInfo, query: String): Boolean

    /**
     * Tier 1 (S4): the pre-session legacy protocol — resolve an activity
     * handling android.media.action.MEDIA_PLAY_FROM_SEARCH and send it the
     * query (+ structured slot extras). False when the player ships no such
     * activity (the common case on modern players).
     */
    fun sendLegacySearch(app: MediaAppInfo, command: SearchCommand): Boolean = false

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
