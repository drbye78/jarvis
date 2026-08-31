package com.jarvis.assistant.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import com.jarvis.assistant.service.JarvisNotificationListener
import timber.log.Timber

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
 *    (launcher-intent query + explicit Yandex Music / Zvuk package names).
 */
class AndroidMediaGateway(
    private val context: Context,
    /**
     * Package of the user's preferred default player (Settings → «Музыка»),
     * or null for "auto" priority. Supplied by the composition root so this
     * adapter stays free of SharedPreferences.
     */
    private val preferredPlayerPackage: () -> String? = { null },
) : MediaGateway {

    private val appContext = context.applicationContext
    private val listenerComponent = ComponentName(appContext, JarvisNotificationListener::class.java)

    /**
     * M2: whether OUR UI is currently visible. Android 10+ silently blocks
     * startActivity from a background app (no BAL exemption), so a "success"
     * return from the launch path is only trustworthy when we are in the
     * foreground; the orchestrator uses this flag to phrase launch outcomes
     * as attempts rather than achievements. Maintained by
     * [com.jarvis.assistant.JarvisApplication] via activity-lifecycle
     * callbacks (process-foreground counting, ProcessLifecycleOwner-style).
     */
    override fun isUiVisible(): Boolean = AppForegroundTracker.isVisible

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
        msm.getActiveSessions(listenerComponent).mapNotNull { fw ->
            // Compat-wrap via the session token (MediaControllerCompat.wrap()
            // no longer exists in androidx.media 1.7): every handle exposes
            // the full compat surface, including the Phase-4 transport.
            runCatching {
                val compatToken = MediaSessionCompat.Token.fromToken(fw.sessionToken)
                AndroidControllerHandle(MediaControllerCompat(appContext, compatToken))
            }.getOrNull()
        }
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
        // M1: SearchLinks delegates the percent-encoding, and production
        // passes Uri::encode — spaces become %20, NOT '+'. URLEncoder would
        // emit '+' (correct for form bodies, wrong in URIs: neither
        // Uri.getQueryParameter nor the https path-segment form ever decodes
        // it back), so multi-word queries reached the player mangled.
        val searchIntents = SearchLinks.searchUris(app.packageName, query, Uri::encode)
            .map { uriIntent(it) }
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

    /**
     * Tier 1 (S4): the pre-session Assistant protocol — resolve an activity
     * declaring android.media.action.MEDIA_PLAY_FROM_SEARCH for the target
     * package and hand it SearchManager.QUERY + the structured slot extras.
     * Most modern players ship no such activity; the resolution failure is
     * the expected common case, not an error. BAL caveat: this is an activity
     * start, so the orchestrator treats the result as attempted-then-verified
     * (it waits for a matching playing session before claiming success).
     */
    override fun sendLegacySearch(app: MediaAppInfo, command: SearchCommand): Boolean {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(app.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for ((key, value) in command.toLegacyIntentExtras()) {
            intent.putExtra(key, value)
        }
        val resolved = runCatching {
            appContext.packageManager.resolveActivity(intent, 0)
        }.getOrNull()
        if (resolved == null) {
            Timber.i("Music: %s ships no MEDIA_PLAY_FROM_SEARCH activity — S4 skipped", app.packageName)
            return false
        }
        Timber.d("Music: sending legacy search intent to %s", resolved.activityInfo?.packageName)
        return startActivitySafely(intent)
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
    val resolver: MusicAppResolver = MusicAppCatalog(::installedLaunchables, preferredPlayerPackage)

    /**
     * Tier 3: the browser lane gateway, built on the same app context.
     * Exposed so the composition root (FunctionRouter) can hand ONE context
     * to both gateways.
     */
    val browserGateway: MediaBrowserGateway = AndroidMediaBrowserGateway(appContext)

    /** Launchable apps as (packageName, label) — the input for [MusicAppCatalog]. */
    private fun installedLaunchables(): List<Pair<String, String>> = runCatching {
        val pm = appContext.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
    }.getOrDefault(emptyList())
}

/**
 * Wraps a [MediaControllerCompat] (compat-wrapped framework controller or a
 * browser-token controller). Remote apps can die between any two calls —
 * every method is best-effort and returns a safe fallback.
 *
 * The compat wrapper is deliberate: framework TransportControls lacks
 * setRepeatMode/setShuffleMode/setPlaybackSpeed (compat-protocol actions
 * only — see the capability lane), and the compat mask in PlaybackState
 * carries those bits. Phase 4's rich transport extends THIS handle.
 */
internal class AndroidControllerHandle(
    private val controller: MediaControllerCompat,
) : MediaControllerHandle {

    override val packageName: String get() = controller.packageName

    /**
     * Tier 0: the ground-truth probe. Framework mask + compat bits (players
     * built on MediaSessionCompat OR the compat bits into the same field),
     * rating type, queue presence — decoded by the pure [MediaCapabilities].
     */
    override fun capabilities(): MediaCapabilities = runCatching {
        val state = controller.playbackState
        MediaCapabilities.fromActionMask(
            mask = state?.actions ?: 0L,
            ratingType = controller.ratingType,
            hasQueue = !controller.queue.isNullOrEmpty(),
        )
    }.getOrDefault(MediaCapabilities.UNKNOWN)

    override fun snapshot(): NowPlaying = runCatching {
        val pb = controller.playbackState
        val md = controller.metadata
        val queue = controller.queue
        val activeId = pb?.activeQueueItemId ?: -1L
        val queueIndex = queue?.indexOfFirst { it.queueId == activeId } ?: -1
        NowPlaying(
            title = md?.getText(MediaMetadataCompat.METADATA_KEY_TITLE)?.toString(),
            artist = md?.getText(MediaMetadataCompat.METADATA_KEY_ARTIST)?.toString()
                ?: md?.getText(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST)?.toString(),
            album = md?.getText(MediaMetadataCompat.METADATA_KEY_ALBUM)?.toString(),
            state = pb?.state ?: NowPlaying.STATE_NONE,
            positionMs = pb?.position ?: 0L,
            durationMs = md?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L,
            queueIndex = queueIndex,
            queueSize = queue?.size ?: 0,
            speed = pb?.playbackSpeed ?: 1.0f,
            repeatMode = controller.repeatMode ?: MediaCapabilities.REPEAT_MODE_NONE,
            shuffleMode = controller.shuffleMode ?: MediaCapabilities.SHUFFLE_MODE_NONE,
        )
    }.getOrDefault(NowPlaying(state = NowPlaying.STATE_NONE))

    private fun transport(block: (MediaControllerCompat.TransportControls) -> Unit): Boolean =
        runCatching {
            block(controller.transportControls)
            true
        }.getOrDefault(false)

    override fun playFromSearch(query: String) =
        transport { it.playFromSearch(query, null) }

    /**
     * Tier 1: assemble the Assistant voice-search extras Bundle — focus
     * entry type + slot extras, keys are the literal MediaStore constants
     * (see [SearchCommand]). Unstructured commands degrade to the plain
     * playFromSearch(query, null) call.
     */
    override fun playFromSearchStructured(command: SearchCommand): Boolean {
        if (command.isUnstructured) {
            return playFromSearch(command.query)
        }
        return transport { controls ->
            val extras = Bundle(command.extras.size + 1).apply {
                command.focus?.let { putString(MediaStore.EXTRA_MEDIA_FOCUS, it) }
                for ((key, value) in command.extras) {
                    putString(key, value)
                }
            }
            controls.playFromSearch(command.query, extras)
        }
    }

    override fun play() = transport { it.play() }
    override fun pause() = transport { it.pause() }
    override fun skipToNext() = transport { it.skipToNext() }
    override fun skipToPrevious() = transport { it.skipToPrevious() }
    override fun stop() = transport { it.stop() }

    // Tier 2: compat-protocol transport (gated upstream on capability bits).

    override fun seekTo(positionMs: Long) =
        transport { it.seekTo(positionMs.coerceAtLeast(0)) }

    override fun skipToQueueItem(queueId: Long) =
        transport { it.skipToQueueItem(queueId) }

    override fun like(): Boolean = transport { controls ->
        // Compat heart rating; the orchestrator gates on ratingType == HEART.
        controls.setRating(RatingCompat.newHeartRating(true))
    }

    override fun setRepeatMode(mode: Int) =
        transport { it.setRepeatMode(mode) }

    override fun setShuffleMode(enabled: Boolean) =
        transport {
            it.setShuffleMode(
                if (enabled) MediaCapabilities.SHUFFLE_MODE_ALL
                else MediaCapabilities.SHUFFLE_MODE_NONE,
            )
        }

    override fun setPlaybackSpeed(speed: Float) =
        transport { it.setPlaybackSpeed(speed) }
}
