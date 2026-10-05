package com.jarvis.assistant.tools

import com.jarvis.assistant.mcp.McpToolContract
import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.session.TurnOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * The authorization boundary (external-audit finding: no boundary between the
 * LLM and tool execution; a follow-up review found the first cut decorative —
 * `explicitUserCommand` was a constant).
 *
 * Four independent nets:
 *  1. the POLICY truth table ([ToolAuthorization] is pure JVM);
 *  2. the REGISTRY enforcement point — a denied call is an error result and
 *     the tool's `execute` is never invoked;
 *  3. the DERIVATION — the flag comes from the utterance via
 *     [IrreversibleCommand] (see its own truth table in
 *     `IrreversibleCommandTest`);
 *  4. TOTAL and PINNED classification — the 29 canonical risk VALUES live in
 *     [ToolRisks], the registry cross-checks them, and this test additionally
 *     verifies each production class's COMPILED risk against the table.
 */
class ToolAuthorizationTest {

    /** Records invocation so a denial can be proven to skip `execute`. */
    private class RecordingTool(
        override val name: String,
        override val risk: ToolRisk,
    ) : ToolContract {
        var invocations = 0
        override val description = "probe"
        override val parametersJson = """{"type":"object","properties":{}}"""
        override suspend fun execute(arguments: String): String {
            invocations++
            return """{"status":"ok"}"""
        }
    }

    // ------------------------------------------------------------------
    // 1. Policy truth table
    // ------------------------------------------------------------------

    @Test
    fun `read-only is always allowed`() {
        val contexts = listOf<TurnAuthorization?>(
            null,
            TurnAuthorization.voice(),
            TurnAuthorization.voice(explicitUserCommand = true),
            TurnAuthorization.system(),
        )
        contexts.forEach { ctx ->
            assertEquals(
                "READ_ONLY must be allowed for $ctx",
                AuthorizationDecision.Allow,
                ToolAuthorization.decide("getWeather", ToolRisk.READ_ONLY, ctx),
            )
        }
    }

    @Test
    fun `stateful fails closed without a context and is allowed with one`() {
        assertTrue(
            "an absent context must deny a state-changing tool",
            ToolAuthorization.decide("setVolume", ToolRisk.STATEFUL, null)
            is AuthorizationDecision.Deny,
        )
        assertEquals(
            AuthorizationDecision.Allow,
            ToolAuthorization.decide("setVolume", ToolRisk.STATEFUL, TurnAuthorization.voice()),
        )
        // The off-turn pause-on-wake call binds a system context (see below).
        assertEquals(
            AuthorizationDecision.Allow,
            ToolAuthorization.decide("controlPlayback", ToolRisk.STATEFUL, TurnAuthorization.system()),
        )
    }

    @Test
    fun `irreversible is allowed only on an explicit user voice command`() {
        assertEquals(
            AuthorizationDecision.Allow,
            ToolAuthorization.decide(
                "cancelAlarm",
                ToolRisk.IRREVERSIBLE,
                TurnAuthorization.voice(explicitUserCommand = true),
            ),
        )
        assertTrue(
            ToolAuthorization.decide(
                "cancelAlarm",
                ToolRisk.IRREVERSIBLE,
                TurnAuthorization.voice(explicitUserCommand = false),
            ) is AuthorizationDecision.Deny,
        )
    }

    @Test
    fun `irreversible fails closed on absent, system and non-voice contexts`() {
        val contexts = listOf<TurnAuthorization?>(
            null,
            TurnAuthorization.system(),
            TurnAuthorization(TurnOrigin.SCHEDULED, explicitUserCommand = true),
            TurnAuthorization(TurnOrigin.PROACTIVE, explicitUserCommand = true),
        )
        contexts.forEach { ctx ->
            assertTrue(
                "IRREVERSIBLE must fail closed for $ctx",
                ToolAuthorization.decide("cancelAlarm", ToolRisk.IRREVERSIBLE, ctx)
                is AuthorizationDecision.Deny,
            )
        }
    }

    @Test
    fun `forget_fact is delegated to its own confirmation gate`() {
        // The two-step «list → confirm with «да»» flow means the confirmation
        // turn is NOT a removal command; double-gating here would break it, so
        // every context must let forget_fact reach its own gate.
        val contexts = listOf<TurnAuthorization?>(
            null,
            TurnAuthorization.voice(explicitUserCommand = false),
            TurnAuthorization.system(),
        )
        contexts.forEach { ctx ->
            assertEquals(
                "forget_fact must reach its own gate for $ctx",
                AuthorizationDecision.Allow,
                ToolAuthorization.decide("forget_fact", ToolRisk.IRREVERSIBLE, ctx),
            )
        }
    }

