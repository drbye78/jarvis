package com.jarvis.assistant.tools

import com.jarvis.assistant.media.MediaCapabilities
import com.jarvis.assistant.media.MusicPlaybackOrchestrator
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
class MusicTools(private val orchestrator: MusicPlaybackOrchestrator) {

    // ------------------------------------------------------------------
    // playMusic
    // ------------------------------------------------------------------

    inner class PlayMusicTool : ToolContract {
        override val name = "playMusic"
        override val description =
            "Search for music in the installed player app and play it. The default player is " +
                "Яндекс Музыка unless the user configured another one or names it explicitly " +
                "(\"включи в Звуке\", \"включи в ВКе\" — use the 'app' slot for that). " +
                "Use when the user names a track, artist, album or playlist — e.g. " +
                "\"включи Bohemian Rhapsody\", \"поставь Кино Группа крови\", \"включи альбом Группа крови\", " +
                "\"включи плейлист для тренировки\". FILL THE SLOTS: the track title goes into 'query', " +
                "and artist/album/playlist/genre each go into their own parameter. " +
                "Do NOT merge them — say the user asked \"Кино Группа крови\", send query=\"Группа крови\", " +
                "artist=\"Кино\". 'query' may be empty when only a slot was named."
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

        override suspend fun execute(arguments: String): String {
            val obj = ToolArgs.parse(arguments)
                ?: return JsonOut.error("Invalid JSON arguments")
            val mediaId = obj.string("mediaId")
            if (!mediaId.isNullOrBlank()) {
                return orchestrator.playLibraryItem(mediaId, obj.string("title"), obj.string("app"))
                    .toJson()
            }
            return orchestrator.playSearchQuery(
                rawQuery = obj.string("query") ?: "",
                artist = obj.string("artist"),
                album = obj.string("album"),
                playlist = obj.string("playlist"),
                genre = obj.string("genre"),
                appHint = obj.string("app"),
            ).toJson()
        }
    }

    // ------------------------------------------------------------------
    // controlPlayback
    // ------------------------------------------------------------------

