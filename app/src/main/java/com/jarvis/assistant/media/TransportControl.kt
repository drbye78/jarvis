package com.jarvis.assistant.media

import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Transport commands for an external player app (play, pause, next, seek,
 * etc.) with capability-gated dispatch and media-key fallback.
 *
 * Selection order: the NAMED app's session → any PLAYING session → the app
 * the assistant most recently targeted → the most recent session → media key.
 * A named app that is installed but has no live session is bound through its
 * MediaBrowserService and driven via the session token before the honest
 * «named_app_miss» answer — a launched-but-idle player publishes no ACTIVE
 * PlaybackState and is otherwise invisible to the active-session snapshot.
 *
 * Rich actions (seek/like/repeat/shuffle/speed) require a live session — a
 * media key cannot express them.
 *
 * Extracted from [MusicPlaybackOrchestrator].
 */
class TransportControl(
    private val gateway: MediaGateway,
    private val resolver: MusicAppResolver,
    /** For the API-29 setPlaybackSpeed guard; production
     *  passes Build.VERSION.SDK_INT, JVM tests pin it explicitly. */
    private val deviceApiLevel: Int = 30,
    /**
     * Tier 3 browser-token fallback. A launched-but-idle player has no
     * ACTIVE session, so the fresh active-session snapshot cannot see it;
     * binding its MediaBrowserService still yields the session token even
     * when the root is empty (only a null root refuses). Null disables the
     * fallback (JVM tests that predate it, devices without a browser lane).
     */
    private val browser: MediaBrowserGateway? = null,
    /** How long the browser-token fallback may take to bind. */
    private val browserConnectTimeoutMs: Long = 3_000,
    /** Service-lifetime memory of the app the assistant last targeted. */
    private val recentTarget: RecentMusicTarget = RecentMusicTarget(),
) {

    /** Back-compat overload: basic transport, no parameters. */
    suspend fun control(
        action: MusicPlaybackOrchestrator.Action,
        appHint: String?,
    ): MusicPlaybackOrchestrator.Outcome =
        control(MusicPlaybackOrchestrator.ControlSpec(action), appHint)

    /**
     * Tier 2: rich transport with per-action capability gating. A player
     * whose action mask (or rating type) says it cannot honor the command
     * gets an honest Russian refusal — never a silent no-op, never a fake
     * success. Selection and the media-key fallback are unchanged for
     * the basic six; the rich actions require a live session (a media key
     * cannot seek/like/repeat).
     */
    suspend fun control(
        spec: MusicPlaybackOrchestrator.ControlSpec,
        appHint: String?,
    ): MusicPlaybackOrchestrator.Outcome {
        val action = spec.action
        val target = if (appHint != null) resolver.resolve(appHint) else null

        // A NAMED app that cannot even be RESOLVED (not
        // installed / unknown label) is a miss too — silently commanding
        // whichever player happens to be playing would act on the wrong app.
        if (appHint != null && target == null) {
            return MusicPlaybackOrchestrator.Outcome(
                MusicPlaybackOrchestrator.Status.ERROR,
                null,
                strategy = "named_app_unresolved",
                detail = "Не нашёл плеер «$appHint» на планшете — установи его или назови другой.",
                isError = true,
            )
        }
        // The explicitly named app is the strongest "recently targeted"
        // signal: it outranks a stale session on the NEXT hintless command.
        target?.let { recentTarget.remember(it.packageName) }

        val controllers = gateway.activeControllers()
        val resolved = resolveController(controllers, target, appHint)
        val controller = resolved.handle
        val namedApp = target ?: controller?.let { MediaAppInfo(it.packageName, it.packageName) }
        return try {
            if (controller != null) {
                dispatchToController(controller, spec, namedApp)
            } else if (target != null) {
                // The named app is installed but nothing is playing in it and
                // its browser service could not be bound — do NOT fall
                // through to some other player's stale session.
                MusicPlaybackOrchestrator.Outcome(
                    MusicPlaybackOrchestrator.Status.ERROR,
                    target,
                    strategy = "named_app_miss",
                    detail = "В ${target.label} сейчас ничего не играет. Скажи, какой трек включить, " +
                        "или запусти плеер вручную.",
                    isError = true,
                )
            } else {
                mediaKeyFallback(action, target, controllers)
            }
        } finally {
            // One bind per attempt: release the browser edge even on an
            // early refusal or a cancelled turn.
            resolved.session?.disconnect()
        }
    }

    /**
     * Resolve a controller for the command. Order: the named app's live
     * session → a PLAYING session → the recently targeted app's live
     * session → its browser token → the most recent session (no hint only).
     * A named target never falls through to a stranger.
     */
    private suspend fun resolveController(
        controllers: List<MediaControllerHandle>,
        target: MediaAppInfo?,
        appHint: String?,
    ): ResolvedController {
        if (target != null) {
            controllers.firstOrNull { it.packageName == target.packageName }
                ?.let { return ResolvedController(it, null) }
        } else {
            controllers.firstOrNull { it.snapshot().isPlaying }
                ?.let { return ResolvedController(it, null) }
            val recent = recentTarget.packageName
            if (recent != null) {
                controllers.firstOrNull { it.packageName == recent }
                    ?.let { return ResolvedController(it, null) }
            }
        }
        return bindOrFallback(controllers, target, appHint)
    }

    /**
     * The browser-token fallback: bind the target's (or the recently
     * targeted app's) MediaBrowserService and drive the returned token
     * controller — the only way to reach a launched-but-idle player. If the
     * bind is refused, a named target answers «named_app_miss»; a hintless
     * command falls back to the most recent session (the pre-existing
     * behaviour), never a random bind.
     */
    private suspend fun bindOrFallback(
        controllers: List<MediaControllerHandle>,
        target: MediaAppInfo?,
        appHint: String?,
    ): ResolvedController {
        val fallbackPackage = target?.packageName
            ?: if (appHint == null) recentTarget.packageName else null
        if (fallbackPackage != null) {
            val session = bindBrowser(fallbackPackage)
            val handle = session?.controller()
            if (handle != null) return ResolvedController(handle, session)
            session?.disconnect()
        }
        return ResolvedController(if (target == null) controllers.firstOrNull() else null, null)
    }

    /** Best-effort bind; a cancelled turn must never be swallowed. */
    private suspend fun bindBrowser(packageName: String): BrowserSession? {
        val gateway = browser ?: return null
        return try {
            gateway.connect(packageName, browserConnectTimeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Transport: browser fallback failed for %s", packageName)
            null
        }
    }

    /**
     * Capability-gated dispatch for a resolved controller. The API gate and
     * the rating gate live BEFORE any dispatch; an absent capability bit is
     * low confidence (dispatch anyway, phrase honestly), never a refusal.
     */
    private fun dispatchToController(
        controller: MediaControllerHandle,
        spec: MusicPlaybackOrchestrator.ControlSpec,
        namedApp: MediaAppInfo?,
    ): MusicPlaybackOrchestrator.Outcome {
        val action = spec.action
        val caps = controller.capabilities()

        // The API gate lives BEFORE any dispatch — on API < 29 the
        // framework transport has no setPlaybackSpeed at all.
        if (action == MusicPlaybackOrchestrator.Action.SPEED &&
            !MusicPlaybackOrchestrator.TransportPolicy.speedAllowed(deviceApiLevel)
        ) {
            return MusicPlaybackOrchestrator.Outcome(
                MusicPlaybackOrchestrator.Status.ERROR,
                namedApp,
                strategy = "api_guard",
                detail = "Смену скорости этот планшет не поддерживает (нужен Android 10+).",
                isError = true,
            )
        }
        val required = MusicPlaybackOrchestrator.TransportPolicy.requiredActions(action)
        // M-3: an ABSENT bit is low confidence, not a refusal. Players
        // under-report their action masks (compat-only sessions in
        // particular), and refusing outright lost the whole feature for
        // them. Dispatch anyway — the Boolean dispatch result IS the
        // verification — and phrase the uncertainty honestly instead of
        // claiming support the session never advertised.
        val lowConfidence = required.isNotEmpty() && caps.known &&
            required.none { caps.supports(it) }
        if (action == MusicPlaybackOrchestrator.Action.LIKE &&
            !MusicPlaybackOrchestrator.TransportPolicy.likeAllowed(caps)
        ) {
            return unsupported(namedApp, "лайки (у плеера другой тип оценки)")
        }
        // Honest refusal for a no-op seek: without positionMs or deltaMs the
        // computed target equals the current position, and a no-op reported
        // as "Команда отправлена" is a fake success (the honesty rule).
        if (action == MusicPlaybackOrchestrator.Action.SEEK &&
            spec.positionMs == null && spec.deltaMs == null
        ) {
            return MusicPlaybackOrchestrator.Outcome(
                MusicPlaybackOrchestrator.Status.ERROR,
                namedApp,
                strategy = "missing_seek_target",
                detail = "Не понял, куда перематывать — скажи «промотай на минуту» " +
                    "или «на вторую минуту».",
                isError = true,
            )
        }

        val dispatched = dispatchAction(controller, spec)
        return when {
            !dispatched -> MusicPlaybackOrchestrator.Outcome(
                MusicPlaybackOrchestrator.Status.ERROR,
                namedApp,
                strategy = "dispatch_failed",
                detail = "Плеер не принял команду (возможно, перезапустился) — попробуй ещё раз.",
                isError = true,
            )
            lowConfidence -> MusicPlaybackOrchestrator.Outcome(
                MusicPlaybackOrchestrator.Status.DISPATCHED,
                namedApp,
                strategy = "session",
                detail = "Команда отправлена плееру (${controller.packageName}), но подтверждения нет: " +
                    "плеер не сообщал о поддержке этой команды.",
            )
            else -> MusicPlaybackOrchestrator.Outcome(
                MusicPlaybackOrchestrator.Status.DISPATCHED,
                namedApp,
                strategy = "session",
                detail = "Команда отправлена плееру (${controller.packageName}).",
            )
        }
    }

    /** One transport action, mapped onto the controller API. */
    private fun dispatchAction(
        controller: MediaControllerHandle,
        spec: MusicPlaybackOrchestrator.ControlSpec,
    ): Boolean = when (spec.action) {
        MusicPlaybackOrchestrator.Action.PLAY -> playOrResume(controller)
        MusicPlaybackOrchestrator.Action.PAUSE -> controller.pause()
        MusicPlaybackOrchestrator.Action.TOGGLE -> {
            val playing = controller.snapshot().isPlaying
            if (playing) controller.pause() else controller.play()
        }
        MusicPlaybackOrchestrator.Action.NEXT -> controller.skipToNext()
        MusicPlaybackOrchestrator.Action.PREVIOUS -> controller.skipToPrevious()
        MusicPlaybackOrchestrator.Action.STOP -> controller.stop()
        MusicPlaybackOrchestrator.Action.SEEK -> seek(controller, spec)
        MusicPlaybackOrchestrator.Action.RESTART -> controller.seekTo(0)
        MusicPlaybackOrchestrator.Action.LIKE -> controller.like()
        MusicPlaybackOrchestrator.Action.REPEAT -> controller.setRepeatMode(
            spec.repeatMode?.wire ?: MediaCapabilities.REPEAT_MODE_ALL,
        )
        MusicPlaybackOrchestrator.Action.SHUFFLE -> controller.setShuffleMode(spec.shuffle ?: true)
        MusicPlaybackOrchestrator.Action.SPEED -> controller.setPlaybackSpeed(
            (spec.speed ?: 1.0f).coerceIn(0.25f, 4.0f),
        )
    }

    /**
     * Absolute target, or current + signed delta. The caller has already
     * rejected the no-op (no position, no delta) case.
     */
    private fun seek(
        controller: MediaControllerHandle,
        spec: MusicPlaybackOrchestrator.ControlSpec,
    ): Boolean {
        val current = controller.snapshot().positionMs
        val target = spec.positionMs ?: (current + (spec.deltaMs ?: 0L))
        return controller.seekTo(target.coerceAtLeast(0))
    }

    private fun unsupported(
        namedApp: MediaAppInfo?,
        what: String,
    ): MusicPlaybackOrchestrator.Outcome = MusicPlaybackOrchestrator.Outcome(
        MusicPlaybackOrchestrator.Status.ERROR,
        namedApp,
        strategy = "unsupported",
        detail = "Этот плеер не поддерживает $what.",
        isError = true,
    )

    /**
     * No live session and no browser token: the media-key fallback only
     * exists for the basic six actions — a media key cannot seek, like,
     * repeat or set speed.
     */
    private fun mediaKeyFallback(
        action: MusicPlaybackOrchestrator.Action,
        target: MediaAppInfo?,
        controllers: List<MediaControllerHandle>,
    ): MusicPlaybackOrchestrator.Outcome {
        val namedApp = target ?: controllers.firstOrNull()?.let {
            MediaAppInfo(it.packageName, it.packageName)
        }
        if (!MusicPlaybackOrchestrator.TransportPolicy.mediaKeyEligible(action)) {
            return MusicPlaybackOrchestrator.Outcome(
                MusicPlaybackOrchestrator.Status.ERROR,
                namedApp,
                strategy = "no_session",
                detail = "Нет запущенного плеера — ${actionName(action)} работает только при включённой музыке.",
                isError = true,
            )
        }

        val key = when (action) {
            MusicPlaybackOrchestrator.Action.PLAY -> MediaKey.PLAY
            MusicPlaybackOrchestrator.Action.PAUSE -> MediaKey.PAUSE
            MusicPlaybackOrchestrator.Action.TOGGLE -> MediaKey.PLAY_PAUSE
            MusicPlaybackOrchestrator.Action.NEXT -> MediaKey.NEXT
            MusicPlaybackOrchestrator.Action.PREVIOUS -> MediaKey.PREVIOUS
            MusicPlaybackOrchestrator.Action.STOP -> MediaKey.STOP
            else -> throw IllegalStateException("unreachable")
        }
        if (action == MusicPlaybackOrchestrator.Action.STOP) {
            gateway.dispatchMediaKey(key)
            return MusicPlaybackOrchestrator.Outcome(
                MusicPlaybackOrchestrator.Status.DISPATCHED,
                namedApp,
                strategy = "media_key",
                detail = "Отправил стоп.",
            )
        }
        if (action == MusicPlaybackOrchestrator.Action.PLAY &&
            gateway.hasNotificationListenerAccess() && controllers.isEmpty()
        ) {
            // Nothing has EVER played: opening the player is more useful
            // than a dead media key.
            val app = target ?: resolver.resolve(null)
            if (app != null && gateway.launchApp(app)) {
                gateway.dispatchMediaKey(key)
                return MusicPlaybackOrchestrator.Outcome(
                    MusicPlaybackOrchestrator.Status.APP_OPENED,
                    app,
                    strategy = "launch_and_key",
                    detail = "Открыл ${app.label}.",
                )
            }
        }
        gateway.dispatchMediaKey(key)
        return MusicPlaybackOrchestrator.Outcome(
            MusicPlaybackOrchestrator.Status.DISPATCHED,
            namedApp,
            strategy = "media_key",
            detail = "Живой сессии плеера нет — отправил команду медиаклавишей.",
        )
    }

    // ------------------------------------------------------------------
    // What is playing
    // ------------------------------------------------------------------

    suspend fun nowPlaying(appHint: String?): MusicPlaybackOrchestrator.Outcome {
        val target = if (appHint != null) resolver.resolve(appHint) else null

        // Asking about a NAMED player must not report some other app's
        // track as if it were the answer — including the case where the
        // named app cannot be resolved at all (not installed). Previously
        // an unresolvable hint fell through to "any playing session" and
        // fabricated an answer about a different player.
        if (appHint != null && target == null) {
            return MusicPlaybackOrchestrator.Outcome(
                MusicPlaybackOrchestrator.Status.ERROR,
                null,
                strategy = "named_app_unresolved",
                detail = "Не нашёл плеер «$appHint» на планшете — не могу сказать, что в нём играет.",
                isError = true,
            )
        }
        val controllers = gateway.activeControllers()
        val controller = selectController(controllers, target)

        if (controller == null) {
            return if (target != null) {
                MusicPlaybackOrchestrator.Outcome(
                    MusicPlaybackOrchestrator.Status.ERROR,
                    target,
                    strategy = "named_app_miss",
                    detail = "В ${target.label} сейчас ничего не играет.",
                    isError = true,
                )
            } else {
                MusicPlaybackOrchestrator.Outcome(
                    MusicPlaybackOrchestrator.Status.ERROR,
                    null,
                    detail = "Сейчас ничего не играет — нет активного плеера.",
                    isError = true,
                )
            }
        }

        val np = controller.snapshot()
        val what = listOfNotNull(np.title, np.artist).joinToString(" — ")
            .ifBlank { "неизвестный трек" }
        val stateWord = if (np.isPlaying) "играет" else "на паузе"
        val queueWord = if (np.queueSize > 0 && np.queueIndex >= 0) {
            ", ${np.queueIndex + 1} из ${np.queueSize}"
        } else {
            ""
        }
        val extras = buildList {
            if (np.speed != 1.0f && np.speed > 0f) add("скорость ${np.speed}x")
            when (np.repeatMode) {
                MediaCapabilities.REPEAT_MODE_ONE -> add("повтор трека")
                MediaCapabilities.REPEAT_MODE_ALL, MediaCapabilities.REPEAT_MODE_GROUP -> add("повтор всего")
            }
            if (MediaCapabilities.shuffleEnabled(np.shuffleMode) == true) add("перемешано")
        }.joinToString(", ").ifBlank { "" }
        val extrasWord = if (extras.isBlank()) "" else ", $extras"
        return MusicPlaybackOrchestrator.Outcome(
            MusicPlaybackOrchestrator.Status.DISPATCHED,
            target ?: MediaAppInfo(controller.packageName, controller.packageName),
            nowPlaying = np,
            detail = "Играет: $what ($stateWord$queueWord$extrasWord).",
        )
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Session selection for status reads. Order: the NAMED app's session →
     * any PLAYING session → the most recent session. A named app that is
     * installed but has no live session is a miss: we answer instructively
     * instead of silently reporting a random player. Transport commands use
     * [resolveController], which additionally consults the recently targeted
     * app and its browser token — a status read has no command to route.
     */
    internal fun selectController(
        controllers: List<MediaControllerHandle>,
        target: MediaAppInfo?,
    ): MediaControllerHandle? {
        if (target != null) {
            controllers.firstOrNull { it.packageName == target.packageName }?.let { return it }
            return null // named-app miss — the caller answers honestly
        }
        return controllers.firstOrNull { it.snapshot().isPlaying }
            ?: controllers.firstOrNull()
    }

    /**
     * Tier 1: the empty-query semantics of the Assistant contract. A PAUSED
     * session resumes (predictable); a STOPPED/NONE session has nothing to
     * resume, so on players advertising playFromSearch we send the EMPTY
     * query — "play my recent mix / something" — and only then fall back to
     * a plain play(). «включи музыку» stops being a dead command.
     *
     * M-4: returns the DISPATCH RESULT — Action.PLAY used to hardcode `true`,
     * so an IPC failure was reported as "Команда отправлена".
     */
    private fun playOrResume(controller: MediaControllerHandle): Boolean {
        val np = controller.snapshot()
        return when {
            np.isPlaying || np.state == NowPlaying.STATE_PAUSED -> controller.play()
            else -> {
                val dispatched = controller.capabilities()
                    .supports(TransportAction.PLAY_FROM_SEARCH) &&
                    controller.playFromSearchStructured(
                        SearchCommand(query = "", focus = null, extras = emptyMap()),
                    )
                if (dispatched) true else controller.play()
            }
        }
    }

    /** Russian name of an action — used in honest-refusal answers. */
    private fun actionName(action: MusicPlaybackOrchestrator.Action): String = when (action) {
        MusicPlaybackOrchestrator.Action.PLAY -> "воспроизведение"
        MusicPlaybackOrchestrator.Action.PAUSE -> "паузу"
        MusicPlaybackOrchestrator.Action.TOGGLE -> "паузу"
        MusicPlaybackOrchestrator.Action.NEXT -> "следующий трек"
        MusicPlaybackOrchestrator.Action.PREVIOUS -> "предыдущий трек"
        MusicPlaybackOrchestrator.Action.STOP -> "стоп"
        MusicPlaybackOrchestrator.Action.SEEK -> "перемотку"
        MusicPlaybackOrchestrator.Action.RESTART -> "перемотку"
        MusicPlaybackOrchestrator.Action.LIKE -> "лайки"
        MusicPlaybackOrchestrator.Action.REPEAT -> "повтор"
        MusicPlaybackOrchestrator.Action.SHUFFLE -> "перемешивание"
        MusicPlaybackOrchestrator.Action.SPEED -> "смену скорости"
    }

    /** A controller plus the browser session backing it, when the fallback bound one. */
    private data class ResolvedController(
        val handle: MediaControllerHandle?,
        val session: BrowserSession?,
    )
}

/**
 * Service-lifetime memory of the app the assistant last targeted (named in a
 * command, or resolved as the playback target). It exists purely as a
 * tie-breaker when a transport command arrives without an app hint: a player
 * launched but idle publishes no ACTIVE MediaSession, so a fresh
 * active-session snapshot cannot see it, and a stale session from another app
 * must not win. Deliberately NOT persisted — a pref would outlive the player
 * it describes and survive a wipe.
 */
class RecentMusicTarget {
    @Volatile
    var packageName: String? = null
        private set

    fun remember(packageName: String?) {
        if (!packageName.isNullOrBlank()) this.packageName = packageName
    }
}