    @Test
    fun `external tools are allowed only on a bound voice turn`() {
        assertEquals(
            "a voice turn permits an external tool",
            AuthorizationDecision.Allow,
            ToolAuthorization.decide("mcp_abc123_search", ToolRisk.EXTERNAL, TurnAuthorization.voice()),
        )
        // An explicit command flag is irrelevant — EXTERNAL keys off origin.
        assertEquals(
            AuthorizationDecision.Allow,
            ToolAuthorization.decide(
                "mcp_abc123_search",
                ToolRisk.EXTERNAL,
                TurnAuthorization.voice(explicitUserCommand = true),
            ),
        )
    }

    @Test
    fun `external tools fail closed off the voice lane`() {
        val contexts = listOf<TurnAuthorization?>(
            null,
            TurnAuthorization.system(),
            TurnAuthorization(TurnOrigin.SCHEDULED, explicitUserCommand = true),
            TurnAuthorization(TurnOrigin.PROACTIVE, explicitUserCommand = true),
        )
        contexts.forEach { ctx ->
            assertTrue(
                "EXTERNAL must fail closed for $ctx",
                ToolAuthorization.decide("mcp_abc123_search", ToolRisk.EXTERNAL, ctx)
                is AuthorizationDecision.Deny,
            )
        }
    }

    @Test
    fun `external write tools are allowed on a voice turn`() {
        assertEquals(
            "a voice turn permits an external write tool (confirmation is added by the registry)",
            AuthorizationDecision.Allow,
            ToolAuthorization.decide("mcp_abc123_send", ToolRisk.EXTERNAL_WRITE, TurnAuthorization.voice()),
        )
    }

    @Test
    fun `external write tools fail closed off the voice lane`() {
        val contexts = listOf<TurnAuthorization?>(
            null,
            TurnAuthorization.system(),
            TurnAuthorization(TurnOrigin.SCHEDULED, explicitUserCommand = true),
            TurnAuthorization(TurnOrigin.PROACTIVE, explicitUserCommand = true),
        )
        contexts.forEach { ctx ->
            assertTrue(
                "EXTERNAL_WRITE must fail closed for $ctx",
                ToolAuthorization.decide("mcp_abc123_send", ToolRisk.EXTERNAL_WRITE, ctx)
                is AuthorizationDecision.Deny,
            )
        }
    }

    @Test
    fun `only external write requires confirmation`() {
        ToolRisk.entries.forEach { risk ->
            assertEquals(
                "requiresConfirmation for $risk",
                risk == ToolRisk.EXTERNAL_WRITE,
                risk.requiresConfirmation,
            )
        }
    }

    @Test
    fun `controlled tools are allowed only on a bound voice turn`() {
        assertEquals(
            "a voice turn permits a controlled local action",
            AuthorizationDecision.Allow,
            ToolAuthorization.decide("enableLanManagement", ToolRisk.CONTROLLED, TurnAuthorization.voice()),
        )
        // Like EXTERNAL, the explicit-command flag is irrelevant.
        assertEquals(
            AuthorizationDecision.Allow,
            ToolAuthorization.decide(
                "enableLanManagement",
                ToolRisk.CONTROLLED,
                TurnAuthorization.voice(explicitUserCommand = true),
            ),
        )
    }

    @Test
    fun `controlled tools fail closed off the voice lane`() {
        val contexts = listOf<TurnAuthorization?>(
            null,
            TurnAuthorization.system(),
            TurnAuthorization(TurnOrigin.SCHEDULED, explicitUserCommand = true),
            TurnAuthorization(TurnOrigin.PROACTIVE, explicitUserCommand = true),
        )
        contexts.forEach { ctx ->
            assertTrue(
                "CONTROLLED must fail closed for $ctx",
                ToolAuthorization.decide("enableLanManagement", ToolRisk.CONTROLLED, ctx)
                is AuthorizationDecision.Deny,
            )
        }
    }

    @Test
    fun `no canonical tool name carries the reserved mcp namespace prefix`() {
        ToolRisks.byName.keys.forEach { name ->
            assertFalse(
                "built-in '$name' must not collide with the dynamic MCP namespace",
                name.startsWith(McpToolContract.NAMESPACE_PREFIX),
            )
        }
    }

