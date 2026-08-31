package com.jarvis.assistant

import com.jarvis.assistant.media.MediaAppInfo
import com.jarvis.assistant.media.MediaGateway
import com.jarvis.assistant.media.MediaControllerHandle
import com.jarvis.assistant.media.MusicAppCatalog
import com.jarvis.assistant.media.MusicPlaybackOrchestrator
import com.jarvis.assistant.media.NowPlaying
import com.jarvis.assistant.media.SearchCommand
import com.jarvis.assistant.tools.MusicTools
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 1: the LLM-side contract of the music tools — the playMusic schema
 * must expose the structured slots (and not force the model to glue
 * everything into `query`), and slot-only / slot-less argument shapes must
 * all route into the orchestrator correctly.
 */
class MusicToolsSchemaTest {

    private class SchemaHandle(
        override val packageName: String,
    ) : MediaControllerHandle {
        var lastCommand: SearchCommand? = null
        private var np = NowPlaying(title = "Тишина", state = NowPlaying.STATE_PAUSED)

        override fun snapshot() = np
        override fun capabilities() = com.jarvis.assistant.media.MediaCapabilities.UNKNOWN

        override fun playFromSearch(query: String): Boolean {
            lastCommand = SearchCommand(query, null, emptyMap())
            np = NowPlaying(
                title = query.substringBefore(' ').ifBlank { "Что-то" },
                artist = "Кто-то",
                state = NowPlaying.STATE_PLAYING,
                positionMs = 0,
            )
            return true
        }

        override fun playFromSearchStructured(command: SearchCommand): Boolean {
            lastCommand = command
            np = NowPlaying(
                title = command.extras[SearchCommand.EXTRA_TITLE]
                    ?: command.query.ifBlank { "Что-то" },
                artist = command.extras[SearchCommand.EXTRA_ARTIST],
                state = NowPlaying.STATE_PLAYING,
                positionMs = 0,
            )
            return true
        }

        override fun play() = true
        override fun pause() = true
        override fun skipToNext() = true
        override fun skipToPrevious() = true
        override fun stop() = true
    }

    private class SchemaGateway : MediaGateway {
        val handle = SchemaHandle("ru.yandex.music")
        override fun hasNotificationListenerAccess() = true
        override fun activeControllers(): List<MediaControllerHandle> = listOf(handle)
        override fun dispatchMediaKey(keyCode: Int) = Unit
        override fun openAppSearch(app: MediaAppInfo, query: String) = false
        override fun launchApp(app: MediaAppInfo) = false
    }

    /** Returns the gateway backing the tools so tests can inspect dispatches. */
    private fun buildTools(): Pair<MusicTools, SchemaGateway> {
        val gw = SchemaGateway()
        val orchestrator = MusicPlaybackOrchestrator(
            gw,
            MusicAppCatalog({ listOf("ru.yandex.music" to "Яндекс Музыка") }),
            budgets = MusicPlaybackOrchestrator.Budgets(
                verifyPollMs = 50, verifyTotalMs = 300,
                coldStartPollMs = 50, coldStartTotalMs = 300,
                legacyWaitTotalMs = 300,
            ),
        )
        return MusicTools(orchestrator) to gw
    }

    // ------------------------------------------------------------------
    // playMusic schema shape
    // ------------------------------------------------------------------

    @Test
    fun `playMusic schema exposes all structured slots`() {
        val (tools, _) = buildTools()
        val schema = Json.parseToJsonElement(
            tools.all().first { it.name == "playMusic" }.parametersJson,
        ).jsonObject
        val props = schema["properties"]!!.jsonObject.keys
        assertTrue(props.containsAll(listOf("query", "artist", "album", "playlist", "genre", "app")))
    }

    @Test
    fun `playMusic has no required fields - slot-only requests are valid`() {
        // Forcing required:["query"] would push the model to glue
        // artist/album into the query string — the exact anti-pattern
        // structured search exists to fix.
        val (tools, _) = buildTools()
        val schema = Json.parseToJsonElement(
            tools.all().first { it.name == "playMusic" }.parametersJson,
        ).jsonObject
        assertTrue(schema["required"] == null || schema["required"].toString() == "[]")
    }

    @Test
    fun `playMusic description teaches slot filling`() {
        val (tools, _) = buildTools()
        val description = tools.all().first { it.name == "playMusic" }.description
        assertTrue(description.contains("FILL THE SLOTS"))
        assertTrue(description.contains("Do NOT merge"))
    }

    // ------------------------------------------------------------------
    // playMusic argument routing
    // ------------------------------------------------------------------

    @Test
    fun `slot-only arguments reach the orchestrator structured`() = runTest {
        val (tools, gw) = buildTools()
        val tool = tools.all().first { it.name == "playMusic" }

        val json = tool.execute("""{"query":null,"artist":"Кино"}""")

        val cmd = gw.handle.lastCommand!!
        assertEquals(SearchCommand.FOCUS_ARTIST, cmd.focus)
        assertEquals("Кино", cmd.extras[SearchCommand.EXTRA_ARTIST])
        assertTrue(json.contains("\"status\":\"playing\""))
    }

    @Test
    fun `flat query still works - no slots`() = runTest {
        val (tools, gw) = buildTools()
        val tool = tools.all().first { it.name == "playMusic" }

        val json = tool.execute("""{"query":"Bohemian Rhapsody"}""")

        val cmd = gw.handle.lastCommand!!
        assertTrue(cmd.isUnstructured)
        assertEquals("Bohemian Rhapsody", cmd.query)
        assertTrue(json.contains("\"status\":\"playing\""))
    }

    @Test
    fun `empty arguments produce the instructive outcome - not a JSON error`() = runTest {
        val (tools, _) = buildTools()
        val tool = tools.all().first { it.name == "playMusic" }

        val json = tool.execute("""{}""")

        // The orchestrator's Russian instructive outcome, relayed by the LLM —
        // not a bare {"error":…} payload the model cannot speak.
        assertTrue(json.contains("\"status\":\"error\""))
        assertTrue(json.contains("назови"))
        assertFalse(json.contains("\"error\":"))
    }

    // ------------------------------------------------------------------
    // controlPlayback / getNowPlaying (Phase 4 widened them)
    // ------------------------------------------------------------------

    @Test
    fun `controlPlayback schema carries the twelve actions and their params`() {
        val (tools, _) = buildTools()
        val params = tools.all().first { it.name == "controlPlayback" }.parametersJson
        listOf(
            "play", "pause", "toggle", "next", "previous", "stop",
            "seek", "restart", "like", "repeat", "shuffle", "speed",
            "positionMs", "deltaMs", "mode",
        ).forEach { assertTrue("schema missing $it", params.contains("\"$it\"")) }
    }
}
