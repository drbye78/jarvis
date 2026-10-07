package com.jarvis.assistant.tools

import com.jarvis.assistant.media.MediaCapabilities
import com.jarvis.assistant.media.MusicPlaybackOrchestrator
import com.jarvis.assistant.media.MusicPlayerChoice
import com.jarvis.assistant.util.JsonOut
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Music tools — the voice path for "Джарвис, включи Bohemian Rhapsody".
 *
 * LLM usage contract (reflected in the descriptions below):
 *  - User names a track/artist/album/playlist  → playMusic(query/slots…):
 *    the TITLE goes into query, everything else into its own slot — never
 *    glued together (Tier 1 structured voice search)
 *  - User just says "включи музыку" / "пауза" /    → controlPlayback(action=…)
 *    "дальше" / "стоп" without naming anything
 *  - User asks "что играет?"                       → getNowPlaying()
 *
 * [PlayMusicTool] overrides the per-tool timeout: the cold-start cascade can
 * legitimately take ~25 s (launch app → wait for its media session →
 * playFromSearch → verify → legacy intent → verify → deep link).
 */
class MusicTools(
    private val orchestrator: MusicPlaybackOrchestrator,
    /**
     * Persists the chosen default player (the same pref the Settings
     * «Музыка» radio writes). Defaults to a no-op so JVM tests can construct
     * the tools without a SharedPreferences instance; the composition root
     * wires it to [com.jarvis.assistant.util.AppPrefs.preferredMusicPlayer].
     */
    private val setPreferredPlayer: (String) -> Unit = {},
    /**
     * Whether the chosen package is installed. The resolver's installed list
     * is not reachable from the tools today, so production leaves the default
     * (always true) and the pref is set without a note — injected here so a
     * test can pin the not-installed honesty branch.
     */
    private val isInstalled: (String) -> Boolean = { true },
    /**
     * Locale-aware "unknown player" error. The default is the Russian
     * fallback used by JVM tests; production passes
     * `R.string.tool_music_player_unknown` via the composition root.
     */
    private val unknownPlayerMessage: (String) -> String = { spoken ->
        "Не знаю такой музыкальный сервис: «$spoken». " +
            "Скажи «Яндекс Музыка», «Звук», «ВК Музыка» или «авто»."
    },
) {

    // ------------------------------------------------------------------
    // playMusic
    // ------------------------------------------------------------------

    inner class PlayMusicTool : ToolContract {
        override val name = "playMusic"
        override val risk = ToolRisk.STATEFUL
        override val description =
            "Search for music in the installed player app and play it. The default player is " +
                "Яндекс Музыка unless the user configured another one or names it explicitly " +
                "(\"включи в Звуке\", \"включи в ВКе\" — use the 'app' slot for that). " +
                "Use when the user names a track, artist, album or playlist — e.g. " +
                "\"включи Bohemian Rhapsody\", \"поставь Кино Группа крови\", \"включи альбом Группа крови\", " +
                "\"включи плейлист для тренировки\". FILL THE SLOTS: the track title goes into 'query', " +
                "and artist/album/playlist/genre each go into their own parameter. " +
                "Do NOT merge them — say the user asked \"Кино Группа крови\", send query=\"Группа крови\", " +
                "artist=\"Кино\". 'query' may be empty when only a slot was named. " +
                FINAL_OUTCOME_INSTRUCTION
        override val deduplicateFailedRetries = true
        override val parametersJson = schema(
            mapOf(
                "query" to """{"type":"string","description":"Track title or free search text. Only the title — put the artist/album into their own slots instead of gluing everything here. May be empty when the request is fully structured."}""",
                "artist" to """{"type":"string","description":"Artist/band name slot: 'Queen', 'Кино'. Fill when the user names the performer."}""",
                "album" to """{"type":"string","description":"Album name slot: fill for \"включи альбом X\"."}""",
                "playlist" to """{"type":"string","description":"Playlist name slot: fill for \"включи плейлист X\"."}""",
                "genre" to """{"type":"string","description":"Genre slot: 'рок', 'классика'. Fill for \"включи рок\"."}""",
                "mediaId" to """{"type":"string","description":"EXACT library item id previously returned by listPlaylists or searchLibrary in THIS conversation. When set, query/slots are ignored — pass it together with the item's 'title'. Use only right after those tools returned items."}""",
                "title" to """{"type":"string","description":"Title of the mediaId item, exactly as listPlaylists/searchLibrary returned it — used to verify playback started. Optional, only meaningful with mediaId."}""",
                "app" to """{"type":"string","description":"Optional player name to disambiguate: 'Яндекс Музыка', 'Звук', 'VK Музыка'. Omit when the user did not name a player — the default (from Settings) applies."}""",
            ),
            required = emptyList(),
        )

        /**
         * Cascade budget (worst case, all defaults): live-session verify 4.5 s
         * + browser lane (connect 3 s + search 3 s + two verify passes 9 s)
         * + cold start (await 8 s + verify 4.5 s) + legacy intent (await 6 s +
         * verify 4.5 s) + deep link ≈ 42.5 s. The old 30 s cut the cascade
         * short and surfaced a raw "Tool timed out" error instead of the
         * designed honest fallbacks (SEARCH_OPENED / APP_OPENED).
         */
        override val timeoutMs: Long = 50_000

        override suspend fun executeResult(arguments: String): ToolResult {
            val obj = ToolArgs.parse(arguments)
                ?: return ToolResult(JsonOut.error("Invalid JSON arguments"), isError = true)
            val mediaId = obj.string("mediaId")
            if (!mediaId.isNullOrBlank()) {
                return orchestrator.playLibraryItem(mediaId, obj.string("title"), obj.string("app"))
                    .toToolResult()
            }
            return orchestrator.playSearchQuery(
                rawQuery = obj.string("query") ?: "",
                artist = obj.string("artist"),
                album = obj.string("album"),
                playlist = obj.string("playlist"),
                genre = obj.string("genre"),
                appHint = obj.string("app"),
            ).toToolResult()
        }

        override suspend fun execute(arguments: String): String = executeResult(arguments).content
    }

    // ------------------------------------------------------------------
    // controlPlayback
    // ------------------------------------------------------------------

    inner class ControlPlaybackTool : ToolContract {
        override val name = "controlPlayback"
        override val risk = ToolRisk.STATEFUL
        override val description =
            "Control music playback: play (resume), pause, toggle, next track, previous track, stop, " +
                "seek (rewind/fast-forward), restart track, like (heart), repeat mode, shuffle, playback speed. " +
                "Use when the user does NOT name a specific track — \"включи музыку\" (resume), " +
                "\"пауза\", \"дальше\", \"выключи музыку\" (stop), \"промотай на минуту\" (seek, compute deltaMs), " +
                "\"сначала\"/\"заново\" (restart), \"лайкни\" (like), \"повтори трек\" (repeat one), " +
                "\"перемешай\" (shuffle), \"быстрее\"/\"медленнее\" (speed, pick 1.5 or 0.75). " +
                "When the user names a track or artist, call playMusic instead. " +
                FINAL_COMMAND_OUTCOME_INSTRUCTION
        override val deduplicateFailedRetries = true
        override val parametersJson = schema(
            mapOf(
                "action" to """{"type":"string","enum":["play","pause","toggle","next","previous","stop","seek","restart","like","repeat","shuffle","speed"],"description":"Transport command"}""",
                "positionMs" to """{"type":"integer","description":"seek: ABSOLUTE target position in milliseconds from track start (e.g. 60000 = 1:00). Use for 'на второй минуте'."}""",
                "deltaMs" to """{"type":"integer","description":"seek: SIGNED offset from the current position in milliseconds (e.g. 30000 = forward 30 s, -15000 = back 15 s). Use for 'промотай на минуту'."}""",
                "mode" to """{"type":"string","enum":["off","one","all"],"description":"repeat mode: 'one' = повтори трек, 'all' = повторить всё, 'off' = без повтора"}""",
                "shuffle" to """{"type":"boolean","description":"shuffle: true = перемешай, false = без перемешивания"}""",
                "speed" to """{"type":"number","description":"playback speed multiplier: 1.5 (быстрее), 0.75 (медленнее), 1.0 (нормально). Range 0.25–4.0."}""",
                "app" to """{"type":"string","description":"Optional player name if several players exist"}""",
            ),
            required = listOf("action"),
        )

        override suspend fun executeResult(arguments: String): ToolResult =
            when (val parsed = parseControlSpec(arguments)) {
                is ControlParse.Err -> ToolResult(JsonOut.error(parsed.message), isError = true)
                is ControlParse.Ok -> orchestrator.control(parsed.spec, parsed.app).toToolResult()
            }

        override suspend fun execute(arguments: String): String = executeResult(arguments).content
    }

    // ------------------------------------------------------------------
    // getNowPlaying
    // ------------------------------------------------------------------

    inner class GetNowPlayingTool : ToolContract {
        override val name = "getNowPlaying"
        override val risk = ToolRisk.READ_ONLY
        override val description =
            "Get what is currently playing in the active player: track title, artist, album, " +
                "playing/paused state, position, queue placement (e.g. '3 of 12'), speed, repeat, shuffle. " +
                "Use for \"что играет?\", \"какая песня?\", \"на какой мы минуте?\"."
        override val deduplicateFailedRetries = true
        override val parametersJson = schema(
            mapOf(
                "app" to """{"type":"string","description":"Optional player name if several players exist"}""",
            ),
        )

        override suspend fun executeResult(arguments: String): ToolResult {
            val obj = ToolArgs.parse(arguments)
                ?: return ToolResult(JsonOut.error("Invalid JSON arguments"), isError = true)
            return orchestrator.nowPlaying(obj.string("app")).toToolResult()
        }

        override suspend fun execute(arguments: String): String = executeResult(arguments).content
    }

    // ------------------------------------------------------------------
    // setMusicPlayer
    // ------------------------------------------------------------------

    inner class SetMusicPlayerTool : ToolContract {
        override val name = "setMusicPlayer"
        override val risk = ToolRisk.STATEFUL
        override val description =
            "Set the DEFAULT music player used by playMusic/controlPlayback when the user does not " +
                "name a player — the same choice as Settings → «Музыка». Argument 'app' is the player " +
                "name: 'Яндекс Музыка', 'Звук', 'ВК Музыка', or 'авто' to reset to the automatic choice. " +
                "Use for \"поставь по умолчанию Звук\", \"смени плеер на ВК Музыку\", " +
                "\"плеер по умолчанию — Яндекс Музыка\", \"верни авто\". " +
                "Do NOT use this to play music — call playMusic for that."
        override val deduplicateFailedRetries = true
        override val parametersJson = schema(
            mapOf(
                "app" to """{"type":"string","description":"Default player: 'Яндекс Музыка', """ +
                    """'Звук', 'ВК Музыка' or 'авто' to reset the automatic choice."}""",
            ),
            required = listOf("app"),
        )

        override suspend fun executeResult(arguments: String): ToolResult {
            val obj = ToolArgs.parse(arguments)
                ?: return ToolResult(JsonOut.error("Invalid JSON arguments"), isError = true)
            val spoken = obj.string("app")
                ?: return ToolResult(JsonOut.error("Missing required parameter: app"), isError = true)
            val pref = MusicPlayerChoice.prefValue(spoken)
                ?: return ToolResult(JsonOut.error(unknownPlayerMessage(spoken)), isError = true)
            setPreferredPlayer(pref)
            val pairs = mutableListOf<Pair<String, Any?>>(
                "status" to "ok",
                "player" to pref,
            )
            // Still persist an uninstalled choice (the user asked for it), but
            // say so honestly so the model can mention it.
            if (pref != MusicPlayerChoice.AUTO && !isInstalled(pref)) {
                pairs += "note" to "app is not installed"
            }
            return ToolResult(JsonOut.obj(pairs))
        }

        override suspend fun execute(arguments: String): String = executeResult(arguments).content
    }

    // ------------------------------------------------------------------
    // listPlaylists / searchLibrary (Tier 3 library lane)
    // ------------------------------------------------------------------

    inner class ListPlaylistsTool : ToolContract {
        override val name = "listPlaylists"
        override val risk = ToolRisk.READ_ONLY
        override val description =
            "List the playlists and library sections of the default player. " +
                "Use for \"какие плейлисты есть\", \"что послушать\", \"покажи библиотеку\". " +
                "Returns up to 10 items with their mediaId — to play one, call playMusic with " +
                "mediaId + title in the SAME conversation, immediately (ids are short-lived)."
        override val deduplicateFailedRetries = true
        override val parametersJson = schema(
            mapOf(
                "app" to """{"type":"string","description":"Optional player name if several players exist"}""",
            ),
        )

        override suspend fun executeResult(arguments: String): ToolResult {
            val obj = ToolArgs.parse(arguments)
                ?: return ToolResult(JsonOut.error("Invalid JSON arguments"), isError = true)
            return orchestrator.listPlaylists(obj.string("app")).toToolResult()
        }

        override suspend fun execute(arguments: String): String = executeResult(arguments).content
    }

    inner class SearchLibraryTool : ToolContract {
        override val name = "searchLibrary"
        override val risk = ToolRisk.READ_ONLY
        override val description =
            "Search the player's own library (different from playMusic: this RETURNS found items " +
                "instead of playing). Use for \"найди в музыке\", \"что есть по запросу X\" or when the " +
                "user asks to choose. Returns up to 10 items with their mediaId — to play one, call " +
                "playMusic with mediaId + title immediately (ids are short-lived)."
        override val deduplicateFailedRetries = true
        override val parametersJson = schema(
            mapOf(
                "query" to """{"type":"string","description":"What to search for in the library: track/artist/playlist name"}""",
                "app" to """{"type":"string","description":"Optional player name if several players exist"}""",
            ),
            required = listOf("query"),
        )

        override suspend fun executeResult(arguments: String): ToolResult {
            val obj = ToolArgs.parse(arguments)
                ?: return ToolResult(JsonOut.error("Invalid JSON arguments"), isError = true)
            val query = obj.string("query")
                ?: return ToolResult(JsonOut.error("Missing required parameter: query"), isError = true)
            return orchestrator.searchLibrary(query, obj.string("app")).toToolResult()
        }

        override suspend fun execute(arguments: String): String = executeResult(arguments).content
    }

    fun all(): List<ToolContract> = listOf(
        PlayMusicTool(),
        ControlPlaybackTool(),
        GetNowPlayingTool(),
        SetMusicPlayerTool(),
        ListPlaylistsTool(),
        SearchLibraryTool(),
    )
}

