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
 *     TransportControls.playFromSearch(query), then VERIFY playback really
 *     started (title change / began playing / position near track start).
 *     Verification exists because implementing onPlayFromSearch is optional
 *     for player apps; a session that silently ignores the command must not
 *     be reported as success.
 *  2. COLD_START      — no session (app not running): cold-start the app,
 *     poll for its session to appear, then playFromSearch + verify.
 *  3. SEARCH_SCREEN   — deep-link the app to its search screen for the
 *     query. Hands-assisted: the user taps the track. Reported honestly as
 *     SEARCH_OPENED, never as success.
 *
 * Playback commands (pause/next/…) first target the resolved app's live
 * session, then any active session, then fall back to global media keys —
 * which work without notification-listener access but hit whichever app
 * owns media focus.
 */
class MusicPlaybackOrchestrator(
    private val gateway: MediaGateway,
    private val resolver: MusicAppResolver,
    private val budgets: Budgets = Budgets(),
) {

    /** Latency budgets — kept in one place so tests can shrink them. */
    data class Budgets(
        val verifyPollMs: Long = 300,
        val verifyTotalMs: Long = 4_500,
        val coldStartPollMs: Long = 400,
        val coldStartTotalMs: Long = 8_000,
        val maxQueryLength: Int = 200,
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
        /** Human-readable Russian detail for the LLM to relay. */
        val detail: String,
        val isError: Boolean = false,
    )

    // ------------------------------------------------------------------
    // Play a search query
    // ------------------------------------------------------------------

    suspend fun playSearchQuery(rawQuery: String, appHint: String?): Outcome {
        val query = sanitize(rawQuery)
            ?: return Outcome(Status.ERROR, null, detail = "Не понял, что включить — назови трек, исполнителя или плейлист.", isError = true)

        val app = resolver.resolve(appHint)
            ?: return Outcome(
                Status.ERROR, null,
                detail = "Не нашёл музыкальное приложение на планшете. Установи Яндекс Музыку или другой плеер.",
                isError = true,
            )

        if (!gateway.hasNotificationListenerAccess()) {
            // Deep-link search still works without listener access — try it
            // before giving up, but say why hands-free failed.
            val opened = gateway.openAppSearch(app, query)
            return if (opened) {
                Outcome(
                    Status.SEARCH_OPENED, app, strategy = "deep_link_no_access",
                    detail = "Голосовое управление музыкой недоступно: нет доступа к уведомлениям. " +
                        "Открой настройки → Доступ к уведомлениям → разреши Джарвису. Пока открыл поиск.",
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

        // Strategy 1: app already exposes a live session. The `before`
        // baseline MUST be captured BEFORE dispatching playFromSearch — a
        // fast player may switch tracks before the first verification poll,
        // and comparing two post-dispatch snapshots would never see the
        // title change.
        val live = controllerFor(app.packageName)
        if (live != null) {
            Timber.d("Music: active session for %s, playFromSearch", app.packageName)
            val before = live.snapshot()
            live.playFromSearch(query)
            val verified = awaitVerifiedStart(live, query, before)
            if (verified != null) {
                return playing(app, "active_session", verified)
            }
        }

        // Strategy 2: cold start, wait for a session, retry the command.
        var launched = false
        if (gateway.launchApp(app)) {
            launched = true
            val fresh = awaitControllerFor(app.packageName, budgets.coldStartTotalMs, budgets.coldStartPollMs)
            if (fresh != null) {
                val before = fresh.snapshot()
                fresh.playFromSearch(query)
                val verified = awaitVerifiedStart(fresh, query, before)
                if (verified != null) {
                    return playing(app, "cold_start", verified)
                }
            }
        }

        // Strategy 3: hands-assisted search screen.
        val opened = gateway.openAppSearch(app, query)
        return if (opened) {
            Outcome(
                Status.SEARCH_OPENED, app, strategy = "deep_link",
                detail = "Плеер не принял голосовую команду — открыл поиск «$query» в ${app.label}. Нажми на трек.",
            )
        } else {
            // Last resort: make sure the player is at least on screen. No
            // second launch if the cold-start branch already opened it.
            if (!launched) gateway.launchApp(app)
            Outcome(
                Status.APP_OPENED, app, strategy = "launch_only",
                detail = "Не смог запустить воспроизведение голосом — открыл ${app.label}, запусти трек вручную.",
            )
        }
    }

    // ------------------------------------------------------------------
    // Transport commands
    // ------------------------------------------------------------------

    enum class Action { PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, STOP }

    suspend fun control(action: Action, appHint: String?): Outcome {
        val target = if (appHint != null) resolver.resolve(appHint) else null
        val controllers = gateway.activeControllers()

        // Prefer the named app's session, else any active session.
        val controller = when {
            target != null -> controllers.firstOrNull { it.packageName == target.packageName }
                ?: controllers.firstOrNull()
            else -> controllers.firstOrNull()
        }

        val namedApp = target ?: controller?.let { MediaAppInfo(it.packageName, it.packageName) }
        fun detail(what: String) = Outcome(Status.DISPATCHED, namedApp, strategy = "session", detail = what)

        if (controller != null) {
            when (action) {
                Action.PLAY -> controller.play()
                Action.PAUSE -> controller.pause()
                Action.TOGGLE -> {
                    val playing = controller.snapshot().isPlaying
                    if (playing) controller.pause() else controller.play()
                }
                Action.NEXT -> controller.skipToNext()
                Action.PREVIOUS -> controller.skipToPrevious()
                Action.STOP -> controller.stop()
            }
            return detail("Команда отправлена плееру (${controller.packageName}).")
        }

        // No live session at all — global media keys still work for most
        // actions (they hit the app that last held media focus).
        val key = when (action) {
            Action.PLAY -> MediaKey.PLAY
            Action.PAUSE -> MediaKey.PAUSE
            Action.TOGGLE -> MediaKey.PLAY_PAUSE
            Action.NEXT -> MediaKey.NEXT
            Action.PREVIOUS -> MediaKey.PREVIOUS
            Action.STOP -> MediaKey.STOP
        }
        return if (action == Action.STOP) {
            gateway.dispatchMediaKey(key)
            Outcome(Status.DISPATCHED, namedApp, strategy = "media_key", detail = "Отправил стоп.")
        } else {
            if (action == Action.PLAY && gateway.hasNotificationListenerAccess() && controllers.isEmpty()) {
                // Nothing has EVER played: opening the player is more useful
                // than a dead media key.
                val app = target ?: resolver.resolve(null)
                if (app != null && gateway.launchApp(app)) {
                    gateway.dispatchMediaKey(key)
                    return Outcome(Status.APP_OPENED, app, strategy = "launch_and_key", detail = "Открыл ${app.label}.")
                }
            }
            gateway.dispatchMediaKey(key)
            Outcome(
                Status.DISPATCHED, namedApp, strategy = "media_key",
                detail = "Живой сессии плеера нет — отправил команду медиаклавишей.",
            )
        }
    }

    // ------------------------------------------------------------------
    // What is playing
    // ------------------------------------------------------------------

    suspend fun nowPlaying(appHint: String?): Outcome {
        val target = if (appHint != null) resolver.resolve(appHint) else null
        val controllers = gateway.activeControllers()
        val controller = when {
            target != null -> controllers.firstOrNull { it.packageName == target.packageName }
                ?: controllers.firstOrNull()
            else -> controllers.firstOrNull()
        }
            ?: return Outcome(
                Status.ERROR, target,
                detail = "Сейчас ничего не играет — нет активного плеера.",
                isError = true,
            )

        val np = controller.snapshot()
        val what = listOfNotNull(np.title, np.artist).joinToString(" — ")
            .ifBlank { "неизвестный трек" }
        return Outcome(
            Status.DISPATCHED,
            target ?: MediaAppInfo(controller.packageName, controller.packageName),
            nowPlaying = np,
            detail = "Играет: $what (${if (np.isPlaying) "играет" else "на паузе"}).",
        )
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun controllerFor(pkg: String): MediaControllerHandle? =
        gateway.activeControllers().firstOrNull { it.packageName == pkg }

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
     * Wait for evidence that OUR search command (not the previously playing
     * track) is what is now playing. Heuristics, any one is enough:
     *  - playback state went from not-playing → playing/buffering, or
     *  - the track title changed, or
     *  - playback position is near the start of a track (< 10 s).
     *
     * A player that ignores playFromSearch keeps playing the OLD track:
     * state stays playing, title unchanged, position keeps growing — the
     * verification correctly fails and the cascade continues.
     */
    private suspend fun awaitVerifiedStart(
        handle: MediaControllerHandle,
        query: String,
        before: NowPlaying,
    ): NowPlaying? {
        val polls = (budgets.verifyTotalMs / budgets.verifyPollMs).coerceAtLeast(1).toInt()
        repeat(polls) {
            delay(budgets.verifyPollMs)
            val now = runCatching { handle.snapshot() }.getOrNull() ?: return null
            val started = when {
                now.state == NowPlaying.STATE_STOPPED || now.state == NowPlaying.STATE_NONE -> false
                !before.isPlaying && now.isPlaying -> true
                before.title != null && now.title != null &&
                    before.title != now.title -> true
                now.isPlaying && now.positionMs in 0..10_000 -> true
                else -> false
            }
            if (started) return now
        }
        Timber.w("Music: playFromSearch('%s') not verified — continuing cascade", query)
        return null
    }

    private fun playing(app: MediaAppInfo, strategy: String, np: NowPlaying): Outcome {
        val what = listOfNotNull(np.title, np.artist).joinToString(" — ").ifBlank { "запрошенный трек" }
        return Outcome(
            Status.PLAYING, app, strategy = strategy, nowPlaying = np,
            detail = "Включил: $what (${app.label}).",
        )
    }

    private fun sanitize(raw: String): String? {
        val q = raw.trim().replace(Regex("\\s+"), " ")
        if (q.isEmpty()) return null
        return q.take(budgets.maxQueryLength)
    }
}
