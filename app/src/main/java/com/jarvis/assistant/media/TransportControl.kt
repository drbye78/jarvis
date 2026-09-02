package com.jarvis.assistant.media

/**
 * Transport commands for an external player app (play, pause, next, seek,
 * etc.) with capability-gated dispatch and media-key fallback.
 *
 * Selection order (M4): the NAMED app's session → any PLAYING session → the
 * most recent session → media key. A named app that is installed but has no
 * live session is a miss: we answer instructively instead of silently
 * commanding a random player.
 *
 * Rich actions (seek/like/repeat/shuffle/speed) require a live session — a
 * media key cannot express them.
 *
 * Extracted from [MusicPlaybackOrchestrator] (M4 decomposition).
 */
class TransportControl(
    private val gateway: MediaGateway,
    private val resolver: MusicAppResolver,
    /** For the API-29 setPlaybackSpeed guard (plan risk R7); production
     *  passes Build.VERSION.SDK_INT, JVM tests pin it explicitly. */
    private val deviceApiLevel: Int = 30,
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
     * success. Selection (M4) and the media-key fallback are unchanged for
     * the basic six; the rich actions require a live session (a media key
     * cannot seek/like/repeat).
     */
    suspend fun control(
        spec: MusicPlaybackOrchestrator.ControlSpec,
        appHint: String?,
    ): MusicPlaybackOrchestrator.Outcome {
        val action = spec.action
        val target = if (appHint != null) resolver.resolve(appHint) else null
        val controllers = gateway.activeControllers()
        val controller = selectController(controllers, target)

        // M4: the named app is installed but nothing is playing in it — do
        // NOT fall through to some other player's session.
        if (controller == null && target != null) {
            return MusicPlaybackOrchestrator.Outcome(
                MusicPlaybackOrchestrator.Status.ERROR, target, strategy = "named_app_miss",
                detail = "В ${target.label} сейчас ничего не играет. Скажи, какой трек включить, " +
                    "или запусти плеер вручную.",
                isError = true,
            )
        }

        val namedApp = target ?: controller?.let { MediaAppInfo(it.packageName, it.packageName) }
        fun detail(what: String) = MusicPlaybackOrchestrator.Outcome(
            MusicPlaybackOrchestrator.Status.DISPATCHED, namedApp, strategy = "session", detail = what,
        )
        fun unsupported(what: String) = MusicPlaybackOrchestrator.Outcome(
            MusicPlaybackOrchestrator.Status.ERROR, namedApp, strategy = "unsupported",
            detail = "Этот плеер не поддерживает $what.", isError = true,
        )

        if (controller != null) {
            val caps = controller.capabilities()

            // R7: the API gate lives BEFORE any dispatch — on API < 29 the
            // framework transport has no setPlaybackSpeed at all.
            if (action == MusicPlaybackOrchestrator.Action.SPEED &&
                !MusicPlaybackOrchestrator.TransportPolicy.speedAllowed(deviceApiLevel)
            ) {
                return MusicPlaybackOrchestrator.Outcome(
                    MusicPlaybackOrchestrator.Status.ERROR, namedApp, strategy = "api_guard",
                    detail = "Смену скорости этот планшет не поддерживает (нужен Android 10+).",
                    isError = true,
                )
            }
            val required = MusicPlaybackOrchestrator.TransportPolicy.requiredAction(action)
            if (required != null && !caps.supports(required)) {
                return unsupported(actionName(action))
            }
            if (action == MusicPlaybackOrchestrator.Action.LIKE &&
                !MusicPlaybackOrchestrator.TransportPolicy.likeAllowed(caps)
            ) {
                return unsupported("лайки (у плеера другой тип оценки)")
            }

            val dispatched = when (action) {
                MusicPlaybackOrchestrator.Action.PLAY -> {
                    playOrResume(controller)
                    true
                }
                MusicPlaybackOrchestrator.Action.PAUSE -> controller.pause()
                MusicPlaybackOrchestrator.Action.TOGGLE -> {
                    val playing = controller.snapshot().isPlaying
                    if (playing) controller.pause() else controller.play()
                }
                MusicPlaybackOrchestrator.Action.NEXT -> controller.skipToNext()
                MusicPlaybackOrchestrator.Action.PREVIOUS -> controller.skipToPrevious()
                MusicPlaybackOrchestrator.Action.STOP -> controller.stop()
                MusicPlaybackOrchestrator.Action.SEEK -> {
                    val current = controller.snapshot().positionMs
                    val target2 = spec.positionMs ?: (current + (spec.deltaMs ?: 0L))
                    controller.seekTo(target2.coerceAtLeast(0))
                }
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
            return if (dispatched) {
                detail("Команда отправлена плееру (${controller.packageName}).")
            } else {
                MusicPlaybackOrchestrator.Outcome(
                    MusicPlaybackOrchestrator.Status.ERROR, namedApp, strategy = "dispatch_failed",
                    detail = "Плеер не принял команду (возможно, перезапустился) — попробуй ещё раз.",
                    isError = true,
                )
            }
        }

        // No live session: the media-key fallback only exists for the basic
        // six actions — a media key cannot seek, like, repeat or set speed.
        if (!MusicPlaybackOrchestrator.TransportPolicy.mediaKeyEligible(action)) {
            return MusicPlaybackOrchestrator.Outcome(
                MusicPlaybackOrchestrator.Status.ERROR, namedApp, strategy = "no_session",
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
        return if (action == MusicPlaybackOrchestrator.Action.STOP) {
            gateway.dispatchMediaKey(key)
            MusicPlaybackOrchestrator.Outcome(
                MusicPlaybackOrchestrator.Status.DISPATCHED, namedApp,
                strategy = "media_key", detail = "Отправил стоп.",
            )
        } else {
            if (action == MusicPlaybackOrchestrator.Action.PLAY &&
                gateway.hasNotificationListenerAccess() && controllers.isEmpty()
            ) {
                // Nothing has EVER played: opening the player is more useful
                // than a dead media key.
                val app = target ?: resolver.resolve(null)
                if (app != null && gateway.launchApp(app)) {
                    gateway.dispatchMediaKey(key)
                    return MusicPlaybackOrchestrator.Outcome(
                        MusicPlaybackOrchestrator.Status.APP_OPENED, app,
                        strategy = "launch_and_key", detail = "Открыл ${app.label}.",
                    )
                }
            }
            gateway.dispatchMediaKey(key)
            MusicPlaybackOrchestrator.Outcome(
                MusicPlaybackOrchestrator.Status.DISPATCHED, namedApp, strategy = "media_key",
                detail = "Живой сессии плеера нет — отправил команду медиаклавишей.",
            )
        }
    }

    // ------------------------------------------------------------------
    // What is playing
    // ------------------------------------------------------------------

    suspend fun nowPlaying(appHint: String?): MusicPlaybackOrchestrator.Outcome {
        val target = if (appHint != null) resolver.resolve(appHint) else null
        val controllers = gateway.activeControllers()
        val controller = selectController(controllers, target)

        // M4: asking about a NAMED player must not report some other app's
        // track as if it were the answer.
        if (controller == null) {
            return if (target != null) {
                MusicPlaybackOrchestrator.Outcome(
                    MusicPlaybackOrchestrator.Status.ERROR, target, strategy = "named_app_miss",
                    detail = "В ${target.label} сейчас ничего не играет.",
                    isError = true,
                )
            } else {
                MusicPlaybackOrchestrator.Outcome(
                    MusicPlaybackOrchestrator.Status.ERROR, null,
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
        } else ""
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
     * M4: session selection for transport commands. Order: the NAMED app's
     * session → any PLAYING session → the most recent session → media key.
     * A named app that is installed but has no live session is a miss: we
     * answer instructively instead of silently commanding a random player.
     */
    internal fun selectController(
        controllers: List<MediaControllerHandle>,
        target: MediaAppInfo?,
    ): MediaControllerHandle? {
        if (target != null) {
            controllers.firstOrNull { it.packageName == target.packageName }?.let { return it }
            return null // named-app miss — the caller answers honestly (M4)
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
     */
    private fun playOrResume(controller: MediaControllerHandle) {
        val np = controller.snapshot()
        when {
            np.isPlaying || np.state == NowPlaying.STATE_PAUSED -> controller.play()
            else -> {
                val dispatched = controller.capabilities()
                    .supports(TransportAction.PLAY_FROM_SEARCH) &&
                    controller.playFromSearchStructured(
                        SearchCommand(query = "", focus = null, extras = emptyMap()),
                    )
                if (!dispatched) controller.play()
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
}
