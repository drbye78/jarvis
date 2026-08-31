package com.jarvis.assistant.media

/**
 * Tier 3 (pulled before Tier 2 on purpose): the MediaBrowser lane.
 *
 * Binding a player's MediaBrowserService is the ONLY cold-start strategy
 * that is simultaneously (a) headless — no activity start, so Android 10+
 * background-activity-launch restrictions do not apply, (b) permission-free —
 * no notification-listener access needed to obtain the session token, and
 * (c) deterministic when the player implements onSearch — a scored
 * search result plays via playFromMediaId(mediaId), retiring heuristic
 * verification for that path entirely.
 *
 * Even a player that REFUSES browsing (onGetRoot → empty root) still accepts
 * the connection and hands us its session token, which is exactly what the
 * S2 cold-start strategy needs. A player that returns null from onGetRoot
 * refuses outright — connect() reports that as null and the cascade moves on.
 *
 * Pure contract (like [MediaGateway]): every decision — search scoring,
 * cascade order, timeouts — lives in JVM-tested code; the Android adapter
 * ([AndroidMediaBrowserGateway]) only binds, relays and converts.
 */

/** An installed app exposing a MediaBrowserService. */
data class BrowserServiceInfo(
    val packageName: String,
    val label: String,
)

/**
 * One browse/search result. [mediaId] is the service's identifier for the
 * item — usable with [BrowserSession.playFromMediaId] while the service
 * process is alive (typically stable across connections, but treat as
 * short-lived: play immediately, do not persist).
 */
data class BrowserMediaItem(
    val mediaId: String,
    val title: String,
    val artist: String? = null,
    /** Can be played via playFromMediaId. */
    val playable: Boolean = true,
    /** Can be browsed (children() on it). */
    val browsable: Boolean = false,
)

/**
 * A connected browser session — one bind. Sessions are cheap but not free:
 * callers MUST [disconnect] when finished (the orchestrator wraps each
 * attempt in try/finally). Every method is best-effort; remote services die.
 */
interface BrowserSession {
    val packageName: String

    /** The service's root media id ("" when unknown). */
    fun root(): String

    /**
     * onSearch() results for [query], or null when the service does not
     * implement search / errored / timed out. Never throws.
     */
    suspend fun search(query: String, timeoutMs: Long): List<BrowserMediaItem>?

    /** Children of [parentId] capped at [max], or null on error/timeout. */
    suspend fun children(parentId: String, timeoutMs: Long, max: Int = 20): List<BrowserMediaItem>?

    /**
     * Live controller handle built from the session token — the BAL-immune,
     * permission-free way to dispatch transport commands. Null when the
     * service refuses to hand out a token.
     */
    fun controller(): MediaControllerHandle?

    /** Deterministic play of a previously discovered item. */
    fun playFromMediaId(mediaId: String): Boolean

    /** Release the bind. Safe to call more than once. */
    fun disconnect()
}

/** Facade over the platform's MediaBrowser service infrastructure. */
interface MediaBrowserGateway {
    /** Installed packages exposing a MediaBrowserService (fast PackageManager query). */
    fun discover(): List<BrowserServiceInfo>

    /**
     * Bind the target package's browser service. Null when the package has
     * no service, refuses the connection (onGetRoot → null), or the bind
     * does not settle within [timeoutMs].
     */
    suspend fun connect(packageName: String, timeoutMs: Long): BrowserSession?
}

/**
 * Scores browse/search results against the voice request — the same
 * normalized token-overlap the playback verification uses, applied to item
 * titles/artists. The best PLAYABLE item at or above the strong threshold
 * wins; a search that returns only weak matches is treated as a miss (the
 * cascade then tries playFromSearch), never as a confident wrong play.
 */
object BrowserResultMatcher {

    fun score(item: BrowserMediaItem, vq: VoiceQuery): Double {
        var weight = 0.0
        var acc = 0.0
        val qTokens = VoiceQueryMatcher.tokens(vq.query)
        if (qTokens.isNotEmpty()) {
            weight += W_TITLE
            acc += W_TITLE * VoiceQueryMatcher.overlap(item.title, qTokens)
        }
        vq.artist?.let { s ->
            val t = VoiceQueryMatcher.tokens(s)
            if (t.isNotEmpty()) {
                weight += W_ARTIST
                acc += W_ARTIST * VoiceQueryMatcher.overlap(item.artist, t)
            }
        }
        if (weight == 0.0) return 0.0
        return acc / weight
    }

    fun bestMatch(items: List<BrowserMediaItem>, vq: VoiceQuery): BrowserMediaItem? =
        items.asSequence()
            .filter { it.playable && it.mediaId.isNotBlank() }
            .map { it to score(it, vq) }
            .filter { (_, s) -> s >= VoiceQueryMatcher.STRONG_THRESHOLD }
            .maxByOrNull { (_, s) -> s }
            ?.first

    private const val W_TITLE = 0.65
    private const val W_ARTIST = 0.35
}