/**
 * Uniform JSON shape for all music outcomes; LLM relays [Outcome.detail].
 * Null fields are OMITTED (JsonOut renders nulls as the string "null",
 * which would mislead the model) — hence the explicit puts.
 *
 * `outcome` is the machine-visible terminal classification the model keys on:
 * `needs_user_action` / `failed` are FINAL — re-calling the tool for the same
 * request cannot make progress (the cascade is exhausted), it only burns
 * passes until the tool loop aborts.
 */
private fun MusicPlaybackOrchestrator.Outcome.toJson(): String =
    buildJsonObject {
        put("status", status.name.lowercase())
        put(
            "outcome",
            when {
                status == MusicPlaybackOrchestrator.Status.PLAYING ||
                    status == MusicPlaybackOrchestrator.Status.DISPATCHED -> "success"
                status.needsUserAction -> "needs_user_action"
                else -> "failed"
            },
        )
        app?.label?.let { put("app", it) }
        strategy?.let { put("strategy", it) }
        nowPlaying?.let { np ->
            np.title?.let { put("title", it) }
            np.artist?.let { put("artist", it) }
            np.album?.let { put("album", it) }
            put("playing", np.isPlaying)
            put("positionSec", np.positionMs / 1000)
            put("durationSec", np.durationMs / 1000)
            if (np.queueSize > 0 && np.queueIndex >= 0) {
                // Human 1-based placement — the LLM says «третья из двенадцати».
                put("queueIndex", np.queueIndex + 1)
                put("queueSize", np.queueSize)
            }
            if (np.speed != 1.0f && np.speed > 0f) put("speed", np.speed.toDouble())
            put("repeat", MediaCapabilities.repeatModeName(np.repeatMode))
            MediaCapabilities.shuffleEnabled(np.shuffleMode)?.let { put("shuffle", it) }
        }
        items?.let { list ->
            // mediaIds are the point of the whole library lane: the LLM must
            // see them to pass back into playMusic. Proper escaping matters —
            // titles contain quotes and slashes.
            put(
                "items",
                buildJsonArray {
                    list.forEach { item ->
                        add(
                            buildJsonObject {
                                put("title", item.title)
                                item.artist?.let { put("artist", it) }
                                put("mediaId", item.mediaId)
                                put("playable", item.playable)
                                put("browsable", item.browsable)
                            },
                        )
                    }
                },
            )
        }
        put("detail", detail)
    }.toString()

