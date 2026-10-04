package com.jarvis.assistant.manage

import com.jarvis.assistant.mcp.McpAccess
import com.jarvis.assistant.mcp.McpServerConfig
import com.jarvis.assistant.mcp.McpServerKind
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.PipelineCall
import io.ktor.server.application.call
import io.ktor.server.request.host
import io.ktor.server.request.path
import io.ktor.server.request.port
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.intercept
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.util.pipeline.PipelineContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The frozen `/api/v1` REST surface over [ManagementCore] (R13 §14.4).
 *
 * The API is **config-only**: it never touches a tool, alarm, media transport or
 * any other action path, so it cannot become a non-voice action route. Every
 * `/api/v1` call except `POST /sessions` requires a valid session cookie or
 * `Authorization: Bearer <password>`; a successful authentication refreshes the
 * in-memory [ManagementActivation] idle timer. The static SPA is served without
 * auth (the login page itself must load).
 *
 * The three pure guards in [ManagementSecurity] run on EVERY request via the
 * root interceptor: the `Host` allow-list, cross-origin protection and the
 * fixed security headers. There are deliberately NO CORS headers.
 *
 * @param tlsFingerprint the certificate SHA-256 shown by `/status`.
 * @param allowedHosts the injected `Host` allow-list (loopback always; the LAN
 *   address is added by the caller when the LAN mode is bound).
 */
