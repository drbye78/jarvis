package com.jarvis.assistant

import com.jarvis.assistant.home.ActionVerb
import com.jarvis.assistant.home.Capability
import com.jarvis.assistant.home.CapabilityOutcome
import com.jarvis.assistant.home.DeviceKind
import com.jarvis.assistant.home.HomeAction
import com.jarvis.assistant.home.HomeActionOutcome
import com.jarvis.assistant.home.HomeBackend
import com.jarvis.assistant.home.HomeDevice
import com.jarvis.assistant.home.HomeDeviceKey
import com.jarvis.assistant.home.HomeGrant
import com.jarvis.assistant.home.HomeGrantStore
import com.jarvis.assistant.home.HomeProviderId
import com.jarvis.assistant.home.HomeRepository
import com.jarvis.assistant.home.HomeResult
import com.jarvis.assistant.home.HomeState
import com.jarvis.assistant.home.HomeValue
import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.session.TurnOrigin
import com.jarvis.assistant.tools.HomeTools
import com.jarvis.assistant.tools.ToolContract
import com.jarvis.assistant.tools.ToolRegistry
import com.jarvis.assistant.tools.ToolStrings
import com.jarvis.assistant.tools.TurnAuthorization
import com.jarvis.assistant.tools.WriteConfirmation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The R4 smart-home tool surface, JVM-only with a fake [HomeBackend] (no
 * network, no Android). Pins the control decision table, the T2/T1-grant
 * fast path, the two-turn `homeConfirmControl` confirmation over the REAL
 * [ToolRegistry] + a shared [WriteConfirmation] (single-use, bait-and-switch
 * guarded), and the voice-turn-only authorization of the CONTROL tools.
 */
class HomeToolsTest {

    // ------------------------------------------------------------------
    // Fakes + fixtures.
    // ------------------------------------------------------------------

    private class FakeHomeBackend(
        private val devices: List<HomeDevice>,
        private val states: Map<HomeDeviceKey, HomeState> = emptyMap(),
    ) : HomeBackend {
        override val provider = HomeProviderId.HOME_ASSISTANT
        var applyCount = 0
        var accepted = true
        var lastAction: HomeAction? = null

        override suspend fun discover(): HomeResult<List<HomeDevice>> = HomeResult.Ok(devices)

        override suspend fun readState(keys: List<HomeDeviceKey>): HomeResult<List<HomeState>> =
            HomeResult.Ok(keys.mapNotNull { states[it] })

        override suspend fun apply(action: HomeAction): HomeResult<HomeActionOutcome> {
            applyCount++
            lastAction = action
            return HomeResult.Ok(
                HomeActionOutcome(
                    accepted = accepted,
                    perCapability = listOf(CapabilityOutcome(action.capability, accepted)),
                ),
            )
        }
    }

