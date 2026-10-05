package com.jarvis.assistant.home.providers.ha

import com.jarvis.assistant.home.CapabilityOutcome
import com.jarvis.assistant.home.DeviceKind
import com.jarvis.assistant.home.HomeAction
import com.jarvis.assistant.home.HomeActionOutcome
import com.jarvis.assistant.home.HomeBackend
import com.jarvis.assistant.home.HomeDevice
import com.jarvis.assistant.home.HomeDeviceKey
import com.jarvis.assistant.home.HomeError
import com.jarvis.assistant.home.HomeEventSource
import com.jarvis.assistant.home.HomeProviderId
import com.jarvis.assistant.home.HomeResult
import com.jarvis.assistant.home.HomeState
import com.jarvis.assistant.home.HomeStateChange
import com.jarvis.assistant.home.net.HomeHttpTransport
import com.jarvis.assistant.home.net.HomeTransportException
import com.jarvis.assistant.home.net.HomeWebSocket
import com.jarvis.assistant.home.net.HttpResponse
import com.jarvis.assistant.mcp.HostClass
import com.jarvis.assistant.mcp.McpServerKind
import com.jarvis.assistant.mcp.McpUrlPolicy
import com.jarvis.assistant.mcp.UrlPolicyResult
import com.jarvis.assistant.mcp.UrlRejection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

/**
 * The Home Assistant [HomeBackend].
 *
 * All provider-specific shapes (states, registries, service names) stay inside
 * `home/providers/ha/`; the provider-agnostic core only ever sees a
 * [HomeDevice]/[HomeState]/[HomeActionOutcome]. The transport and websocket are
 * injected seams, so the whole backend is JVM-testable with in-memory fakes.
 *
 * URL policy reuses [McpUrlPolicy] under the `LAN` reachability class (a
 * loopback host is validated under `LOCAL`), and cleartext is rejected outright:
 * HA must be reached over `https`/`wss` because the app runs with
 * `usesCleartextTraffic="false"`.
 *
 * Auth is a bearer token on every call: a missing token is
 * [HomeError.NOT_CONFIGURED], a 401/403 is [HomeError.AUTH]. Every failure is
 * typed; [CancellationException] is always rethrown for barge-in.
 *
 * A changed `baseUrl`/`token` is read through the live lambdas, but the
 * process-scoped client provider ([HomeAssistantClientProvider]) does not
 * rebuild, so re-pointing HA at a new host takes a service restart in practice.
 *
 * @param nowMs clock for [HomeState.atMs]; injectable for deterministic tests.
 */