class ManagementRoutes(
    private val core: ManagementCore,
    private val auth: ManagementAuth,
    private val sessions: SessionStore,
    private val activation: ManagementActivation,
    private val assets: AssetSource,
    private val tlsFingerprint: String = "",
    private val allowedHosts: Set<String> = DEFAULT_ALLOWED_HOSTS,
    private val sessionTtlMs: Long = DEFAULT_SESSION_TTL_MS,
    private val apiVersion: Int = API_VERSION,
) {

    /** Installs the guards and the whole route tree onto [routing]. */
    fun install(routing: Routing) {
        routing.intercept(ApplicationCallPipeline.Plugins) { guard() }
        routing.route(API_PREFIX) {
            post("/sessions") { createSession() }
            delete("/sessions/current") { deleteCurrentSession() }
            get("/session") { call.respondJson(buildJsonObject { put("authenticated", true) }) }
            get("/status") { status() }
            route("/settings") {
                get { listSettings() }
                get("/{key}") { getSetting() }
                put("/{key}") { putSetting() }
            }
            route("/secrets") {
                get { listSecrets() }
                put("/{key}") { putSecret() }
                delete("/{key}") { deleteSecret() }
            }
            route("/mcp/servers") {
                get { listMcpServers() }
                post { createMcpServer() }
                put("/{id}") { updateMcpServer() }
                delete("/{id}") { deleteMcpServer() }
                put("/{id}/secret") { putMcpSecret() }
                delete("/{id}/secret") { deleteMcpSecret() }
            }
            post("/management/mode") { changeManagementMode() }
            post("/password/change") { changePassword() }
            post("/export") { exportConfig() }
            post("/import") { importConfig() }
        }
        // Static SPA: served without auth; unknown paths fall back to index.html
        // for client-side routing (but an /api path still answers JSON 404).
        routing.get("/{path...}") { serveAsset(call.request.path()) }
    }

    // ------------------------------------------------------------------
    // Request guards (Host, cross-origin, auth) — one entry point.
    // ------------------------------------------------------------------

    private suspend fun PipelineContext<Unit, PipelineCall>.guard() {
        val appCall = call
        applySecurityHeaders(appCall)
        val host = requestedHost(appCall)
        if (!ManagementSecurity.isAllowedHost(host, allowedHosts)) {
            appCall.respondError(HttpStatusCode.BadRequest, "bad_host", "host is not allowed")
            finish()
            return
        }
        val crossOriginAllowed = ManagementSecurity.isCrossOriginAllowed(
            method = appCall.request.local.method.value,
            secFetchSite = appCall.request.headers[ManagementSecurity.HEADER_SEC_FETCH_SITE],
            origin = appCall.request.headers[ManagementSecurity.HEADER_ORIGIN],
            host = host,
        )
        if (!crossOriginAllowed) {
            appCall.respondError(HttpStatusCode.Forbidden, "cross_origin", "cross-origin request rejected")
            finish()
            return
        }
        if (requiresAuth(appCall)) {
            when (val check = authenticate(appCall)) {
                AuthCheck.Ok -> Unit
                AuthCheck.Unauthorized -> {
                    appCall.respondError(HttpStatusCode.Unauthorized, "unauthorized", "authentication required")
                    finish()
                    return
                }

                is AuthCheck.LockedOut -> {
                    appCall.respondLockedOut(check.retryAfterSeconds)
                    finish()
                    return
                }
            }
        }
        proceed()
    }

    /** A session cookie OR a Bearer password authenticates; both refresh idle. */
    private fun authenticate(appCall: ApplicationCall): AuthCheck {
        if (sessions.verify(appCall.request.cookies[COOKIE_NAME])) {
            activation.touch()
            return AuthCheck.Ok
        }
        val token = bearerToken(appCall.request.headers[HttpHeaders.Authorization]) ?: return AuthCheck.Unauthorized
        return when (val result = auth.authenticate(sourceIp(appCall), token)) {
            ManagementAuthResult.Ok -> {
                activation.touch()
                AuthCheck.Ok
            }

            ManagementAuthResult.BadPassword -> AuthCheck.Unauthorized
            is ManagementAuthResult.LockedOut -> AuthCheck.LockedOut(result.retryAfterSeconds)
        }
    }

    private fun requiresAuth(appCall: ApplicationCall): Boolean {
        val path = appCall.request.path()
        if (!path.startsWith("$API_PREFIX/")) return false
        val isLogin = appCall.request.local.method == HttpMethod.Post && path.trimEnd('/') == "$API_PREFIX/sessions"
        return !isLogin
    }

    private fun sourceIp(appCall: ApplicationCall): String =
        appCall.request.local.remoteAddress.ifBlank { appCall.request.local.remoteHost }

    /**
     * The request authority for the `Host` allow-list. Over HTTP/2 the `Host`
     * header is absent (the authority rides in `:authority`), so fall back to
     * Ktor's resolved host + port rather than treating it as a missing host.
     */
    private fun requestedHost(appCall: ApplicationCall): String {
        appCall.request.headers[HttpHeaders.Host]?.let { if (it.isNotBlank()) return it }
        val port = appCall.request.port()
        return if (port > 0) "${appCall.request.host()}:$port" else appCall.request.host()
    }

    // ------------------------------------------------------------------
    // Sessions.
    // ------------------------------------------------------------------

    private suspend fun RoutingContext.createSession() {
        val body = call.jsonBody() ?: return call.respondBadRequest("invalid_body", "expected a JSON object")
        val password = body.string("password") ?: return call.respondBadRequest("missing_password", "password is required")
        when (val result = auth.authenticate(sourceIp(call), password.toCharArray())) {
            ManagementAuthResult.Ok -> {
                val id = sessions.issue(sessionTtlMs)
                call.response.headers.append(HttpHeaders.SetCookie, sessionCookie(id))
                call.respondText(
                    buildJsonObject { put("authenticated", true) }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.Created,
                )
            }

            ManagementAuthResult.BadPassword ->
                call.respondError(HttpStatusCode.Unauthorized, "bad_password", "invalid password")

            is ManagementAuthResult.LockedOut -> call.respondLockedOut(result.retryAfterSeconds)
        }
    }

    private suspend fun RoutingContext.deleteCurrentSession() {
        sessions.revoke(call.request.cookies[COOKIE_NAME])
        call.response.headers.append(HttpHeaders.SetCookie, expiredSessionCookie())
        call.respondNoContent()
    }

    // ------------------------------------------------------------------
    // Status / settings.
    // ------------------------------------------------------------------

    private suspend fun RoutingContext.status() {
        val status = core.status()
        call.respondJson(
            buildJsonObject {
                put("service", "jarvis")
                put("apiVersion", apiVersion)
                put("appVersion", status.appVersion)
                putJsonObject("management") {
                    put("mode", status.mode.id)
                    put("active", activation.isActive)
                    put("port", status.port)
                    put("idleTimeoutMs", status.idleTimeoutMs)
                }
                putJsonObject("tls") { put("sha256Fingerprint", tlsFingerprint) }
                putJsonArray("pendingPolicies") { status.pendingPolicies.forEach { add(JsonPrimitive(it.name)) } }
            },
        )
    }

    private suspend fun RoutingContext.listSettings() {
        val array = buildJsonArray {
            core.listSettings().forEach { setting ->
                add(
                    buildJsonObject {
                        put("key", setting.key)
                        put("category", setting.category)
                        put("type", setting.type.name)
                        put("policy", setting.policy.name)
                        put("essential", setting.essential)
                        put("secret", setting.secret)
                        if (!setting.secret) core.getSetting(setting.key)?.let { put("value", it.toJsonElement()) }
                    },
                )
            }
        }
        call.respondJson(array)
    }

    private suspend fun RoutingContext.getSetting() {
        val key = call.parameters["key"].orEmpty()
        val meta = core.setting(key)
        if (meta == null) return call.respondError(HttpStatusCode.NotFound, "unknown_setting", "no such setting")
        if (meta.secret) {
            return call.respondError(HttpStatusCode.Forbidden, "secret_setting", "secret values are write-only")
        }
        val value = core.getSetting(key)
            ?: return call.respondError(HttpStatusCode.NotFound, "unknown_setting", "no value")
        call.respondJson(
            buildJsonObject {
                put("key", key)
                put("type", meta.type.name)
                put("value", value.toJsonElement())
            },
        )
    }

    private suspend fun RoutingContext.putSetting() {
        val key = call.parameters["key"].orEmpty()
        val meta = core.setting(key)
        if (meta == null || meta.secret) {
            return call.respondBadRequest("unwritable_setting", "unknown or write-only setting")
        }
        val body = call.jsonBody() ?: return call.respondBadRequest("invalid_body", "expected a JSON object")
        val element = body["value"] ?: return call.respondBadRequest("missing_value", "value is required")
        val value = ManagedValue.fromJsonElement(meta.type, element)
            ?: return call.respondBadRequest("invalid_value", "value does not match the setting type")
        val policy = core.setSetting(key, value)
            ?: return call.respondBadRequest("invalid_value", "value was rejected")
        call.respondJson(buildJsonObject { put("policy", policy.name) })
    }

    // ------------------------------------------------------------------
    // Secrets (write-only).
    // ------------------------------------------------------------------

    private suspend fun RoutingContext.listSecrets() {
        val array = buildJsonArray {
            core.listSecrets().forEach { secret ->
                add(
                    buildJsonObject {
                        put("key", secret.key)
                        put("set", secret.set)
                    },
                )
            }
        }
        call.respondJson(array)
    }

    private suspend fun RoutingContext.putSecret() {
        val key = call.parameters["key"].orEmpty()
        val body = call.jsonBody() ?: return call.respondBadRequest("invalid_body", "expected a JSON object")
        val value = body.string("value") ?: return call.respondBadRequest("missing_value", "value is required")
        if (!core.setSecret(key, value)) {
            return call.respondBadRequest("unknown_secret", "unknown or non-secret key")
        }
        call.respondJson(buildJsonObject { put("set", true) })
    }

    private suspend fun RoutingContext.deleteSecret() {
        val key = call.parameters["key"].orEmpty()
        if (!core.clearSecret(key)) {
            return call.respondError(HttpStatusCode.NotFound, "unknown_secret", "unknown or non-secret key")
        }
        call.respondNoContent()
    }

    // ------------------------------------------------------------------
    // MCP servers (typed sub-resource, never a raw blob write).
    // ------------------------------------------------------------------

    private suspend fun RoutingContext.listMcpServers() {
        val array = buildJsonArray { core.listMcpServers().forEach { add(mcpJson(it)) } }
        call.respondJson(array)
    }

    private suspend fun RoutingContext.createMcpServer() {
        val body = call.jsonBody() ?: return call.respondBadRequest("invalid_body", "expected a JSON object")
        val server = McpServerConfig.create(
            displayName = body.string("displayName").orEmpty(),
            kind = parseMcpKind(body.string("kind"), McpServerKind.REMOTE),
            url = body.string("url").orEmpty(),
            enabled = body.boolean("enabled") ?: true,
            access = parseMcpAccess(body.string("access"), McpAccess.READ),
            authHeaderName = body.string("authHeaderName").orEmpty(),
            existing = core.listMcpServers(),
        )
        core.addMcpServer(server)
        call.respondJson(mcpJson(server), HttpStatusCode.Created)
    }

    private suspend fun RoutingContext.updateMcpServer() {
        val id = call.parameters["id"].orEmpty()
        val existing = core.listMcpServers().firstOrNull { it.id == id }
            ?: return call.respondError(HttpStatusCode.NotFound, "unknown_server", "no such MCP server")
        val body = call.jsonBody() ?: return call.respondBadRequest("invalid_body", "expected a JSON object")
        val updated = existing.copy(
            displayName = body.string("displayName") ?: existing.displayName,
            kind = body.string("kind")?.let { parseMcpKind(it, existing.kind) } ?: existing.kind,
            url = body.string("url") ?: existing.url,
            enabled = body.boolean("enabled") ?: existing.enabled,
            access = body.string("access")?.let { parseMcpAccess(it, existing.access) } ?: existing.access,
            authHeaderName = body.string("authHeaderName") ?: existing.authHeaderName,
        )
        if (!core.updateMcpServer(updated)) {
            return call.respondError(HttpStatusCode.NotFound, "unknown_server", "no such MCP server")
        }
        call.respondJson(mcpJson(updated))
    }

    private suspend fun RoutingContext.deleteMcpServer() {
        val id = call.parameters["id"].orEmpty()
        if (!core.deleteMcpServer(id)) {
            return call.respondError(HttpStatusCode.NotFound, "unknown_server", "no such MCP server")
        }
        call.respondNoContent()
    }

    private suspend fun RoutingContext.putMcpSecret() {
        val id = call.parameters["id"].orEmpty()
        if (core.listMcpServers().none { it.id == id }) {
            return call.respondError(HttpStatusCode.NotFound, "unknown_server", "no such MCP server")
        }
        val body = call.jsonBody() ?: return call.respondBadRequest("invalid_body", "expected a JSON object")
        val value = body.string("value") ?: return call.respondBadRequest("missing_value", "value is required")
        core.setMcpSecret(id, value)
        call.respondJson(buildJsonObject { put("set", true) })
    }

    private suspend fun RoutingContext.deleteMcpSecret() {
        core.clearMcpSecret(call.parameters["id"].orEmpty())
        call.respondNoContent()
    }

    // ------------------------------------------------------------------
    // Mode, password, export/import.
    // ------------------------------------------------------------------

    private suspend fun RoutingContext.changeManagementMode() {
        val body = call.jsonBody() ?: return call.respondBadRequest("invalid_body", "expected a JSON object")
        val rawMode = body.string("mode") ?: return call.respondBadRequest("missing_mode", "mode is required")
        val mode = ManagementMode.entries.firstOrNull { it.id == rawMode.trim().lowercase() }
            ?: return call.respondBadRequest("invalid_mode", "unknown management mode")
        core.setSetting("managementMode", ManagedValue.EnumValue(mode.id))
        body.long("idleTimeoutMs")?.let { core.setSetting("managementIdleTimeoutMs", ManagedValue.LongValue(it)) }
        activation.start(mode, explicitEnable = true)
        call.respondJson(
            buildJsonObject {
                put("mode", mode.id)
                put("active", activation.isActive)
            },
        )
    }

    private suspend fun RoutingContext.changePassword() {
        val body = call.jsonBody() ?: return call.respondBadRequest("invalid_body", "expected a JSON object")
        val current = body.string("current") ?: return call.respondBadRequest("missing_current", "current is required")
        val next = body.string("next")
        if (next.isNullOrEmpty()) {
            return call.respondBadRequest("invalid_password", "next password must not be empty")
        }
        when (val result = auth.changePassword(sourceIp(call), current.toCharArray(), next.toCharArray())) {
            ManagementAuthResult.Ok -> {
                sessions.revokeAll()
                call.respondNoContent()
            }

            ManagementAuthResult.BadPassword ->
                call.respondError(HttpStatusCode.Unauthorized, "bad_password", "invalid current password")

            is ManagementAuthResult.LockedOut -> call.respondLockedOut(result.retryAfterSeconds)
        }
    }

    private suspend fun RoutingContext.exportConfig() {
        val body = call.jsonBody() ?: return call.respondBadRequest("invalid_body", "expected a JSON object")
        val passphrase = body.string("passphrase")
        if (passphrase.isNullOrEmpty()) {
            return call.respondBadRequest("missing_passphrase", "passphrase is required")
        }
        val includeSecrets = body.boolean("includeSecrets") ?: false
        val artifact = core.export(passphrase.toCharArray(), includeSecrets)
        call.respondBytes(artifact.toByteArray(Charsets.UTF_8), ContentType.Application.OctetStream)
    }

    private suspend fun RoutingContext.importConfig() {
        val body = call.jsonBody() ?: return call.respondBadRequest("invalid_body", "expected a JSON object")
        val passphrase = body.string("passphrase")
        val data = body.string("data")
        if (passphrase.isNullOrEmpty() || data.isNullOrEmpty()) {
            return call.respondBadRequest("missing_fields", "passphrase and data are required")
        }
        val result = core.import(data, passphrase.toCharArray())
        call.respondJson(
            buildJsonObject {
                put("applied", result.applied)
                put("skipped", result.skipped)
                putJsonArray("errors") { result.errors.forEach { add(JsonPrimitive(it)) } }
            },
        )
    }

    // ------------------------------------------------------------------
    // Static SPA.
    // ------------------------------------------------------------------

    private suspend fun RoutingContext.serveAsset(rawPath: String) {
        val path = normalizeAssetPath(rawPath)
        if (path.startsWith("$API_PREFIX/") || path == API_PREFIX) {
            return call.respondError(HttpStatusCode.NotFound, "not_found", "unknown endpoint")
        }
        val bytes = assets.read(path) ?: assets.read(INDEX_HTML)
        if (bytes == null) return call.respondError(HttpStatusCode.NotFound, "not_found", "asset not found")
        val contentType = contentTypeFor(path)
        if (contentType == ContentType.Text.Html) applySecurityHeaders(call, html = true)
        call.respondBytes(bytes, contentType, HttpStatusCode.OK)
    }

    private fun mcpJson(server: McpServerConfig): JsonObject = buildJsonObject {
        put("id", server.id)
        put("displayName", server.displayName)
        put("kind", server.kind.name)
        put("url", server.url)
        put("enabled", server.enabled)
        put("access", server.access.name)
        put("authHeaderName", server.authHeaderName)
        put("order", server.order)
        put("secretSet", core.mcpSecretSet(server.id))
    }

    private fun parseMcpKind(raw: String?, fallback: McpServerKind): McpServerKind =
        McpServerKind.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: fallback

    private fun parseMcpAccess(raw: String?, fallback: McpAccess): McpAccess =
        McpAccess.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: fallback

    private fun sessionCookie(id: String): String =
        "$COOKIE_NAME=$id; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=${sessionTtlMs / MILLIS_PER_SECOND}"

    private fun expiredSessionCookie(): String =
        "$COOKIE_NAME=; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=0"

    private fun applySecurityHeaders(appCall: ApplicationCall, html: Boolean = false) {
        ManagementSecurity.securityHeaders(html).forEach { (name, value) ->
            appCall.response.headers.append(name, value)
        }
    }

    private fun normalizeAssetPath(rawPath: String): String {
        val cleaned = rawPath.trim().substringBefore('?').substringBefore('#')
        if (cleaned.isEmpty() || cleaned == "/") return INDEX_HTML
        val withSlash = if (cleaned.startsWith("/")) cleaned else "/$cleaned"
        return if (".." in withSlash) INDEX_HTML else withSlash
    }

    private fun contentTypeFor(path: String): ContentType = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> ContentType.Text.Html
        "js", "mjs" -> ContentType.Application.JavaScript
        "css" -> ContentType.Text.CSS
        "json" -> ContentType.Application.Json
        "svg" -> ContentType.Image.SVG
        "png" -> ContentType.Image.PNG
        "jpg", "jpeg" -> ContentType.Image.JPEG
        else -> ContentType.Application.OctetStream
    }

    companion object {
        const val API_VERSION = 1
        const val API_PREFIX = "/api/v1"
        const val COOKIE_NAME = "jarvis_mgmt"
        const val INDEX_HTML = "/index.html"
        const val DEFAULT_SESSION_TTL_MS = 8L * 60L * 60L * 1000L

        /** Loopback hosts always allowed; the caller adds the LAN address. */
        val DEFAULT_ALLOWED_HOSTS: Set<String> = setOf("localhost", "127.0.0.1", "[::1]")

        private const val MILLIS_PER_SECOND = 1000L
    }
}