/**
 * The structured seam: [ToolResult.isError] is what [ToolRegistry] — and,
 * crucially, the retry guard — observe. A [MusicPlaybackOrchestrator.Status]
 * that needs the user to act is a terminal failure for the model even though
 * the orchestrator itself did not throw, so its outcome is flagged here.
 * [ToolResult.content] keeps the structured JSON shape (never a bare
 * `{"error":…}`), so the model can still read `detail` to speak to the user.
 */
private fun MusicPlaybackOrchestrator.Outcome.toToolResult(): ToolResult =
    ToolResult(toJson(), isError = isError || status.needsUserAction)

/**
 * Appended to the `playMusic` description. The model sees only the tool
 * `content` (never [ToolResult.isError]), so the terminal `outcome` tokens
 * are the ONLY way it can tell "the cascade is done, stop retrying" from
 * "forward progress".
 */
private const val FINAL_OUTCOME_INSTRUCTION =
    "A result with `outcome` = `needs_user_action` or `failed` is final — do NOT call playMusic " +
        "again for the same request; relay `detail` to the user."

/** Same directive for the transport tool, which also returns an Outcome. */
private const val FINAL_COMMAND_OUTCOME_INSTRUCTION =
    "A result with `outcome` = `needs_user_action` or `failed` is final — do NOT call " +
        "controlPlayback again for the same request; relay `detail` to the user."

