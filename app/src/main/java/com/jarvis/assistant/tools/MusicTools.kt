package com.jarvis.assistant.tools

import com.jarvis.assistant.media.MusicPlaybackOrchestrator
import com.jarvis.assistant.util.JsonOut

/**
 * Music tools — the voice path for "Джарвис, включи Bohemian Rhapsody".
 *
 * LLM usage contract (reflected in the descriptions below):
 *  - User names a track/artist/playlist            → playMusic(query=…)
 *  - User just says "включи музыку" / "пауза" /    → controlPlayback(action=…)
 *    "дальше" / "стоп" without naming anything
 *  - User asks "что играет?"                       → getNowPlaying()
 *
 * [PlayMusicTool] overrides the per-tool timeout: the cold-start cascade can
 * legitimately take ~15 s (launch app → wait for its media session →
 * playFromSearch → verify playback actually started).
 */
class MusicTools(private val orchestrator: MusicPlaybackOrchestrator) {

    // ------------------------------------------------------------------
    // playMusic
    // ------------------------------------------------------------------

    inner class PlayMusicTool : ToolContract {
        override val name = "playMusic"
        override val description =
            "Search for music in the installed player app (Яндекс Музыка by default) and play it. " +
                "Use when the user names a track, artist, album or playlist — e.g. " +
                "\"включи Bohemian Rhapsody\", \"поставь Кино Группа крови\", \"включи плейлист для тренировки\". " +
                "Pass a CLEAN search query (song + artist if known), not the full user phrase."
        override val parametersJson = schema(
            mapOf(
                "query" to """{"type":"string","description":"Search query: track/artist/playlist name, e.g. 'Bohemian Rhapsody Queen' or 'Кино Группа крови'"}""",
                "app" to """{"type":"string","description":"Optional player name to disambiguate: 'Яндекс Музыка', 'VK', 'Звук'. Omit for the default player."}""",
            ),
            required = listOf("query"),
        )

        /** Cascade budget: cold start 8 s + verify 4.5 s (+retry margin). */
        override val timeoutMs: Long = 30_000

        override suspend fun execute(arguments: String): String {
            val obj = ToolArgs.parse(arguments)
                ?: return JsonOut.error("Invalid JSON arguments")
            val query = obj.string("query")
                ?: return JsonOut.error("Missing required parameter: query")
            return orchestrator.playSearchQuery(query, obj.string("app")).toJson()
        }
    }

    // ------------------------------------------------------------------
    // controlPlayback
    // ------------------------------------------------------------------

    inner class ControlPlaybackTool : ToolContract {
        override val name = "controlPlayback"
        override val description =
            "Control music playback: play (resume), pause, toggle, next track, previous track, stop. " +
                "Use when the user does NOT name a specific track — \"включи музыку\" (resume), " +
                "\"пауза\", \"дальше\", \"предыдущий\", \"выключи музыку\" (stop). " +
                "When the user names a track or artist, call playMusic instead."
        override val parametersJson = schema(
            mapOf(
                "action" to """{"type":"string","enum":["play","pause","toggle","next","previous","stop"],"description":"Transport command"}""",
                "app" to """{"type":"string","description":"Optional player name if several players exist"}""",
            ),
            required = listOf("action"),
        )

        override suspend fun execute(arguments: String): String {
            val obj = ToolArgs.parse(arguments)
                ?: return JsonOut.error("Invalid JSON arguments")
            val action = obj.string("action")?.lowercase()
                ?: return JsonOut.error("Missing required parameter: action")
            val parsed = when (action) {
                "play", "resume", "включи", "продолжи" -> MusicPlaybackOrchestrator.Action.PLAY
                "pause", "пауза" -> MusicPlaybackOrchestrator.Action.PAUSE
                "toggle" -> MusicPlaybackOrchestrator.Action.TOGGLE
                "next", "дальше", "следующий" -> MusicPlaybackOrchestrator.Action.NEXT
                "previous", "prev", "назад", "предыдущий" -> MusicPlaybackOrchestrator.Action.PREVIOUS
                "stop", "стоп", "выключи" -> MusicPlaybackOrchestrator.Action.STOP
                else -> return JsonOut.error("action must be play|pause|toggle|next|previous|stop")
            }
            return orchestrator.control(parsed, obj.string("app")).toJson()
        }
    }

    // ------------------------------------------------------------------
    // getNowPlaying
    // ------------------------------------------------------------------

    inner class GetNowPlayingTool : ToolContract {
        override val name = "getNowPlaying"
        override val description =
            "Get what is currently playing in the active player: track title, artist, playing/paused state. " +
                "Use for \"что играет?\", \"какая песня?\"."
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

    fun all(): List<ToolContract> = listOf(
        PlayMusicTool(),
        ControlPlaybackTool(),
        GetNowPlayingTool(),
    )
}

/**
 * Uniform JSON shape for all music outcomes; LLM relays [Outcome.detail].
 * Null fields are OMITTED (JsonOut renders nulls as the string "null",
 * which would mislead the model).
 */
private fun MusicPlaybackOrchestrator.Outcome.toJson(): String {
    val pairs = mutableListOf<Pair<String, Any?>>()
    pairs += "status" to status.name.lowercase()
    app?.label?.let { pairs += "app" to it }
    strategy?.let { pairs += "strategy" to it }
    nowPlaying?.let { np ->
        np.title?.let { pairs += "title" to it }
        np.artist?.let { pairs += "artist" to it }
        pairs += "playing" to np.isPlaying
    }
    pairs += "detail" to detail
    return JsonOut.obj(*pairs.toTypedArray())
}
