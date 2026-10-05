package com.jarvis.assistant.tools

import com.jarvis.assistant.home.ActionVerb
import com.jarvis.assistant.home.Capability
import com.jarvis.assistant.home.CapabilityOutcome
import com.jarvis.assistant.home.HomeAction
import com.jarvis.assistant.home.HomeActionOutcome
import com.jarvis.assistant.home.HomeBackend
import com.jarvis.assistant.home.HomeDevice
import com.jarvis.assistant.home.HomeDeviceKey
import com.jarvis.assistant.home.HomeError
import com.jarvis.assistant.home.HomeGrantStore
import com.jarvis.assistant.home.HomeProviderId
import com.jarvis.assistant.home.HomeRepository
import com.jarvis.assistant.home.HomeResolution
import com.jarvis.assistant.home.HomeResolver
import com.jarvis.assistant.home.HomeResult
import com.jarvis.assistant.home.HomeRiskClassifier
import com.jarvis.assistant.home.HomeState
import com.jarvis.assistant.home.HomeTier
import com.jarvis.assistant.home.HomeValue
import com.jarvis.assistant.home.ResolveMode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import timber.log.Timber

/**
 * Smart-home tools (R4). Five tools over the Android-free `home/` core:
 *
 *  - [HomeListDevicesTool] / [HomeFindDevicesTool] / [GetHomeStateTool] are
 *    [ToolRisk.READ_ONLY] observations — they never mutate a device.
 *  - [HomeControlTool] is the fast-path write, [ToolRisk.CONTROLLED]
 *    (voice-turn-only). It executes a recoverable (T1) action only when a
 *    persisted [com.jarvis.assistant.home.HomeGrant] authorizes the exact
 *    `(device, capability, verb)`; everything else returns a normal
 *    `requires_confirmed_control` result and changes nothing.
 *  - [HomeConfirmControlTool] is the two-turn confirmation path, also
 *    [ToolRisk.CONTROLLED] but additionally a [ConfirmedTool] with the STABLE
 *    local tokens `domain="home"`, `action="control"`, so the registry
 *    requires an explicit affirmative on the immediately-next voice turn
 *    before `execute` runs. On execute it re-resolves and re-classifies from
 *    scratch and applies — including T2 (safety-critical) actions the fast
 *    path refused.
 *
 * ## Decision table (`HomeRiskClassifier.classify` over the built action)
 * | tier            | `homeControl`                                | `homeConfirmControl`        |
 * |-----------------|----------------------------------------------|-----------------------------|
 * | T0_READ         | refuse («use getHomeState»)                  | refuse                      |
 * | T2_CRITICAL     | `requires_confirmed_control`, no execution   | execute after confirmation  |
 * | T1 + grant      | execute                                      | execute after confirmation  |
 * | T1 no grant     | `requires_confirmed_control`, no execution   | execute after confirmation  |
 * | ambiguous/none  | honest refusal                               | honest refusal              |
 *
 * Android-free by construction (mirrors [GeoPlaceTool]): it sees only `home/`
 * domain types + [ToolStrings], so the whole lane is JVM-testable with a fake
 * [HomeBackend]. Every collaborator is injected: the catalog ([HomeRepository]),
 * the alias/backends/grants readers and a bounded [refresh] that repopulates
 * the repository.
 *
 * @param refresh bounded discovery + read hook; called by [HomeListDevicesTool]
 *   on every call and lazily by the read/control tools when the catalog is
 *   empty. Never called on the caller's main thread by this layer.
 * @param maxDevices cap for the device listing so one huge integration cannot
 *   flood the model context.
 */