/** Outcome of parsing a `controlPlayback` action string into a [ControlSpec]. */
private sealed interface ControlParse {
    data class Ok(
        val spec: MusicPlaybackOrchestrator.ControlSpec,
        val app: String?,
    ) : ControlParse

    data class Err(val message: String) : ControlParse
}

/**
 * Recognized `mode` aliases for `controlPlayback(action=repeat)`. A missing
 * mode keeps the player default (null); any string NOT in this map is rejected
 * by the caller. An unrecognized mode used to silently map to ALL — the
 * OPPOSITE of the likely "repeat this track" intent — so it must stay an
 * honest error, not a default.
 */
private val REPEAT_MODES: Map<String, MusicPlaybackOrchestrator.RepeatMode> = mapOf(
    "off" to MusicPlaybackOrchestrator.RepeatMode.OFF,
    "none" to MusicPlaybackOrchestrator.RepeatMode.OFF,
    "выкл" to MusicPlaybackOrchestrator.RepeatMode.OFF,
    "без повтора" to MusicPlaybackOrchestrator.RepeatMode.OFF,
    "one" to MusicPlaybackOrchestrator.RepeatMode.ONE,
    "track" to MusicPlaybackOrchestrator.RepeatMode.ONE,
    "трек" to MusicPlaybackOrchestrator.RepeatMode.ONE,
    "all" to MusicPlaybackOrchestrator.RepeatMode.ALL,
    "все" to MusicPlaybackOrchestrator.RepeatMode.ALL,
    "всё" to MusicPlaybackOrchestrator.RepeatMode.ALL,
)