    // ------------------------------------------------------------------
    // 2. Registry integration — the single enforcement point
    // ------------------------------------------------------------------

    @Test
    fun `denied tool returns an error result and never invokes execute`() = runBlocking {
        val tool = RecordingTool("cancelAlarm", ToolRisk.IRREVERSIBLE)
        val registry = ToolRegistry(listOf(tool))
        registry.setAuthorizationContext(1, TurnAuthorization.voice(explicitUserCommand = false))

        val result = registry.executeResult(FunctionCall("cancelAlarm", "{}"))

        assertTrue("a denial must be an error result, never a silent success", result.isError)
        assertTrue(result.content.contains("not permitted"))
        assertEquals("a denied tool must never run", 0, tool.invocations)
    }

    @Test
    fun `allowed tool still executes on an explicit user turn`() = runBlocking {
        val tool = RecordingTool("cancelAlarm", ToolRisk.IRREVERSIBLE)
        val registry = ToolRegistry(listOf(tool))
        registry.setAuthorizationContext(1, TurnAuthorization.voice(explicitUserCommand = true))

        val result = registry.executeResult(FunctionCall("cancelAlarm", "{}"))

        assertFalse(result.isError)
        assertEquals(1, tool.invocations)
    }

    @Test
    fun `an absent turn context fails closed for irreversible tools`() = runBlocking {
        val tool = RecordingTool("cancelAlarm", ToolRisk.IRREVERSIBLE)
        val registry = ToolRegistry(listOf(tool))

        val result = registry.executeResult(FunctionCall("cancelAlarm", "{}"))

        assertTrue(result.isError)
        assertEquals(0, tool.invocations)
    }

    @Test
    fun `a mis-declared external write fails closed and never executes`() = runBlocking {
        // Declares the write risk but does NOT implement ConfirmedTool, so
        // its exact call cannot be bound to a domain+action identity. Even on a
        // bound voice turn it must be refused rather than run unconfirmed.
        val tool = RecordingTool("mcp_bad_write", ToolRisk.EXTERNAL_WRITE)
        val registry = ToolRegistry(listOf(tool), writeConfirmation = WriteConfirmation())
        registry.setAuthorizationContext(1, TurnAuthorization.voice())

        val result = registry.executeResult(FunctionCall("mcp_bad_write", "{}"))

        assertTrue("a write tool that cannot be confirmed must be an error", result.isError)
        assertEquals("a mis-declared write must never run", 0, tool.invocations)
    }

    @Test
    fun `a non-command utterance denies cancelAlarm while a command utterance allows it`() = runBlocking {
        val tool = RecordingTool("cancelAlarm", ToolRisk.IRREVERSIBLE)
        val registry = ToolRegistry(listOf(tool))

        // Exactly how TurnRunner binds: derived from the final ASR text.
        registry.setAuthorizationContext(
            1,
            TurnAuthorization.voice(IrreversibleCommand.isCommand("какая погода")),
        )
        assertTrue(
            "an unrelated turn must not authorize an irreversible tool",
            registry.executeResult(FunctionCall("cancelAlarm", "{}")).isError,
        )
        registry.setAuthorizationContext(
            1,
            TurnAuthorization.voice(IrreversibleCommand.isCommand("отмени будильник")),
        )
        assertFalse(
            "the user's own cancel command must authorize it",
            registry.executeResult(FunctionCall("cancelAlarm", "{}")).isError,
        )
        assertEquals(1, tool.invocations)
    }

    @Test
    fun `a superseded turns clear cannot wipe a newer turns context`() = runBlocking {
        val tool = RecordingTool("cancelAlarm", ToolRisk.IRREVERSIBLE)
        val registry = ToolRegistry(listOf(tool))
        registry.setAuthorizationContext(1, TurnAuthorization.voice(explicitUserCommand = false))
        registry.setAuthorizationContext(2, TurnAuthorization.voice(explicitUserCommand = true))
        // The cancelled turn 1 races its end-of-turn clear AFTER turn 2 bound.
        registry.setAuthorizationContext(1, null)

        // Turn 2's explicit command must survive; a stale clear would leave
        // `null` and deny (proving the guard would need the opposite assert).
        val result = registry.executeResult(FunctionCall("cancelAlarm", "{}"))
        assertFalse(result.isError)
        assertEquals(1, tool.invocations)
    }

