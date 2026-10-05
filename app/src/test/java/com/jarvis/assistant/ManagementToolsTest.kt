package com.jarvis.assistant

import com.jarvis.assistant.manage.ManagementMode
import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.session.TurnOrigin
import com.jarvis.assistant.tools.ConfirmedTool
import com.jarvis.assistant.tools.ManagementTools
import com.jarvis.assistant.tools.ToolContract
import com.jarvis.assistant.tools.ToolRegistry
import com.jarvis.assistant.tools.ToolRisk
import com.jarvis.assistant.tools.ToolRisks
import com.jarvis.assistant.tools.TurnAuthorization
import com.jarvis.assistant.tools.WriteConfirmation
import com.jarvis.assistant.util.AppPrefs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R13 §14.7 voice enable/disable of the management surface.
 *
 * The tools are Android-free via the injected `applyMode` action, so the whole
 * flow runs over the REAL [ToolRegistry] and the shared [WriteConfirmation]
 * store:
 *
 *  - the three tools are [ToolRisk.CONTROLLED] and advertised;
 *  - `enableLocalManagement` / `disableManagement` run immediately on a voice
 *    turn and apply the mode;
 *  - `enableLanManagement` is fail-closed: an unconfirmed (or wrong/missing
 *    affirmative) call NEVER runs and NEVER writes the pref, only a genuine
 *    affirmative on the immediately-next turn executes it exactly once;
 *  - a non-VOICE turn is denied before the gate for all three.
 */
class ManagementToolsTest {

    private lateinit var prefs: AppPrefs
    private var applyCount = 0

    /** The fake apply action; records how many times a mode was applied. */
    private lateinit var tools: List<ToolContract>

    @Before
    fun setUp() {
        prefs = AppPrefs(context = null, prefsOverride = FakeSharedPreferences())
        applyCount = 0
        tools = ManagementTools(
            prefs = prefs,
            applyMode = { applyCount++ },
        ).all()
    }

    private fun tool(name: String): ToolContract = tools.first { it.name == name }

    private suspend fun execute(registry: ToolRegistry, name: String) =
        registry.executeResult(FunctionCall(name, "{}"))

    private fun outcome(content: String): String =
        Json.parseToJsonElement(content).jsonObject.getValue("outcome").jsonPrimitive.content

    // ------------------------------------------------------------------
    // Classification + registration
    // ------------------------------------------------------------------

    @Test
    fun `the three management tools are advertised and classified CONTROLLED`() {
        assertEquals(
            setOf("enableLocalManagement", "enableLanManagement", "disableManagement"),
            tools.map { it.name }.toSet(),
        )
        tools.forEach { tool ->
            assertEquals("$tool must be CONTROLLED", ToolRisk.CONTROLLED, tool.risk)
            assertEquals(ToolRisk.CONTROLLED, ToolRisks.of(tool.name))
        }
        // Only the LAN opener opts into the two-turn affirmative.
        assertTrue(tool("enableLanManagement") is ConfirmedTool)
        assertFalse(tool("enableLocalManagement") is ConfirmedTool)
        assertFalse(tool("disableManagement") is ConfirmedTool)
    }

    // ------------------------------------------------------------------
    // Local enable / disable — no confirmation needed on a voice turn
    // ------------------------------------------------------------------

    @Test
    fun `enable local writes localhost and applies on a voice turn`() = runBlocking {
        val registry = ToolRegistry(tools)
        registry.setAuthorizationContext(1, TurnAuthorization.voice())

        val result = execute(registry, "enableLocalManagement")

        assertFalse(result.isError)
        assertEquals(ManagementMode.LOCALHOST.id, prefs.managementMode)
        assertEquals("the fake provider must be applied once", 1, applyCount)
    }

    @Test
    fun `disable writes disabled and applies immediately on a voice turn`() = runBlocking {
        prefs.managementMode = ManagementMode.LAN.id
        val registry = ToolRegistry(tools)
        registry.setAuthorizationContext(1, TurnAuthorization.voice())

        val result = execute(registry, "disableManagement")

        assertFalse(result.isError)
        assertEquals(ManagementMode.DISABLED.id, prefs.managementMode)
        assertEquals("the fail-safe direction is always applied", 1, applyCount)
    }