    inner class ControlPlaybackTool : ToolContract {
        override val name = "controlPlayback"
        override val description =
            "Control music playback: play (resume), pause, toggle, next track, previous track, stop, " +
                "seek (rewind/fast-forward), restart track, like (heart), repeat mode, shuffle, playback speed. " +
                "Use when the user does NOT name a specific track — \"включи музыку\" (resume), " +
                "\"пауза\", \"дальше\", \"выключи музыку\" (stop), \"промотай на минуту\" (seek, compute deltaMs), " +
                "\"сначала\"/\"заново\" (restart), \"лайкни\" (like), \"повтори трек\" (repeat one), " +
                "\"перемешай\" (shuffle), \"быстрее\"/\"медленнее\" (speed, pick 1.5 or 0.75). " +
                "When the user names a track or artist, call playMusic instead."
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

        override suspend fun execute(arguments: String): String {
            val obj = ToolArgs.parse(arguments)
                ?: return JsonOut.error("Invalid JSON arguments")
            val action = obj.string("action")?.lowercase()
                ?: return JsonOut.error("Missing required parameter: action")
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
                "repeat", "повтор", "повтори" -> {
                    val modeStr = obj.string("mode")?.lowercase()
                    val repeatMode = when (modeStr) {
                        // Omitted mode keeps the player-default path (ALL).
                        null -> null
                        "off", "none", "выкл", "без повтора" -> MusicPlaybackOrchestrator.RepeatMode.OFF
                        "one", "track", "трек" -> MusicPlaybackOrchestrator.RepeatMode.ONE
                        "all", "все", "всё" -> MusicPlaybackOrchestrator.RepeatMode.ALL
                        // An unrecognized mode used to silently map to ALL —
                        // the OPPOSITE of the likely "repeat this track"
                        // intent. Reject it honestly instead of rewriting it.
                        else -> return JsonOut.error("mode must be one of off|one|all (got '$modeStr')")
                    }
                    MusicPlaybackOrchestrator.ControlSpec(
                        action = MusicPlaybackOrchestrator.Action.REPEAT,
                        repeatMode = repeatMode,
                    )
                }
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
                else -> return JsonOut.error(
                    "action must be play|pause|toggle|next|previous|stop|seek|restart|like|repeat|shuffle|speed",
                )
            }
            return orchestrator.control(spec, obj.string("app")).toJson()
        }
    }

    // ------------------------------------------------------------------
    // getNowPlaying
    // ------------------------------------------------------------------

    inner class GetNowPlayingTool : ToolContract {
        override val name = "getNowPlaying"
        override val description =
            "Get what is currently playing in the active player: track title, artist, album, " +
                "playing/paused state, position, queue placement (e.g. '3 of 12'), speed, repeat, shuffle. " +
                "Use for \"что играет?\", \"какая песня?\", \"на какой мы минуте?\"."
        override val parametersJson = schema(
            mapOf(
                "app" to """{"type":"string","description":"Optional player name if several players exist"}""",
            ),
        )

        override suspend fun execute(arguments: String): String {
            val obj = ToolArgs.parse(arguments)
                ?: return JsonOut.error("Invalid JSON arguments")
            return orchestrator.nowPlaying(obj.string("app")).toJson()
        }
    }

    // ------------------------------------------------------------------
    // listPlaylists / searchLibrary (Tier 3 library lane)
    // ------------------------------------------------------------------

    inner class ListPlaylistsTool : ToolContract {
        override val name = "listPlaylists"
        override val description =
            "List the playlists and library sections of the default player. " +
                "Use for \"какие плейлисты есть\", \"что послушать\", \"покажи библиотеку\". " +
                "Returns up to 10 items with their mediaId — to play one, call playMusic with " +
                "mediaId + title in the SAME conversation, immediately (ids are short-lived)."
        override val parametersJson = schema(
            mapOf(
                "app" to """{"type":"string","description":"Optional player name if several players exist"}""",
            ),
        )

        override suspend fun execute(arguments: String): String {
            val obj = ToolArgs.parse(arguments)
                ?: return JsonOut.error("Invalid JSON arguments")
            return orchestrator.listPlaylists(obj.string("app")).toJson()
        }
    }

    inner class SearchLibraryTool : ToolContract {
        override val name = "searchLibrary"
        override val description =
            "Search the player's own library (different from playMusic: this RETURNS found items " +
                "instead of playing). Use for \"найди в музыке\", \"что есть по запросу X\" or when the " +
                "user asks to choose. Returns up to 10 items with their mediaId — to play one, call " +
                "playMusic with mediaId + title immediately (ids are short-lived)."
        override val parametersJson = schema(
            mapOf(
                "query" to """{"type":"string","description":"What to search for in the library: track/artist/playlist name"}""",
                "app" to """{"type":"string","description":"Optional player name if several players exist"}""",
            ),
            required = listOf("query"),
        )

        override suspend fun execute(arguments: String): String {
            val obj = ToolArgs.parse(arguments)
                ?: return JsonOut.error("Invalid JSON arguments")
            val query = obj.string("query")
                ?: return JsonOut.error("Missing required parameter: query")
            return orchestrator.searchLibrary(query, obj.string("app")).toJson()
        }
    }

    fun all(): List<ToolContract> = listOf(
        PlayMusicTool(),
        ControlPlaybackTool(),
        GetNowPlayingTool(),
        ListPlaylistsTool(),
        SearchLibraryTool(),
    )
}

/**
 * Uniform JSON shape for all music outcomes; LLM relays [Outcome.detail].
 * Null fields are OMITTED (JsonOut renders nulls as the string "null",
 * which would mislead the model) — hence the explicit puts.
 */
private fun MusicPlaybackOrchestrator.Outcome.toJson(): String =
    buildJsonObject {
        put("status", status.name.lowercase())
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