/** The internal auth verdict the guard turns into an HTTP status. */
private sealed interface AuthCheck {
    data object Ok : AuthCheck
    data object Unauthorized : AuthCheck
    data class LockedOut(val retryAfterSeconds: Long) : AuthCheck
}

private val json = Json { ignoreUnknownKeys = true }

/**
 * Parse the request body as a JSON object, or null on a blank/malformed body.
 * The catch intentionally maps every parse failure to a client error (400).
 */
@Suppress("SwallowedException")
private suspend fun ApplicationCall.jsonBody(): JsonObject? = try {
    val text = receiveText()
    if (text.isBlank()) null else json.parseToJsonElement(text) as? JsonObject
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: Exception) {
    null
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

private fun bearerToken(headerValue: String?): CharArray? {
    val trimmed = headerValue?.trim() ?: return null
    if (!trimmed.startsWith("Bearer ", ignoreCase = true)) return null
    val token = trimmed.substring("Bearer ".length).trim()
    return if (token.isEmpty()) null else token.toCharArray()
}

private suspend fun ApplicationCall.respondJson(element: JsonElement, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(element.toString(), ContentType.Application.Json, status)
}

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, message: String) {
    respondJson(
        buildJsonObject {
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        },
        status,
    )
}

private suspend fun ApplicationCall.respondBadRequest(code: String, message: String) {
    respondError(HttpStatusCode.BadRequest, code, message)
}

private suspend fun ApplicationCall.respondLockedOut(retryAfterSeconds: Long) {
    response.header("Retry-After", retryAfterSeconds.toString())
    respondError(HttpStatusCode.Locked, "locked_out", "too many failed authentication attempts")
}

private suspend fun ApplicationCall.respondNoContent() {
    respondText("", ContentType.Text.Plain, HttpStatusCode.NoContent)
}