/**
 * The `repeat` branch of [parseControlSpec]. Omitted mode keeps the
 * player-default path (null); any other string must be a [REPEAT_MODES] member
 * or it is rejected — an unrecognized mode used to silently map to ALL, the
 * OPPOSITE of the likely "repeat this track" intent.
 */
private fun repeatSpec(obj: kotlinx.serialization.json.JsonObject): ControlParse {
    val modeStr = obj.string("mode")?.lowercase()
    val repeatMode = if (modeStr == null) {
        null
    } else {
        REPEAT_MODES[modeStr]
            ?: return ControlParse.Err("mode must be one of off|one|all (got '$modeStr')")
    }
    return ControlParse.Ok(
        MusicPlaybackOrchestrator.ControlSpec(
            action = MusicPlaybackOrchestrator.Action.REPEAT,
            repeatMode = repeatMode,
        ),
        obj.string("app"),
    )
}

/**
 * Maps the model's free-form `action` string to a
 * [MusicPlaybackOrchestrator.ControlSpec], or an honest error for anything
 * unrecognized. Extracted from `ControlPlaybackTool.executeResult` verbatim so
 * the tool keeps a low cyclomatic complexity; the aliases and the deliberate
 * rejections (unknown action, unknown repeat mode) are unchanged.
 */