    @Test
    fun `a system context lets the off-turn pause path execute a stateful tool`() = runBlocking {
        val tool = RecordingTool("controlPlayback", ToolRisk.STATEFUL)
        val registry = ToolRegistry(listOf(tool))
        // Mirrors AppGraph's externalMusicPauser binding.
        registry.setAuthorizationContext(ToolAuthorization.SYSTEM_TURN_ID, TurnAuthorization.system())

        val result = registry.executeResult(FunctionCall("controlPlayback", "{}"))

        assertFalse("pause-on-wake must still run", result.isError)
        assertEquals(1, tool.invocations)
        // Cleared in the same `finally`.
        registry.setAuthorizationContext(ToolAuthorization.SYSTEM_TURN_ID, null)
    }

    // ------------------------------------------------------------------
    // 3. Risk-value pinning
    // ------------------------------------------------------------------

    @Test
    fun `the canonical risk map pins exactly 29 values`() {
        assertEquals(29, EXPECTED.size)
        assertEquals("ToolRisks.byName must equal the pinned table", EXPECTED, ToolRisks.byName)
    }

    @Test
    fun `every production tool class declares its risk explicitly`() {
        assertEquals(29, TOOL_CLASSES.size)
        TOOL_CLASSES.forEach { (name, clazz) ->
            assertTrue(
                "$name must declare ToolContract.risk",
                clazz.declaredMethods.any { it.name == "getRisk" },
            )
        }
    }

    @Test
    fun `every production tool class compiles to its pinned risk value`() {
        // The abstract property only proves a declaration EXISTS. Read the
        // compiled class's constant pool to prove the VALUE, even for
        // Android-bound tools that cannot be instantiated in a JVM test. A
        // reclassification to READ_ONLY changes this reference and fails here.
        TOOL_CLASSES.forEach { (name, clazz) ->
            val referenced = compiledRiskNames(clazz)
            assertEquals(
                "$name must compile to exactly one ToolRisk constant, found $referenced",
                1,
                referenced.size,
            )
            assertEquals("$name risk", EXPECTED.getValue(name).name, referenced.single())
        }
    }

    // ------------------------------------------------------------------
    // 4. Canonical surface regression
    // ------------------------------------------------------------------

    @Test
    fun `no canonical tool is denied on an explicit user turn`() = runBlocking {
        val tools = ToolRisks.byName.map { (name, risk) -> RecordingTool(name, risk) }
        val registry = ToolRegistry(tools)
        registry.setAuthorizationContext(7, TurnAuthorization.voice(explicitUserCommand = true))

        tools.forEach { tool ->
            val result = registry.executeResult(FunctionCall(tool.name, "{}"))
            assertFalse("${tool.name} must not be denied on a user turn", result.isError)
            assertEquals(1, tool.invocations)
        }
    }

