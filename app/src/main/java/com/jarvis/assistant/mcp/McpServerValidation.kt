package com.jarvis.assistant.mcp

/**
 * One configured server that failed [McpUrlPolicy] under its own kind.
 *
 * [name] is the human label ([McpServerConfig.displayName] when non-blank, else
 * [serverId]); [reason] is the coarse, host-free rejection. Neither echoes the
 * URL, which is deliberately not carried here so it can never be logged.
 */
data class Rejected(
    val serverId: String,
    val name: String,
    val reason: UrlRejection,
)

/**
 * Pure outcome of [McpServerValidation.validate].
 *
 * Deliberately distinct from [McpServerDecodeResult]: this is the policy-applied
 * view of the persisted blob, where a decodable list is further partitioned by
 * [McpUrlPolicy]. A decode failure is [Invalid], blank input is [Empty], and a
 * decodable list is [Decoded] — possibly with every server rejected.
 */
sealed interface McpServerValidationResult {
    /** No payload at all — nothing configured yet. */
    data object Empty : McpServerValidationResult

    /** The payload was present but not a decodable list; [reason] is log-safe. */
    data class Invalid(val reason: String) : McpServerValidationResult

    /**
     * A decodable list, partitioned by the URL policy. [valid] keeps the input
     * order; [rejected] keeps the input order too.
     */
    data class Decoded(
        val valid: List<McpServerConfig>,
        val rejected: List<Rejected>,
    ) : McpServerValidationResult
}

/**
 * The PURE decision half of MCP-server validation: decode the persisted blob and
 * partition each server by [McpUrlPolicy] under its kind.
 *
 * Android-free, I/O-free and logging-free by construction. The CALLER owns
 * logging and MUST throttle it — this object is re-invoked on every
 * [McpToolCatalog.snapshot] (every LLM pass), so an unthrottled caller would
 * WARN once per pass for a permanently-misconfigured server. See
 * [McpWarningThrottle] for the process-lifetime first-time gate.
 */
object McpServerValidation {

    /** Decode [raw] and apply the URL policy; never throws. */
    fun validate(raw: String): McpServerValidationResult =
        when (val decoded = McpServerConfigCodec.decode(raw)) {
            McpServerDecodeResult.Empty -> McpServerValidationResult.Empty

            is McpServerDecodeResult.Invalid ->
                McpServerValidationResult.Invalid(decoded.reason)

            is McpServerDecodeResult.Ok -> partition(decoded.servers)
        }

    private fun partition(servers: List<McpServerConfig>): McpServerValidationResult.Decoded {
        val valid = ArrayList<McpServerConfig>(servers.size)
        val rejected = ArrayList<Rejected>()
        for (server in servers) {
            when (val policy = McpUrlPolicy.validate(server.kind, server.url)) {
                UrlPolicyResult.Allowed -> valid += server

                is UrlPolicyResult.Rejected -> rejected += Rejected(
                    serverId = server.id,
                    name = server.displayName.takeIf { it.isNotBlank() } ?: server.id,
                    reason = policy.reason,
                )
            }
        }
        return McpServerValidationResult.Decoded(valid, rejected)
    }
}