    private val lightKey = HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "light.kitchen")
    private val lockKey = HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "lock.front")

    private val light = HomeDevice(
        key = lightKey,
        name = "Свет кухни",
        room = "Кухня",
        kind = DeviceKind.LIGHT,
        capabilities = setOf(Capability.ON_OFF, Capability.BRIGHTNESS),
        verbs = setOf(ActionVerb.TURN_ON, ActionVerb.TURN_OFF, ActionVerb.SET_LEVEL),
    )
    private val lock = HomeDevice(
        key = lockKey,
        name = "Замок",
        room = "Прихожая",
        kind = DeviceKind.LOCK,
        capabilities = setOf(Capability.LOCK),
        verbs = setOf(ActionVerb.LOCK, ActionVerb.UNLOCK),
    )
    private val lightState = HomeState(
        key = lightKey,
        values = mapOf(
            Capability.ON_OFF to HomeValue.Bool(true),
            Capability.BRIGHTNESS to HomeValue.Level(50.0),
        ),
        atMs = 7L,
    )
    private val defaultDevices = listOf(light, lock)

    private fun repo(devices: List<HomeDevice> = defaultDevices) =
        HomeRepository(devices, mapOf(lightKey to lightState))

    private fun backend(devices: List<HomeDevice> = defaultDevices) =
        FakeHomeBackend(devices, mapOf(lightKey to lightState))

    private fun homeTools(
        repository: HomeRepository = repo(),
        backend: FakeHomeBackend = backend(),
        aliases: Map<String, HomeDeviceKey> = emptyMap(),
        grants: HomeGrantStore = HomeGrantStore(),
        refresh: suspend () -> Unit = {},
    ): HomeTools = HomeTools(
        repository = repository,
        aliases = { aliases },
        backends = { mapOf(HomeProviderId.HOME_ASSISTANT to backend) },
        grants = { grants },
        strings = ToolStrings.Default,
        refresh = refresh,
    )

    private fun HomeTools.tool(name: String): ToolContract = all().first { it.name == name }

    // ------------------------------------------------------------------
    // Read tools.
    // ------------------------------------------------------------------

    @Test
    fun `homeListDevices returns the catalog with handles and capabilities`() = runBlocking {
        val result = homeTools().tool("homeListDevices").execute("{}")

        val json = Json.parseToJsonElement(result).jsonObject
        assertEquals(2, json["count"]!!.jsonPrimitive.content.toInt())
        val handles = json["devices"]!!.jsonArray.map { it.jsonObject["handle"]!!.jsonPrimitive.content }
        assertTrue("the light handle is advertised", "ha:light.kitchen" in handles)
        assertTrue("the lock handle is advertised", "ha:lock.front" in handles)
        val lightJson = json["devices"]!!.jsonArray
            .first { it.jsonObject["handle"]!!.jsonPrimitive.content == "ha:light.kitchen" }.jsonObject
        val capabilities = lightJson["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(capabilities.contains("ON_OFF"))
        assertTrue(capabilities.contains("BRIGHTNESS"))
    }

    @Test
    fun `homeFindDevices resolves a unique device`() = runBlocking {
        val result = homeTools().tool("homeFindDevices").execute("""{"query":"Свет кухни"}""")

        assertTrue(result, result.contains("\"outcome\":\"unique\""))
        assertTrue(result, result.contains("ha:light.kitchen"))
    }

    @Test
    fun `homeFindDevices surfaces ambiguity instead of guessing`() = runBlocking {
        val secondLight = light.copy(
            key = HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "light.hall"),
            name = "Свет коридора",
        )
        val tools = homeTools(repository = repo(defaultDevices + secondLight))

        val result = tools.tool("homeFindDevices").execute("""{"query":"свет"}""")

        assertTrue(result, result.contains("\"outcome\":\"ambiguous\""))
        assertTrue(result, result.contains("ha:light.kitchen"))
        assertTrue(result, result.contains("ha:light.hall"))
    }

    @Test
    fun `getHomeState returns normalized values and never raw attributes`() = runBlocking {
        val result = homeTools().tool("getHomeState").execute("""{"handle":"ha:light.kitchen"}""")

        assertTrue(result, result.contains("\"ON_OFF\""))
        assertTrue(result, result.contains("\"BRIGHTNESS\""))
        assertFalse("raw provider attributes must never be exposed", result.contains("raw"))
    }

    // ------------------------------------------------------------------
    // Control decision table.
    // ------------------------------------------------------------------

    @Test
    fun `homeControl refuses a read action and points at getHomeState`() = runBlocking {
        val backend = backend()
        val result = homeTools(backend = backend).tool("homeControl")
            .execute("""{"handle":"ha:light.kitchen","action":"read"}""")

        assertTrue(result, result.contains("getHomeState"))
        assertEquals(0, backend.applyCount)
    }

    @Test
    fun `homeControl refuses a T2 lock with a challenge and does not execute`() = runBlocking {
        val backend = backend()
        val result = homeTools(backend = backend).tool("homeControl")
            .execute("""{"handle":"ha:lock.front","action":"unlock"}""")

        assertTrue(result, result.contains("requires_confirmed_control"))
        assertTrue(result, result.contains("ha:lock.front"))
        assertEquals(0, backend.applyCount)
    }

    @Test
    fun `homeControl executes a T1 action that has a matching grant`() = runBlocking {
        val backend = backend()
        val grant = HomeGrant("ha", "light.kitchen", "ON_OFF", "TURN_ON")
        val result = homeTools(backend = backend, grants = HomeGrantStore(listOf(grant)))
            .tool("homeControl").execute("""{"handle":"ha:light.kitchen","action":"on"}""")

        assertTrue(result, result.contains("\"outcome\":\"applied\""))
        assertEquals(1, backend.applyCount)
        assertEquals(Capability.ON_OFF, backend.lastAction!!.capability)
        assertEquals(ActionVerb.TURN_ON, backend.lastAction!!.verb)
    }

    @Test
    fun `homeControl without a grant asks for confirmation and does not execute`() = runBlocking {
        val backend = backend()
        val result = homeTools(backend = backend).tool("homeControl")
            .execute("""{"handle":"ha:light.kitchen","action":"on"}""")

        assertTrue(result, result.contains("requires_confirmed_control"))
        assertEquals(0, backend.applyCount)
    }

    @Test
    fun `homeControl reports an honest refusal for an unknown handle`() = runBlocking {
        val backend = backend()
        val result = homeTools(backend = backend).tool("homeControl")
            .execute("""{"handle":"ha:does.not.exist","action":"on"}""")

        assertTrue(result, result.contains("error"))
        assertEquals(0, backend.applyCount)
    }

    @Test
    fun `homeConfirmControl refuses a T0 read`() = runBlocking {
        val backend = backend()
        val result = homeTools(backend = backend).tool("homeConfirmControl")
            .execute("""{"handle":"ha:light.kitchen","action":"read"}""")

        assertTrue(result, result.contains("getHomeState"))
        assertEquals(0, backend.applyCount)
    }

    @Test
    fun `homeConfirmControl executes a T2 action after confirmation`() = runBlocking {
        val backend = backend()
        val result = homeTools(backend = backend).tool("homeConfirmControl")
            .execute("""{"handle":"ha:lock.front","action":"unlock"}""")

        assertTrue(result, result.contains("\"outcome\":\"applied\""))
        assertEquals(1, backend.applyCount)
        assertEquals(ActionVerb.UNLOCK, backend.lastAction!!.verb)
    }

    // ------------------------------------------------------------------
    // Registry integration: the two-turn confirmation.
    // ------------------------------------------------------------------

    @Test
    fun `homeConfirmControl requires one affirmative then executes exactly once`() = runBlocking {
        val backend = backend()
        val confirmation = WriteConfirmation()
        val registry = ToolRegistry(homeTools(backend = backend).all(), writeConfirmation = confirmation)
        val args = """{"handle":"ha:light.kitchen","action":"on"}"""

        // Turn 1 — the first call only arms a challenge.
        registry.setAuthorizationContext(1, TurnAuthorization.voice())
        confirmation.noteTurnStart(1)
        val first = registry.executeResult(FunctionCall("homeConfirmControl", args))
        assertFalse(first.isError)
        assertTrue(first.content, first.content.contains("needs_confirmation"))
        assertEquals("nothing may run before confirmation", 0, backend.applyCount)

        // Turn 2 — a NEGATIVE utterance must not confirm; the call re-arms.
        confirmation.noteTurnStart(2)
        confirmation.noteUserUtterance(2, "нет")
        registry.setAuthorizationContext(2, TurnAuthorization.voice())
        val rejected = registry.executeResult(FunctionCall("homeConfirmControl", args))
        assertTrue(rejected.content, rejected.content.contains("needs_confirmation"))
        assertEquals("a non-affirmative must not execute", 0, backend.applyCount)

        // Turn 3 — the immediately-next affirmative confirms and executes.
        confirmation.noteTurnStart(3)
        confirmation.noteUserUtterance(3, "да")
        registry.setAuthorizationContext(3, TurnAuthorization.voice())
        val confirmed = registry.executeResult(FunctionCall("homeConfirmControl", args))
        assertFalse(confirmed.content, confirmed.content.contains("needs_confirmation"))
        assertFalse(confirmed.isError)
        assertEquals(1, backend.applyCount)

        // Turn 4 — a replay without a fresh affirmative must NOT execute again.
        confirmation.noteTurnStart(4)
        registry.setAuthorizationContext(4, TurnAuthorization.voice())
        val replay = registry.executeResult(FunctionCall("homeConfirmControl", args))
        assertTrue(replay.content, replay.content.contains("needs_confirmation"))
        assertEquals("the confirmation is single-use", 1, backend.applyCount)
    }

    @Test
    fun `homeConfirmControl cannot be confirmed by a missing affirmative`() = runBlocking {
        val backend = backend()
        val confirmation = WriteConfirmation()
        val registry = ToolRegistry(homeTools(backend = backend).all(), writeConfirmation = confirmation)
        val args = """{"handle":"ha:light.kitchen","action":"on"}"""

        registry.setAuthorizationContext(1, TurnAuthorization.voice())
        confirmation.noteTurnStart(1)
        registry.executeResult(FunctionCall("homeConfirmControl", args))

        // No utterance is recorded for turn 2; the call only re-arms.
        registry.setAuthorizationContext(2, TurnAuthorization.voice())
        confirmation.noteTurnStart(2)
        val result = registry.executeResult(FunctionCall("homeConfirmControl", args))

        assertTrue(result.content, result.content.contains("needs_confirmation"))
        assertEquals(0, backend.applyCount)
    }

    @Test
    fun `a non-voice turn denies the controlled home tools before the gate`() = runBlocking {
        val backend = backend()
        val registry = ToolRegistry(homeTools(backend = backend).all())
        registry.setAuthorizationContext(9, TurnAuthorization(TurnOrigin.PROACTIVE, explicitUserCommand = true))

        val control = registry.executeResult(
            FunctionCall("homeControl", """{"handle":"ha:light.kitchen","action":"on"}"""),
        )
        val confirm = registry.executeResult(
            FunctionCall("homeConfirmControl", """{"handle":"ha:light.kitchen","action":"on"}"""),
        )

        assertTrue("a proactive turn must not reach homeControl", control.isError)
        assertTrue("a proactive turn must not reach homeConfirmControl", confirm.isError)
        assertEquals("neither call may execute off the voice lane", 0, backend.applyCount)
    }
}