class HomeAssistantBackend(
    private val transport: HomeHttpTransport,
    private val webSocket: HomeWebSocket,
    private val baseUrl: () -> String,
    private val token: () -> String?,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * The DEDICATED socket for the push event stream. It must NOT be
     * [webSocket] (the discovery socket): OkHttp's transport holds one socket
     * and `connect()` closes the previous one, so a shared socket would tear
     * one of the two down. Null disables push (poll-only degradation).
     */
    private val eventSocket: HomeWebSocket? = null,
) : HomeBackend, HomeEventSource {

    override val provider: HomeProviderId = HomeProviderId.HOME_ASSISTANT

    private val mapper = HaCapabilityMapper()
    private val enricher = HaRoomEnricher(webSocket, json)

    private val eventStream: HaEventStream? = eventSocket?.let { socket ->
        HaEventStream(
            webSocket = socket,
            baseUrl = baseUrl,
            token = token,
            json = json,
            nowMs = nowMs,
        )
    }

    /**
     * Push state changes for the curated [keys]. Empty [keys] means "curate
     * nothing" and yields an empty flow (no socket is opened), so the awareness
     * lane never streams the whole home by accident.
     */
    override fun events(keys: Set<HomeDeviceKey>): Flow<HomeStateChange> =
        if (keys.isEmpty()) emptyFlow() else (eventStream?.events(keys) ?: emptyFlow())

    override suspend fun discover(): HomeResult<List<HomeDevice>> =
        when (val prepared = preparedConnection()) {
            is HomeResult.Err -> prepared
            is HomeResult.Ok -> discoverWith(prepared.value)
        }

    override suspend fun readState(keys: List<HomeDeviceKey>): HomeResult<List<HomeState>> {
        if (keys.isEmpty()) return HomeResult.Ok(emptyList())
        return when (val prepared = preparedConnection()) {
            is HomeResult.Err -> prepared
            is HomeResult.Ok -> readStateWith(prepared.value, keys)
        }
    }

    override suspend fun apply(action: HomeAction): HomeResult<HomeActionOutcome> =
        when (val prepared = preparedConnection()) {
            is HomeResult.Err -> prepared
            is HomeResult.Ok -> applyWith(prepared.value, action)
        }

    // ------------------------------------------------------------------
    // Discovery.
    // ------------------------------------------------------------------

    private suspend fun discoverWith(connection: Connection): HomeResult<List<HomeDevice>> =
        when (val response = request(connection, STATES_PATH)) {
            is HomeResult.Err -> response
            is HomeResult.Ok -> {
                val entities = HaPayloads.parseStates(response.value.body, json)
                val rooms = roomIndex(connection)
                HomeResult.Ok(entities.mapNotNull { toDevice(it, rooms) })
            }
        }

    private fun toDevice(entity: HaEntity, rooms: Map<String, String>): HomeDevice? {
        val deviceClass = entity.attributeText("device_class")
        val kind = mapper.deviceKind(entity.domain, deviceClass)
        // Unknown domains are not controllable/observable; sensors are kept as
        // read-only observations, everything unmapped is dropped.
        if (kind == DeviceKind.UNKNOWN) return null
        val capabilities = HaCapabilities.capabilities(entity.domain, kind, entity.attributes, mapper)
        if (capabilities.isEmpty()) return null
        return HomeDevice(
            key = HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, entity.entityId),
            name = entity.attributeText("friendly_name") ?: entity.objectId,
            room = rooms[entity.entityId],
            kind = kind,
            capabilities = capabilities,
            verbs = HaCapabilities.verbs(capabilities),
        )
    }

    /** Room enrichment is best-effort: any failure yields an empty map, never an error. */
    private suspend fun roomIndex(connection: Connection): Map<String, String> = try {
        enricher.roomIndex(connection.base, connection.token).orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyMap()
    }

    // ------------------------------------------------------------------
    // State reads.
    // ------------------------------------------------------------------

    private suspend fun readStateWith(
        connection: Connection,
        keys: List<HomeDeviceKey>,
    ): HomeResult<List<HomeState>> = when (val response = request(connection, STATES_PATH)) {
        is HomeResult.Err -> response
        is HomeResult.Ok -> {
            val byId = HaPayloads.parseStates(response.value.body, json).associateBy { it.entityId }
            val now = nowMs()
            val states = keys.mapNotNull { key ->
                byId[key.nativeId]?.let { entity ->
                    HomeState(key, HaStateNormalizer.values(entity), entity.rawAttributes(), now)
                }
            }
            if (states.isEmpty()) {
                HomeResult.Err(HomeError.NOT_FOUND, "no state for the requested devices")
            } else {
                HomeResult.Ok(states)
            }
        }
    }

    // ------------------------------------------------------------------
    // Action application.
    // ------------------------------------------------------------------

    private suspend fun applyWith(connection: Connection, action: HomeAction): HomeResult<HomeActionOutcome> {
        val call = HaActionEncoder.encode(action)
            ?: return HomeResult.Err(HomeError.UNSUPPORTED, "action is not expressible as a HA service")
        val path = "/api/services/${call.domain}/${call.service}"
        return when (val response = request(connection, path, serviceBody(action.key.nativeId, call.data))) {
            is HomeResult.Err -> response
            is HomeResult.Ok -> {
                val accepted = response.value.status in 200..299
                val outcome = HomeActionOutcome(
                    accepted = accepted,
                    perCapability = listOf(CapabilityOutcome(action.capability, accepted)),
                    reconciled = null,
                )
                HomeResult.Ok(outcome)
            }
        }
    }

    private fun serviceBody(entityId: String, data: Map<String, JsonElement>): String = buildJsonObject {
        put("entity_id", entityId)
        data.forEach { (key, value) -> put(key, value) }
    }.toString()

    // ------------------------------------------------------------------
    // Connection prep + HTTP.
    // ------------------------------------------------------------------

    private fun preparedConnection(): HomeResult<Connection> {
        val base = when (val validated = validateBaseUrl(baseUrl())) {
            is HomeResult.Err -> return validated
            is HomeResult.Ok -> validated.value
        }
        val bearer = token()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return HomeResult.Err(HomeError.NOT_CONFIGURED, "home assistant token is not configured")
        return HomeResult.Ok(Connection(base, bearer))
    }

    /**
     * Reuses the MCP SSRF policy: `https` only, host under `LOCAL` when it is a
     * loopback literal else `LAN`. A cleartext or host-policy-rejected URL fails
     * closed before any request leaves the device.
     */
    private fun validateBaseUrl(raw: String): HomeResult<String> {
        val url = raw.trim().trimEnd('/')
        if (url.isEmpty()) return HomeResult.Err(HomeError.NOT_CONFIGURED, "home assistant url is not configured")
        val uri = runCatching { URI(url) }.getOrNull()
            ?: return HomeResult.Err(HomeError.UNSUPPORTED, "malformed home assistant url")
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            return HomeResult.Err(HomeError.UNSUPPORTED, "home assistant url must use https")
        }
        val host = uri.host?.removeSurrounding("[", "]")?.takeIf { it.isNotEmpty() }
            ?: return HomeResult.Err(HomeError.UNSUPPORTED, "home assistant url has no host")
        val kind = if (McpUrlPolicy.classifyHost(host) == HostClass.LOOPBACK) {
            McpServerKind.LOCAL
        } else {
            McpServerKind.LAN
        }
        return when (val policy = McpUrlPolicy.validate(kind, url)) {
            UrlPolicyResult.Allowed -> HomeResult.Ok(url)
            is UrlPolicyResult.Rejected ->
                HomeResult.Err(rejectionError(policy.reason), "home assistant url rejected: ${policy.reason.name}")
        }
    }

    private fun rejectionError(reason: UrlRejection): HomeError = when (reason) {
        UrlRejection.MALFORMED, UrlRejection.MISSING_HOST, UrlRejection.UNSUPPORTED_SCHEME ->
            HomeError.NOT_CONFIGURED

        UrlRejection.HTTPS_REQUIRED, UrlRejection.LOOPBACK_HOST, UrlRejection.PRIVATE_HOST,
        UrlRejection.LINK_LOCAL_HOST, UrlRejection.METADATA_HOST, UrlRejection.NON_LOOPBACK_HOST,
        UrlRejection.CROSS_ORIGIN_PRIVATE_HOST, UrlRejection.PUBLIC_HOST, UrlRejection.NON_PRIVATE_HOST,
        UrlRejection.CROSS_ORIGIN_LAN_HOST -> HomeError.UNSUPPORTED
    }

    private suspend fun request(
        connection: Connection,
        path: String,
        body: String? = null,
    ): HomeResult<HttpResponse> {
        val url = connection.base + path
        val headers = if (body == null) {
            authHeaders(connection)
        } else {
            authHeaders(connection) + mapOf("Content-Type" to JSON_CONTENT_TYPE)
        }
        val response = try {
            if (body == null) transport.get(url, headers) else transport.post(url, headers, body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HomeTransportException) {
            return HomeResult.Err(HomeError.UNREACHABLE, e.message)
        } catch (_: Exception) {
            return HomeResult.Err(HomeError.UNREACHABLE)
        }
        return classify(response)
    }

    private fun authHeaders(connection: Connection): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${connection.token}",
        "Accept" to JSON_CONTENT_TYPE,
    )

    private fun classify(response: HttpResponse): HomeResult<HttpResponse> = when {
        response.status == 401 || response.status == 403 -> HomeResult.Err(HomeError.AUTH)
        response.status !in 200..299 -> HomeResult.Err(HomeError.FAILED, "home assistant http ${response.status}")
        else -> HomeResult.Ok(response)
    }

    private data class Connection(val base: String, val token: String)

    private companion object {
        const val STATES_PATH = "/api/states"
        const val JSON_CONTENT_TYPE = "application/json"
    }
}
