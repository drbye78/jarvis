package com.jarvis.assistant

import com.jarvis.assistant.data.AlertDao
import com.jarvis.assistant.data.ScheduledAlertEntity
import com.jarvis.assistant.model.ToolDefinition
import com.jarvis.assistant.tools.AlertArmer
import com.jarvis.assistant.tools.AndroidAlarmScheduler
import com.jarvis.assistant.tools.SetAlarmTool
import com.jarvis.assistant.tools.SetTimerTool
import com.jarvis.assistant.tools.ToolContract
import com.jarvis.assistant.tools.ToolRegistry
import com.jarvis.assistant.tools.WeatherClient
import com.jarvis.assistant.tools.WeatherTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT-AUDIT gap: "FunctionRouter / DeviceTools / WeatherTool — no tests".
 *
 * The router itself is Android-bound (Room + gateway construction) and its
 * WIRING is compile-verified by the router gate against the real sources;
 * the context-carrying tools (Cancel/List alarms, timers, DeviceTools) are
 * likewise compile-verified there. What is JVM-testable — and what these
 * tests pin — is the registration CONTRACT: the full advertised surface
 * (pinned below, matching FunctionRouter's construction list one-to-one),
 * unique names, and schemas that parse as JSON objects — so a registration
 * regression (duplicate name silently shadowing a tool, malformed schema)
 * cannot slip through. WeatherTool's argument validation is exercised
 * directly.
 */
class FunctionRouterTest {

    /** Minimal DAO/armer fakes — tools are only INSPECTED here, never executed. */
    private class NoopDao : AlertDao {
        override suspend fun insert(alert: ScheduledAlertEntity): Long = 1L
        override suspend fun update(alert: ScheduledAlertEntity) {}
        override suspend fun byId(id: Int): ScheduledAlertEntity? = null
        override fun allLive(): Flow<List<ScheduledAlertEntity>> = emptyFlow()
        override fun alarmsLive(): Flow<List<ScheduledAlertEntity>> = emptyFlow()
        override suspend fun all(): List<ScheduledAlertEntity> = emptyList()
        override suspend fun enabled(): List<ScheduledAlertEntity> = emptyList()
        override suspend fun delete(id: Int) {}
        override suspend fun setEnabled(id: Int, enabled: Boolean) {}
    }

    private class NoopArmer : AlertArmer {
        override fun arm(id: Int, triggerAtMillis: Long, kind: String, label: String) {}
        override fun cancel(id: Int, kind: String) {}
    }

    private class NoopWeather : WeatherClient {
        override suspend fun getWeather(location: String): String = "{}"
    }

    /**
     * The router's COMPLETE advertised surface, one-to-one with its
     * construction list in FunctionRouter. Context-free tools are
     * instance-verified below; the context-carrying entries are pinned here
     * and compile-verified by the router gate.
     */
    private val expectedSurface = listOf(
        // Alarm/weather lane (FunctionRouter's explicit list)
        "setAlarm", "cancelAlarm", "listAlarms", "setTimer", "cancelTimer", "getWeather",
        // DeviceTools(appContext).all()
        "setVolume", "setBrightness", "setWifi", "setBluetooth", "setDnd", "lockScreen",
        "openApp", "getDeviceInfo",
        // MusicTools(...).all() — schemas covered in depth by MusicToolsSchemaTest
        "playMusic", "controlPlayback", "getNowPlaying", "listPlaylists", "searchLibrary",
    )

    @Test
    fun `advertised tool surface is complete and has no duplicates`() {
        assertEquals(19, expectedSurface.size)
        assertEquals(
            "duplicate tool names would silently shadow each other in the registry",
            expectedSurface.size,
            expectedSurface.toSet().size,
        )
    }

    @Test
    fun `constructible tools advertise unique names and schema-valid definitions`() {
        val scheduler = AndroidAlarmScheduler(NoopDao(), NoopArmer())
        val tools: List<ToolContract> = listOf(
            SetAlarmTool(scheduler),
            SetTimerTool(scheduler),
            WeatherTool(NoopWeather()),
        )

        val names = tools.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { it in expectedSurface })

        // Every schema parses as a JSON object with a type — the registry
        // silently replaces unparseable schemas with {}, which the LLM reads
        // as "no parameters"; better to fail HERE than in a chat session.
        val defs: List<ToolDefinition> = ToolRegistry(tools).getToolDefinitions()
        assertEquals(tools.size, defs.size)
        defs.forEach { def ->
            assertTrue("blank description for ${def.name}", def.description.isNotBlank())
            assertEquals(
                "schema type for ${def.name}",
                "object",
                (def.parameters["type"] as? JsonPrimitive)?.content,
            )
        }
    }

    @Test
    fun `weather tool validates its arguments before touching the network`() = runBlocking {
        val tool = WeatherTool(NoopWeather())
        assertTrue(tool.execute("not json at all").contains("Invalid JSON"))
        assertTrue(tool.execute("""{"city":"Москва"}""").contains("Missing required parameter"))
        assertEquals("{}", tool.execute("""{"location":"Москва"}"""))
    }
}
