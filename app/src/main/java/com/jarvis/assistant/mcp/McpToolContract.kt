package com.jarvis.assistant.mcp

import com.jarvis.assistant.tools.EMPTY_PARAMETER_SCHEMA
import com.jarvis.assistant.tools.ToolContract
import com.jarvis.assistant.tools.ToolResult
import com.jarvis.assistant.tools.ToolRisk
import com.jarvis.assistant.util.JsonOut
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import timber.log.Timber
import java.security.MessageDigest

/**
 * Adapts ONE discovered tool on ONE MCP server to the LLM-facing
 * [ToolContract].
 *
 * ## Namespace scheme (the collision rule)
 *
 * A discovered name is `mcp_<slug>_<sanitizedTool>`, where `<slug>` is the
 * first [SLUG_HEX_CHARS] hex characters of `SHA-256(serverId)` and
 * `<sanitizedTool>` maps every character outside `[A-Za-z0-9_-]` to `_`.
 *
 *  - The `mcp_` prefix is reserved for dynamic tools, so a namespaced name can
 *    NEVER equal a built-in tool name (`ToolAuthorizationTest` pins that no
 *    canonical key carries the prefix).
 *  - Two servers exposing the SAME tool name map to different slugs (server
 *    ids are distinct UUIDs), so the names stay disjoint across servers.
 *  - If the candidate exceeds [MAX_NAME_LENGTH], it is deterministically
 *    truncated to `candidate.take(MAX - HASH) + SHA-256(candidate).take(HASH)`.
 *    Names differing only beyond the truncation point therefore still differ
 *    in the appended hash.
 *
 * The reverse mapping is the instance itself: [serverId] and
 * [originalToolName] name the server and the unmodified tool exactly.
 *
 * All names are deterministic — the same `(serverId, toolName)` always yields
 * the same name, across processes and restarts.
 */
class McpToolContract(
    /** Stable identity of the owning [McpServerConfig]. */
    val serverId: String,
    private val descriptor: McpToolDescriptor,
    private val client: McpClient,
    override val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : ToolContract {

    /** The namespaced, LLM-safe name (see the class KDoc for the scheme). */
    override val name: String = namespacedName(serverId, descriptor.name)

    /** The server's own tool name — the reverse half of the mapping. */
    val originalToolName: String = descriptor.name

    override val description: String = McpBudget.capDescription(descriptor.description.orEmpty())

    /**
     * The descriptor's `inputSchema`, RAW, but validated at construction:
     * rejected when it exceeds [McpBudget.MAX_SCHEMA_BYTES] or is not a JSON
     * object. The failure degrades to [EMPTY_PARAMETER_SCHEMA] — the same
     * discipline [com.jarvis.assistant.tools.schema] uses — never a
     * hand-concatenated string.
     */
    override val parametersJson: String = acceptedSchema(descriptor.inputSchema, name)

    /** External tools are the untrusted class — see [ToolRisk.EXTERNAL]. */
    override val risk: ToolRisk = ToolRisk.EXTERNAL

    /** Returns the text body; the structured outcome is [executeResult]. */
    override suspend fun execute(arguments: String): String = executeResult(arguments).content

    /**
     * Runs `tools/call` against the owning server. A protocol/transport
     * failure is an honest error [ToolResult]; the adapter never throws except
     * for [CancellationException], which a barge-in must preserve.
     */
    override suspend fun executeResult(arguments: String): ToolResult {
        val call = try {
            client.callTool(originalToolName, arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "MCP tool %s call failed", name)
            return errorResult("MCP tool call failed")
        }
        return when (call) {
            is McpCall2.Ok ->
                ToolResult(McpBudget.capResult(call.result.text), isError = call.result.isError)

            is McpCall2.ProtocolError -> {
                // The server's free-text message is deliberately NOT surfaced.
                Timber.w("MCP tool %s rejected with protocol error %d", name, call.code)
                errorResult("MCP server rejected the call (${call.code})")
            }

            McpCall2.Unreachable -> errorResult("MCP server unreachable")
            McpCall2.BadResponse -> errorResult("MCP server returned an unusable response")
        }
    }

    private fun errorResult(message: String): ToolResult =
        ToolResult(JsonOut.error(message), isError = true)

    companion object {
        /** Reserved prefix; no built-in tool name may carry it. */
        const val NAMESPACE_PREFIX = "mcp_"

        /** LLM function-name ceiling (and the namespace's hard cap). */
        const val MAX_NAME_LENGTH = 64

        /** Registry default per-tool timeout; MCP calls reuse it. */
        const val DEFAULT_TIMEOUT_MS: Long = 15_000

        private const val SLUG_HEX_CHARS = 8
        private const val TRUNCATION_HASH_CHARS = 8
        private const val SANITIZED_REPLACEMENT = '_'
        private const val HEX = "0123456789abcdef"

        /**
         * The deterministic namespaced name for `(serverId, toolName)`. Pure
         * and side-effect-free; see the class KDoc for the collision rule.
         */
        fun namespacedName(serverId: String, toolName: String): String {
            val slug = hash(serverId).take(SLUG_HEX_CHARS)
            val candidate = NAMESPACE_PREFIX + slug + "_" + sanitize(toolName)
            if (candidate.length <= MAX_NAME_LENGTH) return candidate
            val suffix = hash(candidate).take(TRUNCATION_HASH_CHARS)
            return candidate.take(MAX_NAME_LENGTH - suffix.length) + suffix
        }

        private fun acceptedSchema(raw: String, name: String): String {
            if (raw.isBlank() || !McpBudget.isSchemaAcceptable(raw)) {
                Timber.w("MCP tool %s schema rejected; advertising empty parameters", name)
                return EMPTY_PARAMETER_SCHEMA
            }
            val parsed = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
            if (parsed !is JsonObject) {
                Timber.w("MCP tool %s schema is not a JSON object; advertising empty parameters", name)
                return EMPTY_PARAMETER_SCHEMA
            }
            return raw
        }

        private fun sanitize(raw: String): String = buildString(raw.length) {
            raw.forEach { ch -> append(if (isNameSafe(ch)) ch else SANITIZED_REPLACEMENT) }
        }

        private fun isNameSafe(ch: Char): Boolean =
            ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '-'

        private fun hash(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return buildString(digest.size * 2) {
                digest.forEach { byte ->
                    val unsigned = byte.toInt() and 0xFF
                    append(HEX[unsigned ushr 4])
                    append(HEX[unsigned and 0x0F])
                }
            }
        }
    }
}
