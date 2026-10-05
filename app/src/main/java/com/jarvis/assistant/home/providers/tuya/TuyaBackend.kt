package com.jarvis.assistant.home.providers.tuya

import com.jarvis.assistant.home.Capability
import com.jarvis.assistant.home.CapabilityOutcome
import com.jarvis.assistant.home.HomeAction
import com.jarvis.assistant.home.HomeActionOutcome
import com.jarvis.assistant.home.HomeBackend
import com.jarvis.assistant.home.HomeDevice
import com.jarvis.assistant.home.HomeDeviceKey
import com.jarvis.assistant.home.HomeError
import com.jarvis.assistant.home.HomeProviderId
import com.jarvis.assistant.home.HomeResult
import com.jarvis.assistant.home.HomeState
import com.jarvis.assistant.home.HomeVerbs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Tuya Cloud [HomeBackend] (Cloud Project, Smart Home).
 *
 * CLOUD-ONLY: every call is HMAC-signed against the project's data-center host
 * via [TuyaOpenApiClient]; there is no LAN path. All provider shapes stay in
 * `home/providers/tuya/`; the core only sees [HomeDevice]/[HomeState]/
 * [HomeActionOutcome].
 *
 * Discovery reads `GET /v1.0/users/{uid}/devices`, which already carries each
 * device's `category` (→ kind) and `status[]` (→ capabilities/state), so one
 * call both enumerates and describes. [apply] first fetches the device's
 * `functions[]` (`GET /v1.0/iot-03/devices/{id}/specification`) because DP codes
 * are device-specific, then encodes against that set — never a hardcoded code.
 *
 * Errors are typed; [CancellationException] is always rethrown. The [json]
 * parser is injected for deterministic tests.
 */
class TuyaBackend(
    private val client: TuyaOpenApiClient,
    private val uid: () -> String?,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val nowMs: () -> Long = System::currentTimeMillis,
) : HomeBackend {

    override val provider: HomeProviderId = HomeProviderId.TUYA

    private val mapper = TuyaCapabilityMapper()

    override suspend fun discover(): HomeResult<List<HomeDevice>> {
        val uidValue = configuredUid() ?: return HomeResult.Err(HomeError.NOT_CONFIGURED, "tuya uid is not configured")
        return when (val response = client.get(deviceListPath(uidValue))) {
            is HomeResult.Err -> response
            is HomeResult.Ok -> HomeResult.Ok(
                TuyaPayloads.deviceList(response.value).mapNotNull { toDevice(it) },
            )
        }
    }

    override suspend fun readState(keys: List<HomeDeviceKey>): HomeResult<List<HomeState>> {
        if (keys.isEmpty()) return HomeResult.Ok(emptyList())
        val uidValue = configuredUid() ?: return HomeResult.Err(HomeError.NOT_CONFIGURED, "tuya uid is not configured")
        return when (val response = client.get(deviceListPath(uidValue))) {
            is HomeResult.Err -> response
            is HomeResult.Ok -> {
                val wanted = keys.map { it.nativeId }.toSet()
                val now = nowMs()
                val states = TuyaPayloads.deviceList(response.value).mapNotNull { device ->
                    val id = TuyaPayloads.deviceId(device) ?: return@mapNotNull null
                    if (id !in wanted) return@mapNotNull null
                    HomeState(
                        key = HomeDeviceKey(HomeProviderId.TUYA, id),
                        values = TuyaStateNormalizer.values(TuyaPayloads.statusMap(device)),
                        raw = emptyMap(),
                        atMs = now,
                    )
                }
                if (states.isEmpty()) {
                    HomeResult.Err(HomeError.NOT_FOUND, "no state for the requested devices")
                } else {
                    HomeResult.Ok(states)
                }
            }
        }
    }

    override suspend fun apply(action: HomeAction): HomeResult<HomeActionOutcome> {
        val deviceId = action.key.nativeId
        val functions = when (val spec = fetchFunctions(deviceId)) {
            is HomeResult.Err -> return spec
            is HomeResult.Ok -> spec.value
        }
        val command = TuyaActionEncoder.encode(action, functions)
            ?: return HomeResult.Err(HomeError.UNSUPPORTED, "action is not expressible as a Tuya command")
        val body = buildJsonObject {
            put(
                "commands",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("code", command.code)
                            // The value literal is written VERBATIM so the bytes we
                            // signed are the bytes we send (a re-serialized value
                            // could reorder/space and invalidate the signature).
                            put("value", parseValue(command.value))
                        },
                    )
                },
            )
        }.toString()
        return when (val response = client.post(commandsPath(deviceId), body)) {
            is HomeResult.Err -> response
            is HomeResult.Ok ->
                HomeResult.Ok(
                    HomeActionOutcome(
                        accepted = true,
                        perCapability = listOf(CapabilityOutcome(action.capability, true)),
                        reconciled = null,
                    ),
                )
        }
    }

    // ------------------------------------------------------------------
    // Mapping.
    // ------------------------------------------------------------------

    private fun toDevice(device: JsonObject): HomeDevice? {
        val id = TuyaPayloads.deviceId(device) ?: return null
        val category = TuyaPayloads.category(device).orEmpty()
        val kind = mapper.deviceKind(category, null)
        if (kind == com.jarvis.assistant.home.DeviceKind.UNKNOWN) return null
        val capabilities = capabilities(category, device)
        if (capabilities.isEmpty()) return null
        return HomeDevice(
            key = HomeDeviceKey(HomeProviderId.TUYA, id),
            name = TuyaPayloads.deviceName(device) ?: id,
            room = null,
            kind = kind,
            capabilities = capabilities,
            verbs = HomeVerbs.of(capabilities),
        )
    }

    /** Capabilities from the device's own `status[]` codes, via the category mapper. */
    private fun capabilities(category: String, device: JsonObject): Set<Capability> {
        val out = linkedSetOf<Capability>()
        for (code in TuyaPayloads.statusMap(device).keys) {
            val capability = mapper.capability(category, code)
            if (capability != Capability.UNKNOWN) out += capability
        }
        // A known actuator with no status yet still offers on/off if the
        // category implies it; this keeps a just-added device usable.
        if (out.isEmpty() && isSwitchable(category)) out += Capability.ON_OFF
        return out
    }

    private fun isSwitchable(category: String): Boolean = category in SWITCHABLE_CATEGORIES

    private suspend fun fetchFunctions(deviceId: String): HomeResult<Set<String>> =
        when (val response = client.get(specificationPath(deviceId))) {
            is HomeResult.Err -> response
            is HomeResult.Ok -> HomeResult.Ok(TuyaPayloads.functionCodes(response.value))
        }

    private fun configuredUid(): String? = uid()?.trim()?.takeIf { it.isNotEmpty() }

    private fun parseValue(literal: String): JsonElement =
        runCatching { json.parseToJsonElement(literal) }.getOrElse { JsonPrimitive(literal) }

    private fun deviceListPath(uid: String): String = "/v1.0/users/$uid/devices"

    private fun specificationPath(deviceId: String): String = "/v1.0/iot-03/devices/$deviceId/specification"

    private fun commandsPath(deviceId: String): String = "/v1.0/devices/$deviceId/commands"

    private companion object {
        val SWITCHABLE_CATEGORIES = setOf("kg", "cz", "pc", "tgkg", "dj", "dd")
    }
}