class HomeTools(
    private val repository: HomeRepository,
    private val aliases: () -> Map<String, HomeDeviceKey>,
    private val backends: () -> Map<HomeProviderId, HomeBackend>,
    private val grants: () -> HomeGrantStore,
    private val strings: ToolStrings,
    private val refresh: suspend () -> Unit,
    private val maxDevices: Int = MAX_DEVICES,
) {

    /** All five tools, in advertised order. */
    fun all(): List<ToolContract> = listOf(
        HomeListDevicesTool(),
        HomeFindDevicesTool(),
        GetHomeStateTool(),
        HomeControlTool(),
        HomeConfirmControlTool(),
    )

    // ------------------------------------------------------------------
    // Catalog / resolution helpers (shared by the inner tools).
    // ------------------------------------------------------------------

    /** Populate the catalog on first use; a refresh is bounded by the caller. */
    private suspend fun ensureCatalog() {
        if (repository.all().isNotEmpty()) return
        refresh()
    }

    /** Room vocabulary for the resolver, derived from the live catalog. */
    private fun rooms(): Set<String> = repository.all().mapNotNull { it.room }.toSet()

    /**
     * Resolve a handle (the stable `provider:nativeId` wire from the listing)
     * or, when it is not a known handle, a spoken query. The handle wins.
     */
    private fun resolveDevice(handleOrQuery: String, mode: ResolveMode): HomeResolution {
        val wire = HomeDeviceKey.parseOrNull(handleOrQuery)
        if (wire != null) {
            repository.byHandle(wire)?.let { return HomeResolution.Unique(it) }
        }
        return HomeResolver.resolve(handleOrQuery, repository.all(), aliases(), rooms(), mode)
    }

    private fun resolutionError(): String = errorJson(strings.homeControlAmbiguous)

    private fun notFoundError(): String = errorJson(strings.homeControlNotFound)

    // ------------------------------------------------------------------
    // JSON building.
    // ------------------------------------------------------------------

    private fun deviceJson(device: HomeDevice): JsonObject = buildJsonObject {
        put("handle", device.key.wire)
        put("name", device.name)
        put("kind", device.kind.name)
        device.room?.let { put("room", it) }
        putJsonArray("capabilities") { device.capabilities.forEach { add(it.name) } }
    }

    private fun valueJson(value: HomeValue): JsonElement = when (value) {
        is HomeValue.Bool -> JsonPrimitive(value.value)
        is HomeValue.Level -> JsonPrimitive(value.value)
        is HomeValue.Text -> JsonPrimitive(value.value)
        HomeValue.Unsupported -> JsonNull
    }

    private fun stateJson(device: HomeDevice, state: HomeState?): String = buildJsonObject {
        put("handle", device.key.wire)
        put("name", device.name)
        put("kind", device.kind.name)
        if (state == null) {
            put("available", false)
        } else {
            put("available", true)
            putJsonArray("values") {
                state.values.forEach { (capability, value) ->
                    add(
                        buildJsonObject {
                            put("capability", capability.name)
                            put("value", valueJson(value))
                        },
                    )
                }
            }
        }
    }.toString()

    private fun listJson(): String {
        val all = repository.all()
        val capped = all.take(maxDevices)
        return buildJsonObject {
            put("count", capped.size)
            put("truncated", all.size > capped.size)
            putJsonArray("devices") { capped.forEach { add(deviceJson(it)) } }
        }.toString()
    }

    private fun resolutionJson(resolution: HomeResolution): String = when (resolution) {
        is HomeResolution.Unique ->
            buildJsonObject {
                put("outcome", "unique")
                put("device", deviceJson(resolution.device))
            }.toString()

        is HomeResolution.Ambiguous ->
            buildJsonObject {
                put("outcome", "ambiguous")
                putJsonArray("candidates") {
                    resolution.candidates.take(MAX_CANDIDATES).forEach { add(deviceJson(it)) }
                }
            }.toString()

        HomeResolution.NotFound -> buildJsonObject { put("outcome", "not_found") }.toString()
    }

    private fun errorJson(message: String): String =
        buildJsonObject { put("error", message) }.toString()

    // ------------------------------------------------------------------
    // Action building + application.
    // ------------------------------------------------------------------

    /**
     * Translate a model-facing `action` token into a [HomeAction], validating
     * it against the device's advertised capabilities. An unsupported pairing
     * (or a missing level value) is an honest refusal, never a guess.
     */
    private fun buildAction(device: HomeDevice, token: String, value: Double?): ActionBuild {
        val action = token.trim().lowercase().replace('-', '_')
        return when (action) {
            "on", "turn_on" -> toggle(device, ActionVerb.TURN_ON)
            "off", "turn_off" -> toggle(device, ActionVerb.TURN_OFF)
            "level", "brightness", "set_level" ->
                levelAction(device, value, Capability.BRIGHTNESS, ActionVerb.SET_LEVEL)

            "temperature", "temp", "set_temperature" ->
                levelAction(device, value, Capability.TARGET_TEMPERATURE, ActionVerb.SET_TEMPERATURE)

            "lock" -> lockAction(device, ActionVerb.LOCK)
            "unlock" -> lockAction(device, ActionVerb.UNLOCK)
            "open" -> coverAction(device, ActionVerb.OPEN)
            "close" -> coverAction(device, ActionVerb.CLOSE)
            // A read asked of a write tool: classify as T0 and refuse with a
            // pointer at getHomeState.
            "read", "status" ->
                ActionBuild.Ok(HomeAction(device.key, device.kind, Capability.SENSOR, ActionVerb.READ))

            else -> ActionBuild.Unsupported(strings.homeControlUnsupported)
        }
    }

    private fun toggle(device: HomeDevice, verb: ActionVerb): ActionBuild =
        if (Capability.ON_OFF in device.capabilities) {
            ActionBuild.Ok(HomeAction(device.key, device.kind, Capability.ON_OFF, verb))
        } else {
            ActionBuild.Unsupported(strings.homeControlUnsupported)
        }

    private fun lockAction(device: HomeDevice, verb: ActionVerb): ActionBuild =
        if (Capability.LOCK in device.capabilities) {
            ActionBuild.Ok(HomeAction(device.key, device.kind, Capability.LOCK, verb))
        } else {
            ActionBuild.Unsupported(strings.homeControlUnsupported)
        }

    private fun coverAction(device: HomeDevice, verb: ActionVerb): ActionBuild {
        val capability = if (Capability.COVER_POSITION in device.capabilities) {
            Capability.COVER_POSITION
        } else {
            Capability.ON_OFF
        }
        return ActionBuild.Ok(HomeAction(device.key, device.kind, capability, verb))
    }

    private fun levelAction(
        device: HomeDevice,
        value: Double?,
        capability: Capability,
        verb: ActionVerb,
    ): ActionBuild = when {
        capability !in device.capabilities -> ActionBuild.Unsupported(strings.homeControlUnsupported)
        value == null -> ActionBuild.Unsupported(strings.homeControlMissingValue)
        else -> ActionBuild.Ok(HomeAction(device.key, device.kind, capability, verb, level = value))
    }

    /** The model-facing "ask the user, then call homeConfirmControl" result. */
    private fun challengeJson(device: HomeDevice, token: String): String = buildJsonObject {
        put("outcome", CONFIRM_OUTCOME)
        put("handle", device.key.wire)
        put("target", device.name)
        put("action", token)
        put("instruction", strings.homeConfirmInstruction)
    }.toString()

    /** Apply through the device's backend and report per-capability honestly. */
    private suspend fun applyAndReport(action: HomeAction): String {
        val backend = backends()[action.key.provider]
            ?: return errorJson(strings.homeProviderUnavailable)
        return try {
            when (val outcome = backend.apply(action)) {
                is HomeResult.Ok -> outcomeJson(action, outcome.value)
                is HomeResult.Err -> errorJson(backendErrorText(outcome.error))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Home tool apply failed: %s", e::class.java.simpleName)
            errorJson(strings.homeServiceFailed)
        }
    }

    private fun outcomeJson(action: HomeAction, outcome: HomeActionOutcome): String = buildJsonObject {
        put("outcome", outcomeStatus(outcome))
        put("handle", action.key.wire)
        put("capability", action.capability.name)
        putJsonArray("capabilities") {
            outcome.perCapability.forEach { add(capabilityJson(it)) }
        }
    }.toString()

    private fun capabilityJson(outcome: CapabilityOutcome): JsonObject = buildJsonObject {
        put("capability", outcome.capability.name)
        put("ok", outcome.ok)
    }

    private fun outcomeStatus(outcome: HomeActionOutcome): String = when {
        !outcome.accepted -> "failed"
        outcome.perCapability.all { it.ok } -> "applied"
        else -> "partial"
    }

    private fun backendErrorText(error: HomeError): String = when (error) {
        HomeError.NOT_CONFIGURED -> strings.homeNotConfigured
        HomeError.AUTH -> strings.homeAuthFailed
        HomeError.UNREACHABLE -> strings.homeUnreachable
        HomeError.NOT_FOUND -> strings.homeControlNotFound
        HomeError.AMBIGUOUS -> strings.homeControlAmbiguous
        HomeError.UNSUPPORTED -> strings.homeControlUnsupported
        HomeError.PARTIAL, HomeError.FAILED -> strings.homeServiceFailed
    }

    // ------------------------------------------------------------------
    // Read tools.
    // ------------------------------------------------------------------

    /** Enumerate the catalog (bounded, handles + names + kind + capabilities). */
    inner class HomeListDevicesTool : ToolContract {
        override val name = "homeListDevices"
        override val risk = ToolRisk.READ_ONLY
        override val description =
            "List the discovered smart-home devices with a stable `handle`, name, kind and " +
                "capabilities. Call this FIRST to learn the available handles, then use the handle " +
                "with getHomeState/homeControl. Refreshes discovery from the configured integrations."
        override val parametersJson = EMPTY_PARAMETER_SCHEMA

        override suspend fun execute(arguments: String): String {
            refresh()
            return listJson()
        }
    }

    /** Resolve a spoken name/room to one device, or surface the candidates. */
    inner class HomeFindDevicesTool : ToolContract {
        override val name = "homeFindDevices"
        override val risk = ToolRisk.READ_ONLY
        override val description =
            "Resolve a spoken device name or room to a smart-home device. Returns outcome=unique " +
                "with the device, or outcome=ambiguous with candidates so you can ask the user which " +
                "one they meant. Use the returned handle with getHomeState/homeControl."
        override val parametersJson = schema(
            mapOf(
                "query" to
                    """{"type":"string","description":"The spoken device name or room to find, """ +
                    """e.g. 'свет на кухне' or 'замок в прихожей'. Required."}""",
            ),
            required = listOf("query"),
        )

        override suspend fun execute(arguments: String): String {
            val args = ToolArgs.parse(arguments) ?: return errorJson(INVALID_ARGS)
            val query = args.string("query")?.trim().orEmpty()
            if (query.isEmpty()) return errorJson(strings.homeControlMissingHandle)
            ensureCatalog()
            return resolutionJson(resolveDevice(query, ResolveMode.READ))
        }
    }

    /** Read the normalized state of one device (handle preferred, query allowed). */
    inner class GetHomeStateTool : ToolContract {
        override val name = "getHomeState"
        override val risk = ToolRisk.READ_ONLY
        override val description =
            "Read the current normalized state of a smart-home device. Pass a `handle` from " +
                "homeListDevices (preferred) or a spoken `query`. Returns the typed capability " +
                "values; never raw provider attributes."
        override val parametersJson = schema(
            mapOf(
                "handle" to
                    """{"type":"string","description":"Stable device handle from homeListDevices, """ +
                    """e.g. 'ha:light.kitchen'. Preferred over query."}""",
                "query" to
                    """{"type":"string","description":"Spoken device name, used when no handle is """ +
                    """known."}""",
            ),
        )

        override suspend fun execute(arguments: String): String {
            val args = ToolArgs.parse(arguments) ?: return errorJson(INVALID_ARGS)
            val raw = args.string("handle")?.trim()?.takeIf { it.isNotEmpty() }
                ?: args.string("query")?.trim()?.takeIf { it.isNotEmpty() }
                ?: return errorJson(strings.homeControlMissingHandle)
            ensureCatalog()
            return when (val resolution = resolveDevice(raw, ResolveMode.READ)) {
                is HomeResolution.Unique -> stateJson(resolution.device, repository.state(resolution.device.key))
                is HomeResolution.Ambiguous -> resolutionError()
                HomeResolution.NotFound -> notFoundError()
            }
        }
    }

    // ------------------------------------------------------------------
    // Control tools.
    // ------------------------------------------------------------------

    /** The fast path: T1-with-grant applies; everything else asks for confirmation. */
    inner class HomeControlTool : ToolContract {
        override val name = "homeControl"
        override val risk = ToolRisk.CONTROLLED
        override val description =
            "Control a smart-home device on the fast path. Pass a `handle` from homeListDevices " +
                "and an `action` (on, off, level, temperature, lock, unlock, open, close); provide " +
                "`value` for level/temperature. A recoverable action you have been granted runs " +
                "immediately; anything else returns outcome=requires_confirmed_control and changes " +
                "NOTHING — then ask the user and call homeConfirmControl with the SAME arguments. " +
                "To read a state, use getHomeState instead."
        override val parametersJson = schema(
            mapOf(
                "handle" to HANDLE_PARAM,
                "action" to ACTION_PARAM,
                "value" to VALUE_PARAM,
            ),
            required = listOf("handle", "action"),
        )

        override suspend fun execute(arguments: String): String {
            val args = ToolArgs.parse(arguments) ?: return errorJson(INVALID_ARGS)
            val handle = args.string("handle")?.trim().orEmpty()
            val action = args.string("action")?.trim().orEmpty()
            if (handle.isEmpty()) return errorJson(strings.homeControlMissingHandle)
            if (action.isEmpty()) return errorJson(strings.homeControlMissingAction)
            ensureCatalog()
            return when (val resolution = resolveDevice(handle, ResolveMode.CONTROL)) {
                is HomeResolution.Unique -> fastPath(resolution.device, action, args.double("value"))
                is HomeResolution.Ambiguous -> resolutionError()
                HomeResolution.NotFound -> notFoundError()
            }
        }

        private suspend fun fastPath(device: HomeDevice, token: String, value: Double?): String =
            when (val built = buildAction(device, token, value)) {
                is ActionBuild.Unsupported -> errorJson(built.detail)
                is ActionBuild.Ok -> when (HomeRiskClassifier.classify(built.action)) {
                    HomeTier.T0_READ -> errorJson(strings.homeControlReadOnly)
                    HomeTier.T2_CRITICAL -> challengeJson(device, token)
                    HomeTier.T1_REVERSIBLE ->
                        if (grants().allows(built.action)) {
                            applyAndReport(built.action)
                        } else {
                            challengeJson(device, token)
                        }
                }
            }
    }

    /**
     * The confirmed path. The registry gates the two-turn affirmative through
     * the STABLE local tokens below; on execute we re-resolve and re-classify
     * from the live catalog (never trust the earlier resolution) and refuse T0.
     */
    inner class HomeConfirmControlTool : ToolContract, ConfirmedTool {
        override val name = "homeConfirmControl"
        override val risk = ToolRisk.CONTROLLED
        override val description =
            "Confirm and execute a smart-home action AFTER the user explicitly agreed. Call it " +
                "with the SAME handle/action/value that homeControl returned as " +
                "requires_confirmed_control. The first call only asks for confirmation and changes " +
                "nothing; call it again on the next turn, once the user says yes."
        override val parametersJson = schema(
            mapOf(
                "handle" to HANDLE_PARAM,
                "action" to ACTION_PARAM,
                "value" to VALUE_PARAM,
            ),
            required = listOf("handle", "action"),
        )

        override val confirmationDomain = "home"
        override val confirmationAction = "control"

        override suspend fun execute(arguments: String): String {
            val args = ToolArgs.parse(arguments) ?: return errorJson(INVALID_ARGS)
            val handle = args.string("handle")?.trim().orEmpty()
            val action = args.string("action")?.trim().orEmpty()
            if (handle.isEmpty()) return errorJson(strings.homeControlMissingHandle)
            if (action.isEmpty()) return errorJson(strings.homeControlMissingAction)
            ensureCatalog()
            return when (val resolution = resolveDevice(handle, ResolveMode.CONTROL)) {
                is HomeResolution.Unique -> confirmed(resolution.device, action, args.double("value"))
                is HomeResolution.Ambiguous -> resolutionError()
                HomeResolution.NotFound -> notFoundError()
            }
        }

        private suspend fun confirmed(device: HomeDevice, token: String, value: Double?): String =
            when (val built = buildAction(device, token, value)) {
                is ActionBuild.Unsupported -> errorJson(built.detail)
                is ActionBuild.Ok ->
                    if (HomeRiskClassifier.classify(built.action) == HomeTier.T0_READ) {
                        errorJson(strings.homeControlReadOnly)
                    } else {
                        applyAndReport(built.action)
                    }
            }
    }

    private companion object {
        /** Cap the advertised device list; the listing reports `truncated`. */
        const val MAX_DEVICES = 60

        /** Cap the candidates echoed for disambiguation. */
        const val MAX_CANDIDATES = 8

        const val CONFIRM_OUTCOME = "requires_confirmed_control"

        const val INVALID_ARGS = "Invalid JSON arguments"

        const val HANDLE_PARAM =
            """{"type":"string","description":"Stable device handle from homeListDevices, """ +
                """e.g. 'ha:light.kitchen'. Required."}"""

        const val ACTION_PARAM =
            """{"type":"string","enum":["on","off","level","temperature","lock","unlock","open","close"],""" +
                """"description":"What to do: on/off toggle power, level sets brightness, """ +
                """temperature sets the target setpoint, lock/unlock a lock, open/close a cover."}"""

        const val VALUE_PARAM =
            """{"type":"number","description":"Required for action=level (0..100) or """ +
                """action=temperature (degrees Celsius); ignored otherwise."}"""
    }
}

/** A built action or an honest refusal reason (already localized). */
private sealed interface ActionBuild {
    data class Ok(val action: HomeAction) : ActionBuild

    data class Unsupported(val detail: String) : ActionBuild
}

/** Numeric tool argument; a non-numeric/missing value is null. */
private fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