    // ------------------------------------------------------------------
    // LAN enable — the confirmation-gated, fail-closed path
    // ------------------------------------------------------------------

    @Test
    fun `enable lan is fail-closed until the immediately-next turn confirms`() = runBlocking {
        val store = WriteConfirmation()
        val registry = ToolRegistry(tools, writeConfirmation = store)

        // Turn 1: the user asks; the model requests the LAN opener. The pref
        // must be untouched and the apply action must NOT run.
        store.noteTurnStart(1)
        store.noteUserUtterance(1, "включи управление в локальной сети")
        registry.setAuthorizationContext(1, TurnAuthorization.voice())
        val challenge = execute(registry, "enableLanManagement")
        assertFalse("a challenge is a normal (non-error) tool result", challenge.isError)
        assertEquals("needs_confirmation", outcome(challenge.content))
        assertNotEquals(
            "an unconfirmed LAN enable must not write the pref",
            ManagementMode.LAN.id,
            prefs.managementMode,
        )
        assertEquals("an unconfirmed LAN enable must not apply", 0, applyCount)

        // Turn 2: a NEGATIVE answer must not confirm; still no execution.
        store.noteTurnStart(2)
        store.noteUserUtterance(2, "нет")
        registry.setAuthorizationContext(2, TurnAuthorization.voice())
        val denied = execute(registry, "enableLanManagement")
        assertFalse(denied.isError)
        assertEquals("needs_confirmation", outcome(denied.content))
        assertNotEquals(ManagementMode.LAN.id, prefs.managementMode)
        assertEquals("a non-affirmative turn must not apply", 0, applyCount)

        // Turn 3: the user says yes; the exact call now executes once.
        store.noteTurnStart(3)
        store.noteUserUtterance(3, "да")
        registry.setAuthorizationContext(3, TurnAuthorization.voice())
        val executed = execute(registry, "enableLanManagement")
        assertFalse(executed.isError)
        assertEquals(ManagementMode.LAN.id, prefs.managementMode)
        assertEquals("the confirmed LAN enable must apply exactly once", 1, applyCount)

        // Replay within the confirmed turn: single use, no second execution.
        val replay = execute(registry, "enableLanManagement")
        assertFalse("a replay is a fresh challenge, not an error", replay.isError)
        assertEquals("needs_confirmation", outcome(replay.content))
        assertEquals("a confirmation must be single-use", 1, applyCount)

        // A later turn without an affirmative utterance does not confirm.
        store.noteTurnStart(4)
        registry.setAuthorizationContext(4, TurnAuthorization.voice())
        val missing = execute(registry, "enableLanManagement")
        assertFalse(missing.isError)
        assertEquals("needs_confirmation", outcome(missing.content))
        assertEquals("a missing affirmative must not apply", 1, applyCount)
    }

    @Test
    fun `all three management tools are denied before the gate off the voice lane`() = runBlocking {
        val store = WriteConfirmation()
        val registry = ToolRegistry(tools, writeConfirmation = store)
        val nonVoice = listOf<TurnAuthorization>(
            TurnAuthorization(TurnOrigin.PROACTIVE, explicitUserCommand = false),
            TurnAuthorization(TurnOrigin.SCHEDULED, explicitUserCommand = true),
        )

        nonVoice.forEach { context ->
            registry.setAuthorizationContext(1, context)
            tools.forEach { tool ->
                val result = execute(registry, tool.name)
                assertTrue(
                    "${tool.name} must be denied on a non-voice turn ($context)",
                    result.isError,
                )
            }
        }
        assertEquals("no non-voice turn may write the pref", ManagementMode.DISABLED.id, prefs.managementMode)
        assertEquals("no non-voice turn may apply", 0, applyCount)

        // An absent context is likewise denied.
        registry.setAuthorizationContext(1, null)
        tools.forEach { tool ->
            assertTrue(execute(registry, tool.name).isError)
        }
        assertEquals(0, applyCount)
    }
}
