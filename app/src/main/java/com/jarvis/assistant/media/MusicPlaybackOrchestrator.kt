package com.jarvis.assistant.media

import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Drives an EXTERNAL player app (Яндекс Музыка by default) through a
 * verified strategy cascade. Pure Kotlin — all Android specifics live in
 * [MediaGateway] implementations, so every branch below is unit-tested.
 *
 * Cascade for "play <query>":
 *  1. ACTIVE_SESSION  — the target app already has a live MediaSession:
 *     TransportControls.playFromSearch(query, extras), then VERIFY playback
 *     really started and MATCHES the request (VoiceQuery scoring).
 *  1b. BROWSER_SEARCH (S0) — the player's MediaBrowserService implements
 *     onSearch: score the results against the request and play the best
 *     hit deterministically via playFromMediaId(mediaId).
 *  1c. BROWSER_COLD  (S2) — bind the MediaBrowserService and dispatch
 *     through the session token: no activity start (immune to Android 10+
 *     background-activity-launch restrictions) and NO permission needed.
 *     Works even when the player refuses browsing (empty root still hands
 *     out the token).
 *  2. COLD_START      — no session (app not running): cold-start the app,
 *     poll for its session to appear, then playFromSearch + verify.
 *  2b. LEGACY_INTENT (S4) — the pre-session android.media.action
 *     .MEDIA_PLAY_FROM_SEARCH activity intent, if the player ships one.
 *  3. SEARCH_SCREEN   — deep-link the app to its search screen for the
 *     query. Hands-assisted: the user taps the track. Reported honestly as
 *     SEARCH_OPENED, never as success.
 *
 * The browser lane runs BEFORE any activity-starting strategy: binding is
 * the Assistant-grade headless cold start (plan §3.1 rationale), and it
 * also works when notification-listener access is missing — the token path
 * needs no permission at all.
 *
 * Playback commands (pause/next/…) first target the resolved app's live
 * session, then any active session, then fall back to global media keys —
 * which work without notification-listener access but hit whichever app
 * owns media focus.
 *
 * M2 honesty rule: on Android 10+ a background app cannot reliably start
 * activities (no BAL exemption for a foreground service), and startActivity
 * fails SILENTLY. So whenever our UI is not visible ([MediaGateway.isUiVisible]),
 * deep-link and launch outcomes are phrased as attempts with a contingency
 * instruction, never as confirmed actions.
 */