    private companion object {
        /**
         * The 29-tool runtime surface (26 base + 3 cognitive) with its
         * canonical classification. This is the PINNED expectation; it must
         * stay byte-for-byte in step with [ToolRisks.byName] and with each
         * production tool class.
         */
        val EXPECTED: Map<String, ToolRisk> = mapOf(
            // alarms / timers
            "setAlarm" to ToolRisk.STATEFUL,
            "cancelAlarm" to ToolRisk.IRREVERSIBLE,
            "listAlarms" to ToolRisk.READ_ONLY,
            "setTimer" to ToolRisk.STATEFUL,
            "cancelTimer" to ToolRisk.IRREVERSIBLE,
            // weather + geo
            "getWeather" to ToolRisk.READ_ONLY,
            "findPlace" to ToolRisk.READ_ONLY,
            "getRoute" to ToolRisk.READ_ONLY,
            "getCurrentLocation" to ToolRisk.READ_ONLY,
            // device control
            "setVolume" to ToolRisk.STATEFUL,
            "setBrightness" to ToolRisk.STATEFUL,
            "setWifi" to ToolRisk.STATEFUL,
            "setBluetooth" to ToolRisk.STATEFUL,
            "setDnd" to ToolRisk.STATEFUL,
            "lockScreen" to ToolRisk.STATEFUL,
            "openApp" to ToolRisk.STATEFUL,
            "getDeviceInfo" to ToolRisk.READ_ONLY,
            // music
            "playMusic" to ToolRisk.STATEFUL,
            "controlPlayback" to ToolRisk.STATEFUL,
            "getNowPlaying" to ToolRisk.READ_ONLY,
            "setMusicPlayer" to ToolRisk.STATEFUL,
            "listPlaylists" to ToolRisk.READ_ONLY,
            "searchLibrary" to ToolRisk.READ_ONLY,
            // cognitive memory
            "remember_fact" to ToolRisk.STATEFUL,
            "recall_facts" to ToolRisk.READ_ONLY,
            "forget_fact" to ToolRisk.IRREVERSIBLE,
            // R13 §14.7 management surface (voice-turn-only local control)
            "enableLocalManagement" to ToolRisk.CONTROLLED,
            "enableLanManagement" to ToolRisk.CONTROLLED,
            "disableManagement" to ToolRisk.CONTROLLED,
        )

        /** Every production tool class, keyed by its advertised name. */
        val TOOL_CLASSES: Map<String, Class<*>> = mapOf(
            "setAlarm" to SetAlarmTool::class.java,
            "cancelAlarm" to CancelAlarmTool::class.java,
            "listAlarms" to ListAlarmsTool::class.java,
            "setTimer" to SetTimerTool::class.java,
            "cancelTimer" to CancelTimerTool::class.java,
            "getWeather" to com.jarvis.assistant.weather.WeatherTool::class.java,
            "findPlace" to GeoPlaceTool::class.java,
            "getRoute" to GeoRouteTool::class.java,
            "getCurrentLocation" to GetCurrentLocationTool::class.java,
            "setVolume" to DeviceTools.SetVolumeTool::class.java,
            "setBrightness" to DeviceTools.SetBrightnessTool::class.java,
            "setWifi" to DeviceTools.SetWifiTool::class.java,
            "setBluetooth" to DeviceTools.SetBluetoothTool::class.java,
            "setDnd" to DeviceTools.SetDndTool::class.java,
            "lockScreen" to DeviceTools.LockScreenTool::class.java,
            "openApp" to DeviceTools.OpenAppTool::class.java,
            "getDeviceInfo" to DeviceTools.GetDeviceInfoTool::class.java,
            "playMusic" to MusicTools.PlayMusicTool::class.java,
            "controlPlayback" to MusicTools.ControlPlaybackTool::class.java,
            "getNowPlaying" to MusicTools.GetNowPlayingTool::class.java,
            "setMusicPlayer" to MusicTools.SetMusicPlayerTool::class.java,
            "listPlaylists" to MusicTools.ListPlaylistsTool::class.java,
            "searchLibrary" to MusicTools.SearchLibraryTool::class.java,
            "remember_fact" to com.jarvis.assistant.cognitive.tools.RememberFactTool::class.java,
            "recall_facts" to com.jarvis.assistant.cognitive.tools.RecallFactsTool::class.java,
            "forget_fact" to com.jarvis.assistant.cognitive.tools.ForgetFactTool::class.java,
            "enableLocalManagement" to ManagementTools.EnableLocalManagementTool::class.java,
            "enableLanManagement" to ManagementTools.EnableLanManagementTool::class.java,
            "disableManagement" to ManagementTools.DisableManagementTool::class.java,
        )

        private val RISK_NAMES =
            setOf("READ_ONLY", "STATEFUL", "IRREVERSIBLE", "EXTERNAL", "EXTERNAL_WRITE", "CONTROLLED")

        /**
         * The [ToolRisk] constant names referenced by [clazz]'s compiled
         * constant pool. A tool class references exactly the one enum constant
         * its `risk` property was initialized with.
         */
        private fun compiledRiskNames(clazz: Class<*>): Set<String> {
            val path = clazz.name.replace('.', '/') + ".class"
            val loader = clazz.classLoader ?: ClassLoader.getSystemClassLoader()
            val bytes = loader.getResourceAsStream(path)?.use { it.readBytes() }
                ?: throw AssertionError("class bytes not found for ${clazz.name}")
            val utf8 = linkedSetOf<String>()
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                input.readInt() // magic
                input.readUnsignedShort() // minor
                input.readUnsignedShort() // major
                val count = input.readUnsignedShort()
                var index = 1
                while (index < count) {
                    when (input.readUnsignedByte()) {
                        1 -> utf8.add(input.readUTF())
                        3, 4 -> input.skipNBytes(4)
                        5, 6 -> {
                            input.skipNBytes(8)
                            index++ // long/double occupy two constant-pool slots
                        }
                        7, 8, 16, 19, 20 -> input.skipNBytes(2)
                        9, 10, 11, 12, 17, 18 -> input.skipNBytes(4)
                        15 -> input.skipNBytes(3)
                        else -> throw AssertionError("unknown constant pool tag in ${clazz.name}")
                    }
                    index++
                }
            }
            return utf8.filterTo(linkedSetOf()) { it in RISK_NAMES }
        }
    }
}