private fun parseControlSpec(arguments: String): ControlParse {
    val obj = ToolArgs.parse(arguments)
        ?: return ControlParse.Err("Invalid JSON arguments")
    val action = obj.string("action")?.lowercase()
        ?: return ControlParse.Err("Missing required parameter: action")
    val spec = when (action) {
        "play", "resume", "включи", "продолжи" ->
            MusicPlaybackOrchestrator.ControlSpec(MusicPlaybackOrchestrator.Action.PLAY)
        "pause", "пауза" ->
            MusicPlaybackOrchestrator.ControlSpec(MusicPlaybackOrchestrator.Action.PAUSE)
        "toggle" ->
            MusicPlaybackOrchestrator.ControlSpec(MusicPlaybackOrchestrator.Action.TOGGLE)
        "next", "дальше", "следующий" ->
            MusicPlaybackOrchestrator.ControlSpec(MusicPlaybackOrchestrator.Action.NEXT)
        "previous", "prev", "назад", "предыдущий" ->
            MusicPlaybackOrchestrator.ControlSpec(MusicPlaybackOrchestrator.Action.PREVIOUS)
        "stop", "стоп", "выключи" ->
            MusicPlaybackOrchestrator.ControlSpec(MusicPlaybackOrchestrator.Action.STOP)
        "seek", "промотай", "промотать", "перематывай" ->
            MusicPlaybackOrchestrator.ControlSpec(
                action = MusicPlaybackOrchestrator.Action.SEEK,
                positionMs = obj.string("positionMs")?.toLongOrNull(),
                deltaMs = obj.string("deltaMs")?.toLongOrNull(),
            )
        "restart", "сначала", "заново" ->
            MusicPlaybackOrchestrator.ControlSpec(MusicPlaybackOrchestrator.Action.RESTART)
        "like", "лайк", "лайкни", "нравится" ->
            MusicPlaybackOrchestrator.ControlSpec(MusicPlaybackOrchestrator.Action.LIKE)
        "repeat", "повтор", "повтори" -> return repeatSpec(obj)
        "shuffle", "перемешай", "перемешать", "шуфл" ->
            MusicPlaybackOrchestrator.ControlSpec(
                action = MusicPlaybackOrchestrator.Action.SHUFFLE,
                shuffle = obj.bool("shuffle") ?: true,
            )
        "speed", "скорость", "быстрее", "медленнее" ->
            MusicPlaybackOrchestrator.ControlSpec(
                action = MusicPlaybackOrchestrator.Action.SPEED,
                speed = obj.string("speed")?.toFloatOrNull()
                    ?: if (action == "медленнее") 0.75f else 1.5f,
            )
        else -> return ControlParse.Err(
            "action must be play|pause|toggle|next|previous|stop|seek|restart|like|repeat|shuffle|speed",
        )
    }
    return ControlParse.Ok(spec, obj.string("app"))
}