class MusicPlaybackOrchestrator(
    private val gateway: MediaGateway,
    private val resolver: MusicAppResolver,
    private val browser: MediaBrowserGateway? = null,
    private val budgets: Budgets = Budgets(),
    /** For the API-29 setPlaybackSpeed guard (plan risk R7); production
     *  passes Build.VERSION.SDK_INT, JVM tests pin it explicitly. */
    private val deviceApiLevel: Int = 30,
    /** Phase 5 (M5): spoken cascade progress («Секунду…»); null = silent. */
    private val feedback: com.jarvis.assistant.audio.SpeechFeedback? = null,
) {

    private val transportControl = TransportControl(gateway, resolver, deviceApiLevel)
    private val libraryBrowser = LibraryBrowser(browser, resolver, budgets)

    /** Latency budgets — kept in one place so tests can shrink them. */
    data class Budgets(
        val verifyPollMs: Long = 300,
        val verifyTotalMs: Long = 4_500,
        val coldStartPollMs: Long = 400,
        val coldStartTotalMs: Long = 8_000,
        /** S4: how long the legacy intent gets to produce a playing session. */
        val legacyWaitTotalMs: Long = 6_000,
        /** Tier 3: browser bind and per-op (search/children) budgets. */
        val browserConnectTimeoutMs: Long = 3_000,
        val browserSearchTimeoutMs: Long = 3_000,
        val maxQueryLength: Int = 200,
        /** Tier 3: how many library items listPlaylists/searchLibrary return. */
        val maxLibraryItems: Int = 10,
    )

    /** What happened, in a form the LLM can relay in Russian. */
    enum class Status {
        /** Requested track verified as actually playing. */
        PLAYING,

        /** Command dispatched; could not verify, but nothing contradicts it. */
        DISPATCHED,

        /** Search screen opened — user must tap the track. */
        SEARCH_OPENED,

        /** App launched but playback could not be started. */
        APP_OPENED,

        /** Unrecoverable (no app installed, no access, no query). */
        ERROR,
    }

    data class Outcome(
        val status: Status,
        val app: MediaAppInfo?,
        val strategy: String? = null,
        val nowPlaying: NowPlaying? = null,
        /** Tier 3: library items for listPlaylists / searchLibrary results. */
        val items: List<BrowserMediaItem>? = null,
        /** Human-readable Russian detail for the LLM to relay. */
        val detail: String,
        val isError: Boolean = false,
    )

    // ------------------------------------------------------------------
    // Play a search query
    // ------------------------------------------------------------------

    /** Back-compat overload: flat query only. */
    suspend fun playSearchQuery(rawQuery: String, appHint: String?): Outcome =
        playSearchQuery(rawQuery, null, null, null, null, appHint)

    /**
     * Tier 1: structured play request. Slots are optional; the flat query
     * alone is a valid request. Returns null-rejection via the outcome.
     */
    suspend fun playSearchQuery(
        rawQuery: String,
        artist: String?,
        album: String?,
        playlist: String?,
        genre: String?,
        appHint: String?,
    ): Outcome {
        val vq = VoiceQuery.clean(rawQuery, artist, album, playlist, genre, budgets.maxQueryLength)
            ?: return Outcome(Status.ERROR, null, detail = "Не понял, что включить — назови трек, исполнителя, альбом или плейлист.", isError = true)
        val command = vq.toSearchCommand()
        val flat = vq.flatQuery()

        val app = resolver.resolve(appHint)
            ?: return Outcome(
                Status.ERROR, null,
                detail = "Не нашёл музыкальное приложение на планшете. Установи Яндекс Музыку или другой плеер.",
                isError = true,
            )

        // Tier 0: one-shot capability dump per attempt — the RUNBOOK's
        // ground-truth probe (`adb logcat -s MusicDiag`).
        dumpSessionDiagnostics()

        // Strategy 1: app already exposes a live session. The `before`
        // baseline MUST be captured BEFORE dispatching playFromSearch — a
        // fast player may switch tracks before the first verification poll,
        // and comparing two post-dispatch snapshots would never see the
        // title change. Tier 0: if the session's action mask says it does
        // NOT honor playFromSearch, skip the dispatch entirely — saves the
        // whole verify budget on players that never implement it.
        // Tier 1: the dispatch is STRUCTURED (focus + slot extras) when the
        // request carried slots.
        val live = controllerFor(app.packageName)
        // Phase 5 (M5): with a live session the fast path answers in ~1 s;
        // anything else faces a cold start (bind/launch/verify) — say so
        // BEFORE the silence, not after it.
        feedback?.onCascadeStarted(predictedLong = live == null)
        if (live != null) {
            val caps = live.capabilities()
            if (caps.supports(TransportAction.PLAY_FROM_SEARCH)) {
                Timber.d("Music: active session for %s, playFromSearch(%s)", app.packageName, command.focus ?: "flat")
                val before = live.snapshot()
                live.playFromSearchStructured(command)
                val verified = awaitStartVerified(live, vq, before)
                if (verified != null) {
                    return playing(app, "active_session", verified)
                }
            } else {
                Timber.tag("MusicDiag")
                    .i("music-diag: %s mask lacks playFromSearch — skipping live-session dispatch", app.packageName)
            }
        }

        // ------------------------------------------------------------------
        // Browser lane (Tier 3): runs when the live session is absent,
        // unhelpful, or ignored the dispatch. Binding the player's
        // MediaBrowserService needs NO permission and NO activity start —
        // it works without notification-listener access and is immune to
        // Android 10+ BAL restrictions, which is why it runs BEFORE every
        // activity-starting strategy below.
        // ------------------------------------------------------------------
        runBrowserLane(app, command, vq)?.let { return it }

        if (!gateway.hasNotificationListenerAccess()) {
            // The browser lane could not start playback either — deep-link
            // search still works without listener access, but say why
            // hands-free failed. M2: phrase the launch as an attempt unless
            // our UI is visible (BAL).
            val opened = gateway.openAppSearch(app, flat)
            return if (opened) {
                Outcome(
                    Status.SEARCH_OPENED, app, strategy = "deep_link_no_access",
                    detail = "Голосовое управление музыкой недоступно: нет доступа к уведомлениям. " +
                        "Открой настройки → Доступ к уведомлениям → разреши Джарвису. " +
                        launchAttemptPhrasing(flat, app),
                )
            } else {
                Outcome(
                    Status.ERROR, app,
                    detail = "Нет доступа к уведомлениям — не могу управлять плеером. " +
                        "Открой настройки → Специальный доступ → Доступ к уведомлениям → включи Джарвиса.",
                    isError = true,
                )
            }
        }

        // Strategy 2: cold start, wait for a session, retry the command.
        var launched = false
        feedback?.onLaunchingPlayer(app.label) // M5: heard while the app opens
        if (gateway.launchApp(app)) {
            launched = true
            val fresh = awaitControllerFor(app.packageName, budgets.coldStartTotalMs, budgets.coldStartPollMs)
            if (fresh != null) {
                val caps = fresh.capabilities()
                if (caps.supports(TransportAction.PLAY_FROM_SEARCH)) {
                    val before = fresh.snapshot()
                    fresh.playFromSearchStructured(command)
                    val verified = awaitStartVerified(fresh, vq, before)
                    if (verified != null) {
                        return playing(app, "cold_start", verified)
                    }
                } else {
                    Timber.tag("MusicDiag")
                        .i("music-diag: %s cold-start session lacks playFromSearch — skipping dispatch", app.packageName)
                }
            }
        }

        // Strategy 4: legacy MEDIA_PLAY_FROM_SEARCH activity intent — the
        // pre-session Assistant protocol. Some players implement the
        // intent-filter but not onPlayFromSearch; BAL caveat applies (it is
        // an activity start), so the outcome is verified, not assumed.
        if (gateway.sendLegacySearch(app, command)) {
            val legacy = awaitVerifiedFromNothing(app.packageName, vq)
            if (legacy != null) {
                return playing(app, "legacy_intent", legacy)
            }
        }

        // Strategy 5: hands-assisted search screen. M2: BAL-honest phrasing.
        val opened = gateway.openAppSearch(app, flat)
        return if (opened) {
            Outcome(
                Status.SEARCH_OPENED, app, strategy = "deep_link",
                detail = "Плеер не принял голосовую команду — " + launchAttemptPhrasing(flat, app),
            )
        } else {
            // Last resort: make sure the player is at least on screen. No
            // second launch if the cold-start branch already opened it.
            if (!launched) gateway.launchApp(app)
            val launchedDetail = if (gateway.isUiVisible()) {
                "Не смог запустить воспроизведение голосом — открыл ${app.label}, запусти трек вручную."
            } else {
                "Не смог запустить воспроизведение голосом — пробую открыть ${app.label} в фоне; " +
                    "если экран не появился, открой плеер вручную."
            }
            Outcome(
                Status.APP_OPENED, app, strategy = "launch_only",
                detail = launchedDetail,
            )
        }
    }

    // ------------------------------------------------------------------
    // Browser lane (Tier 3)
    // ------------------------------------------------------------------

    /**
     * S0 (browser search → deterministic playFromMediaId) then S2 (session
     * token → playFromSearch). Returns a PLAYING outcome when a strategy
     * verifies, or null to continue the cascade. The session — when one is
     * opened — is always disconnected (try/finally): one bind per attempt,
     * no leaks across attempts.
     */
    private suspend fun runBrowserLane(
        app: MediaAppInfo,
        command: SearchCommand,
        vq: VoiceQuery,
    ): Outcome? {
        val browserGateway = browser ?: return null
        val services = browserGateway.discover()
        MediaDiagnostics.browserTable(services).forEach { Timber.tag("MusicDiag").i("%s", it) }
        if (services.none { it.packageName == app.packageName }) return null

        val session = browserGateway.connect(app.packageName, budgets.browserConnectTimeoutMs)
            ?: return null // not installed / refused / timed out
        try {
            // S0: search results scored against the request; a strong match
            // plays deterministically by mediaId.
            val results = session.search(command.query, budgets.browserSearchTimeoutMs)
            val best = results?.let { BrowserResultMatcher.bestMatch(it, vq) }
            if (best != null) {
                Timber.d(
                    "Music: browser search hit «%s» (mediaId=%s) for «%s»",
                    best.title, best.mediaId, command.query,
                )
                val handle = session.controller()
                if (handle != null) {
                    val before = runCatching { handle.snapshot() }.getOrDefault(NowPlaying())
                    if (session.playFromMediaId(best.mediaId)) {
                        // Verify against the ITEM we chose (title/artist are
                        // known) — a player that ignores playFromMediaId while
                        // the old track keeps playing must not pass.
                        val itemVq = VoiceQuery.clean(best.title, artist = best.artist) ?: vq
                        val verified = awaitVerifiedStart(handle, itemVq, before)
                        if (verified != null) {
                            return playing(app, "browser_media_id", verified)
                        }
                    }
                }
            }

            // S2: cold start through the session token — the BAL-immune,
            // permission-free dispatch. Even an empty (unbrowsable) root
            // hands out the token, so this works for players that refuse
            // browsing but still implement onPlayFromSearch.
            val handle = session.controller()
            if (handle != null) {
                if (handle.capabilities().supports(TransportAction.PLAY_FROM_SEARCH)) {
                    val before = runCatching { handle.snapshot() }.getOrDefault(NowPlaying())
                    handle.playFromSearchStructured(command)
                    val verified = awaitStartVerified(handle, vq, before)
                    if (verified != null) {
                        return playing(app, "browser_cold_start", verified)
                    }
                } else {
                    Timber.tag("MusicDiag").i(
                        "music-diag: browser session of %s lacks playFromSearch — skipping token dispatch",
                        app.packageName,
                    )
                }
            }
        } finally {
            session.disconnect()
        }
        return null
    }

    /**
     * Tier 3: play a specific library item by the mediaId previously
     * returned by [listPlaylists]/[searchLibrary]. When [titleHint] is
     * given, playback is score-verified like any other play; without it the
     * best honest evidence is a PLAYING state (the target was exact by
     * construction — we named the mediaId).
     */
    suspend fun playLibraryItem(mediaId: String, titleHint: String?, appHint: String?): Outcome {
        val app = resolver.resolve(appHint)
            ?: return Outcome(
                Status.ERROR, null,
                detail = "Не нашёл музыкальное приложение на планшете.",
                isError = true,
            )
        if (mediaId.isBlank()) {
            return Outcome(Status.ERROR, app, detail = "Пустой идентификатор трека — скажи название, я найду его поиском.", isError = true)
        }
        val session = libraryBrowser.connectBrowser(app)
            ?: return libraryBrowser.browserUnavailable(app)
        try {
            val handle = session.controller()
                ?: return libraryBrowser.browserUnavailable(app)
            val before = runCatching { handle.snapshot() }.getOrDefault(NowPlaying())
            if (!session.playFromMediaId(mediaId)) {
                return Outcome(
                    Status.ERROR, app, strategy = "browser_media_id",
                    detail = "Плеер не принял команду для этого трека — возможно, библиотека обновилась. " +
                        "Скажи название, я включу его поиском.",
                    isError = true,
                )
            }
            val vq = titleHint?.let { VoiceQuery.clean(it) }
            val verified = if (vq != null) {
                awaitVerifiedStart(handle, vq, before)
            } else {
                awaitPlaying(handle)
            }
            return if (verified != null) {
                playing(app, "browser_media_id", verified)
            } else {
                Outcome(
                    Status.APP_OPENED, app, strategy = "browser_media_id",
                    detail = "Отправил команду плееру, но подтверждения не дождался — проверь экран.",
                )
            }
        } finally {
            session.disconnect()
        }
    }

    // ------------------------------------------------------------------
    // Delegated commands — thin wrappers preserving the public API
    // ------------------------------------------------------------------

    /** Back-compat overload: basic transport, no parameters. */
    suspend fun control(action: Action, appHint: String?): Outcome =
        transportControl.control(action, appHint)

    /**
     * Tier 2: rich transport with per-action capability gating. A player
     * whose action mask (or rating type) says it cannot honor the command
     * gets an honest Russian refusal — never a silent no-op, never a fake
     * success. Selection (M4) and the media-key fallback are unchanged for
     * the basic six; the rich actions require a live session (a media key
     * cannot seek/like/repeat).
     */
    suspend fun control(spec: ControlSpec, appHint: String?): Outcome =
        transportControl.control(spec, appHint)

    suspend fun nowPlaying(appHint: String?): Outcome =
        transportControl.nowPlaying(appHint)

    /** Tier 3: top-level library sections / playlists (root children). */
    suspend fun listPlaylists(appHint: String?): Outcome =
        libraryBrowser.listPlaylists(appHint)

    /** Tier 3: search the player's own library (onSearch results). */
    suspend fun searchLibrary(rawQuery: String, appHint: String?): Outcome =
        libraryBrowser.searchLibrary(rawQuery, appHint)

    // ------------------------------------------------------------------
    // Transport types — kept here for public API compatibility
    // ------------------------------------------------------------------

    enum class Action {
        PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, STOP,
        SEEK, RESTART, LIKE, REPEAT, SHUFFLE, SPEED,
    }

    /** Compat repeat modes the LLM can name (wire values in [MediaCapabilities]). */
    enum class RepeatMode(val wire: Int) {
        OFF(MediaCapabilities.REPEAT_MODE_NONE),
        ONE(MediaCapabilities.REPEAT_MODE_ONE),
        ALL(MediaCapabilities.REPEAT_MODE_ALL),
    }

    /**
     * Tier 2: one transport command with its parameters. All optional
     * fields apply only to specific actions; the tool layer maps the LLM's
     * arguments into this shape.
     */
    data class ControlSpec(
        val action: Action,
        /** SEEK: absolute target; omitted → current + [deltaMs]. */
        val positionMs: Long? = null,
        /** SEEK: signed offset from the current position. */
        val deltaMs: Long? = null,
        val repeatMode: RepeatMode? = null,
        val shuffle: Boolean? = null,
        val speed: Float? = null,
    )

    /**
     * Pure gating table (plan risk R7): which capability bit (if any) an
     * action requires, and the extra conditions no bitmask can express.
     * Null bit = no session-bit requirement (basic transport).
     */
    object TransportPolicy {
        fun requiredAction(action: Action): TransportAction? = when (action) {
            Action.PLAY, Action.TOGGLE -> TransportAction.PLAY
            Action.PAUSE -> TransportAction.PAUSE
            Action.NEXT -> TransportAction.SKIP_TO_NEXT
            Action.PREVIOUS -> TransportAction.SKIP_TO_PREVIOUS
            Action.STOP -> TransportAction.STOP
            Action.SEEK, Action.RESTART -> TransportAction.SEEK_TO
            Action.LIKE -> TransportAction.SET_RATING
            Action.REPEAT -> TransportAction.SET_REPEAT_MODE
            Action.SHUFFLE -> TransportAction.SET_SHUFFLE_MODE
            Action.SPEED -> TransportAction.SET_PLAYBACK_SPEED
        }

        /** Framework TransportControls.setPlaybackSpeed exists since API 29
         *  (minSdk is 24) — below that only compat sessions honor it, which
         *  we do not promise. */
        fun speedAllowed(apiLevel: Int): Boolean = apiLevel >= 29

        /** Heart rating is the only rating style we can speak. */
        fun likeAllowed(caps: MediaCapabilities): Boolean =
            caps.ratingType == MediaCapabilities.RATING_HEART

        /** Actions the global media-key fallback can express at all. */
        fun mediaKeyEligible(action: Action): Boolean = action in setOf(
            Action.PLAY, Action.PAUSE, Action.TOGGLE,
            Action.NEXT, Action.PREVIOUS, Action.STOP,
        )
    }

    // ------------------------------------------------------------------
    // M2: BAL honesty — background launch outcomes are attempts, not facts
    // ------------------------------------------------------------------

    /**
     * M2: how to describe a deep-link launch, depending on whether we are
     * allowed to start activities at all. In the foreground the start either
     * happened or threw; in the background Android may have silently dropped
     * it — so we say we TRIED and tell the user what to do if nothing opened.
     */
    private fun launchAttemptPhrasing(query: String, app: MediaAppInfo): String =
        if (gateway.isUiVisible()) {
            "Открыл поиск «$query» в ${app.label}. Нажми на трек."
        } else {
            "Пытаюсь открыть поиск «$query» в ${app.label}; если экран не появился — " +
                "планшет блокирует запуск из фона, открой плеер вручную."
        }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun controllerFor(pkg: String): MediaControllerHandle? =
        gateway.activeControllers().firstOrNull { it.packageName == pkg }

    /** Tier 0: decode and log every live session's capability mask. */
    private fun dumpSessionDiagnostics() {
        val rows = runCatching {
            gateway.activeControllers().map { h ->
                MediaDiagnostics.SessionRow(
                    packageName = h.packageName,
                    capabilities = h.capabilities(),
                    nowPlaying = runCatching { h.snapshot() }.getOrNull(),
                )
            }
        }.getOrDefault(emptyList())
        MediaDiagnostics.sessionTable(rows).forEach { Timber.tag("MusicDiag").i("%s", it) }
    }

    /** Poll [budgets.coldStartTotalMs] for the app's session to appear. */
    private suspend fun awaitControllerFor(
        pkg: String,
        totalMs: Long,
        pollMs: Long,
    ): MediaControllerHandle? {
        val deadline = totalMs / pollMs
        repeat(deadline.coerceAtLeast(1).toInt()) {
            controllerFor(pkg)?.let { return it }
            delay(pollMs)
        }
        return controllerFor(pkg)
    }

    /**
     * Verification router: scoreable requests (title/artist/album/genre)
     * use the strong score rule; a playlist-only request never appears in
     * track metadata (score is 0 by construction) and verifies by STATE
     * evidence instead — otherwise every playlist command "failed" while
     * the playlist was audibly playing, re-dispatching the cascade and
     * opening search screens (the playLibraryItem no-title-hint path is the
     * precedent for state-evidence verification).
     */
    private suspend fun awaitStartVerified(
        handle: MediaControllerHandle,
        vq: VoiceQuery,
        before: NowPlaying,
    ): NowPlaying? =
        if (VoiceQueryMatcher.hasScoreableExpectation(vq)) {
            awaitVerifiedStart(handle, vq, before)
        } else {
            awaitCommandEffect(handle, before)
        }

    /**
     * Weakest honest verification: ANY playing state within the budget.
     * Used only when the play target was exact by construction (a named
     * mediaId) and no title hint exists to score against.
     */
    private suspend fun awaitPlaying(handle: MediaControllerHandle, totalMs: Long? = null): NowPlaying? {
        val budget = totalMs ?: budgets.verifyTotalMs
        val polls = (budget / budgets.verifyPollMs).coerceAtLeast(1).toInt()
        repeat(polls) {
            val now = runCatching { handle.snapshot() }.getOrNull() ?: return null
            if (now.isPlaying) return now
            delay(budgets.verifyPollMs)
        }
        return null
    }

    /**
     * Verification v2 (audit M3, full fix): wait until what is playing
     * MATCHES THE REQUEST — score(now-playing vs the requested slots) at or
     * above [VoiceQueryMatcher.STRONG_THRESHOLD], or a position reset while
     * at least partially matching. The pre-Tier-1 heuristics (title changed,
     * not-playing → playing, "position < 10 s") are gone: a player that
     * ignores our command while the OLD track happens to be early never
     * produces a confident lie any more.
     */
    private suspend fun awaitVerifiedStart(
        handle: MediaControllerHandle,
        vq: VoiceQuery,
        before: NowPlaying,
    ): NowPlaying? {
        val polls = (budgets.verifyTotalMs / budgets.verifyPollMs).coerceAtLeast(1).toInt()
        repeat(polls) {
            delay(budgets.verifyPollMs)
            val now = runCatching { handle.snapshot() }.getOrNull() ?: return null
            if (VoiceQueryMatcher.isVerified(now, vq, before)) return now
        }
        Timber.w(
            "Music: playFromSearch('%s') not verified against the request — continuing cascade",
            vq.flatQuery(),
        )
        return null
    }

    /**
     * State-evidence verification for requests that cannot score (playlist
     * names never appear in track metadata): the player was silent before
     * and now plays, the position reset, or the track switched — any of
     * these means the dispatch DID something to the player. Same weak-evidence
     * trade-off the position-reset rule of [VoiceQueryMatcher.isVerified]
     * already accepts.
     */
    private suspend fun awaitCommandEffect(
        handle: MediaControllerHandle,
        before: NowPlaying,
    ): NowPlaying? {
        val polls = (budgets.verifyTotalMs / budgets.verifyPollMs).coerceAtLeast(1).toInt()
        repeat(polls) {
            delay(budgets.verifyPollMs)
            val now = runCatching { handle.snapshot() }.getOrNull() ?: return null
            if (now.isPlaying) {
                val started = !before.isPlaying
                val positionReset = now.positionMs < before.positionMs
                val trackSwitched = before.title != now.title || before.artist != now.artist
                if (started || positionReset || trackSwitched) return now
            }
        }
        return null
    }

    /**
     * S4 verification: no `before` baseline exists (the legacy intent may
     * have created the session), so the ONLY acceptable evidence is a
     * strong score against the request.
     */
    private suspend fun awaitVerifiedFromNothing(
        pkg: String,
        vq: VoiceQuery,
    ): NowPlaying? {
        val handle = awaitControllerFor(pkg, budgets.legacyWaitTotalMs, budgets.coldStartPollMs)
            ?: return null
        if (!VoiceQueryMatcher.hasScoreableExpectation(vq)) {
            // Playlist-only: score is impossible — a playing session that
            // appeared after the legacy intent is the honest evidence.
            return awaitPlaying(handle)
        }
        val polls = (budgets.verifyTotalMs / budgets.verifyPollMs).coerceAtLeast(1).toInt()
        repeat(polls) {
            val now = runCatching { handle.snapshot() }.getOrNull() ?: return null
            if (now.isPlaying && VoiceQueryMatcher.score(now, vq) >= VoiceQueryMatcher.STRONG_THRESHOLD) {
                return now
            }
            delay(budgets.verifyPollMs)
        }
        return null
    }

    private fun playing(app: MediaAppInfo, strategy: String, np: NowPlaying): Outcome {
        val what = listOfNotNull(np.title, np.artist).joinToString(" — ").ifBlank { "запрошенный трек" }
        return Outcome(
            Status.PLAYING, app, strategy = strategy, nowPlaying = np,
            detail = "Включил: $what (${app.label}).",
        )
    }
}
