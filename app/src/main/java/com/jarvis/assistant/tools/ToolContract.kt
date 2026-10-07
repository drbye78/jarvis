package com.jarvis.assistant.tools

import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.model.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * A tool exposed to the LLM. `parametersJson` is a raw JSON-schema string
 * (parsed into an object by the registry for the wire layer).
 */
interface ToolContract {
    val name: String
    val description: String
    val parametersJson: String

    /**
     * How dangerous this tool is (see [ToolRisk]). DELIBERATELY ABSTRACT — a
     * newly registered tool is a COMPILE error until it declares its class, so
     * no tool can slip into the registry unclassified. There is NO default:
     * defaulting everything to READ_ONLY would erase the boundary (the same
     * no-`else` idiom as the LLM-provider `when` in the graph).
     */
    val risk: ToolRisk

    /**
     * Optional per-tool execution timeout override (MUSIC lane). Null →
     * the registry default. `playMusic` legitimately needs ~30 s: cold-start
     * the player, wait for its media session, playFromSearch, verify.
     */
    val timeoutMs: Long? get() = null

    /**
     * OPT-IN failed-retry dedupe. When true, [ToolRegistry.executeResult]
     * caches a FAILED result ([ToolResult.isError] = true) for the current
     * turn keyed by `(name, arguments)`; an identical retry returns the cached
     * result WITHOUT re-executing. Defaults to false deliberately: the model
     * can legitimately retry a transient failure (a GPS fix that is warming
     * up), so a global guard would suppress real recovery. Only tools whose
     * repeat of the exact same call is provably pointless opt in — currently
     * the music cascade, which can exhaust every strategy and still be
     * retried until the loop aborts.
     */
    val deduplicateFailedRetries: Boolean get() = false

    suspend fun execute(arguments: String): String

    /**
     * Structured execution seam. The default preserves the historical
     * contract — a tool returns a [String] body and is classified
     * non-error — so no existing tool changes behavior. Tools that carry
     * their own error classification (the external MCP adapter) override it
     * with the honest [ToolResult]. [ToolRegistry] invokes THIS method, so a
     * tool-specific error flag reaches telemetry instead of being flattened
     * to a success.
     */
    suspend fun executeResult(arguments: String): ToolResult = ToolResult(execute(arguments))
}

/**
 * Structured outcome of a tool execution. Classification is carried by
 * [isError] instead of sniffing the content for an `"error"` substring —
 * legitimate payloads may legitimately contain that key.
 */
data class ToolResult(val content: String, val isError: Boolean = false)

/** Tool facade used by the session layer (interface for JVM testing). */
interface ToolExecutor {
    fun getToolDefinitions(): List<ToolDefinition>
    suspend fun executeResult(call: FunctionCall): ToolResult

    /**
     * Bind the provenance of the turn [sessionId] to this executor, or clear
     * it ([context] = null). Called at turn start/end by the session layer —
     * the model can NEVER pass this as a tool argument, which is the whole
     * point of the boundary.
     *
     * DELIBERATELY ABSTRACT (no no-op default): a default would let an
     * executor silently enforce nothing. Every implementation must state its
     * behavior; a test fake that genuinely does not enforce still has to say
     * so explicitly.
     */
    fun setAuthorizationContext(sessionId: Int, context: TurnAuthorization?)
}

/**
 * Registry + executor. Unknown tools, exceptions, and hangs are all converted
 * into JSON error results — a tool can never crash or wedge the turn.
 */
class ToolRegistry(
    private val tools: List<ToolContract>,
    private val perToolTimeoutMs: Long = 15_000,
    /**
     * Telemetry seam — observes every COMPLETED execution
     * (success, tool failure or timeout; not barge-in cancellations, which
     * rethrow). Receives (call, result, latencyMs). Fire-and-forget on the
     * observer's side; a throwing observer is logged and ignored by the
     * registry (telemetry must never break a turn).
     */
    private val onExecuted: (suspend (FunctionCall, ToolResult, Long) -> Unit)? = null,
    /**
     * Optional per-call projection of dynamically-discovered tools (the MCP
     * bridge). [tools] stays the STATIC, classified, cross-checked surface;
     * this supplies the untrusted additions. The supplier is re-invoked on
     * EVERY [available]/[getToolDefinitions]/[executeResult] call, so a tool
     * that appeared between LLM passes is visible on the next pass without
     * rebuilding the registry. A discovered tool may also disappear between
     * passes — accepted for P1 (the model gets an honest "unknown function"
     * on a stale call). Default null → current behavior for every existing
     * caller. Deliberately NOT cross-checked against [ToolRisks] and never
     * allowed to affect the static authorization binding.
     */
    private val dynamicTools: (() -> List<ToolContract>)? = null,
    /**
     * Single-use confirmation store for [ToolRisk.EXTERNAL_WRITE] tools. ONE
     * instance is shared between this registry and the session turn hooks
     * ([com.jarvis.assistant.session.TurnRunner]), because a confirmation is
     * only meaningful when the challenge arming and the affirmative utterance
     * are recorded against the same ordinal/turn state. Default null → every
     * EXTERNAL_WRITE call fails closed (the tests that exercise the class
     * without a store must opt in explicitly).
     */
    private val writeConfirmation: WriteConfirmation? = null,
) {

    /**
     * The turn currently authorized to run tools. Held with the originating
     * session id so a superseded turn's end-of-turn clear cannot wipe the
     * context a newer turn has just bound (the turn hand-off races).
     */
    @Volatile
    private var authorizedTurn: AuthorizedTurn? = null

    /**
     * Per-turn cache of FAILED results for tools that opted into
     * [ToolContract.deduplicateFailedRetries]. Keyed by `(name, arguments)`.
     * Only failures are remembered (a success is never cached — a repeated
     * call that succeeded the first time must still be free to run again).
     * Cleared at every new turn bind in [setAuthorizationContext].
     */
    private val failedRetries = ConcurrentHashMap<String, ToolResult>()

    private data class AuthorizedTurn(val sessionId: Int, val context: TurnAuthorization)

    init {
        // Canonical-risk agreement check: the abstract `risk` property stops an
        // UNCLASSIFIED tool, but not a silently RECLASSIFIED one. Every tool
        // whose name is in the canonical table must match it, so drift is loud
        // at construction instead of quiet at execution.
        tools.forEach { tool ->
            val expected = ToolRisks.byName[tool.name] ?: return@forEach
            check(tool.risk == expected) {
                "Tool '${tool.name}' declares ${tool.risk} but ToolRisks says $expected"
            }
        }
    }

    /**
     * Bind or clear the active turn's provenance. Clearing only takes effect
     * when [sessionId] is still the bound turn; setting always overwrites.
     */
    fun setAuthorizationContext(sessionId: Int, context: TurnAuthorization?) {
        // A non-null bind marks a NEW turn (TurnRunner binds voice() at turn
        // start and rebinds once the utterance is known, both BEFORE any tool
        // runs). The failed-retry cache is per-turn, so it is cleared here.
        if (context != null) failedRetries.clear()
        authorizedTurn = if (context == null) {
            authorizedTurn?.takeUnless { it.sessionId == sessionId }
        } else {
            AuthorizedTurn(sessionId, context)
        }
    }

    /**
     * The tools visible on THIS call: the static surface plus a fresh
     * projection of the dynamic supplier. Called once per lookup so a
     * discovery that landed between passes is picked up without a rebuild.
     */
    private fun snapshot(): List<ToolContract> =
        tools + (dynamicTools?.invoke() ?: emptyList())

    fun available(): List<ToolContract> = snapshot()

    fun getToolDefinitions(): List<ToolDefinition> = snapshot().map { tool ->
        ToolDefinition(
            name = tool.name,
            description = tool.description,
            parameters = parseParameters(tool),
        )
    }

    private fun parseParameters(tool: ToolContract) = try {
        Json.parseToJsonElement(tool.parametersJson).jsonObject
    } catch (e: Exception) {
        Timber.e(e, "Tool %s has invalid parameters schema", tool.name)
        buildJsonObject { }
    }

    /**
     * Classified execution: success → isError=false; execution exception
     * or timeout → isError=true with JSON error content. The old
     * `result.contains("\"error\"")` substring sniffing is gone — a payload
     * that merely mentions "error" is no longer misclassified.
     *
     * [CancellationException] is RETHROWN, never converted to an
     * error result. Barge-in cancels the session mid tool call; swallowing
     * that cancellation here would break structured concurrency (the turn
     * would keep running and persist a bogus tool error instead of being
     * cancelled). [TimeoutCancellationException] is re-caught first: it is
     * a CancellationException subclass, but the per-tool timeout is OURS.
     *
     * AUTHORIZATION (single enforcement point): every tool call passes through
     * here, so this is where the LLM/tool boundary lives. A denied call is an
     * honest error [ToolResult] — never a throw, never a silent success — and
     * [ToolContract.execute] is NOT invoked. The model-facing text is
     * content-free; the real reason is logged (tool name + rule, no payload).
     */
    suspend fun executeResult(call: FunctionCall): ToolResult {
        val tool = snapshot().find { it.name == call.name }
            ?: return ToolResult(
                buildJsonObject {
                    put("error", "Unknown function: ${call.name}")
                }.toString(),
                isError = true,
            )
        // OPT-IN failed-retry guard key. The lookup happens AFTER authorization
        // and the confirmation gate so a cached failure can never bypass either
        // boundary; only the tool exercise is skipped.
        val dedupeKey = if (tool.deduplicateFailedRetries) {
            call.name + "\u0000" + call.arguments
        } else {
            null
        }
        val decision = ToolAuthorization.decide(call.name, tool.risk, authorizedTurn?.context)
        if (decision is AuthorizationDecision.Deny) {
            Timber.w("Tool %s denied by authorization (%s)", call.name, decision.reason)
            return ToolResult(
                buildJsonObject {
                    put("error", "Action not permitted in this context")
                }.toString(),
                isError = true,
            )
        }
        // SECOND ENFORCEMENT CLAUSE for external WRITE tools: a bound voice
        // turn is necessary but NOT sufficient. The exact call (server + tool +
        // canonical arguments) must additionally be confirmed by an explicit
        // affirmative on the immediately-next user turn. This is still BEFORE
        // the timeout/execute block, so an unconfirmed write never reaches the
        // third-party server and no telemetry fires for it.
        confirmationGate(tool, call)?.let { return it }
        // A tool that opted in and already failed on this exact
        // (name, arguments) in this turn returns the cached failure instead of
        // re-running. The model sees only `content`, so a success-shaped
        // failure would otherwise be retried until the loop aborts (the music
        // cascade exhausting every strategy); this makes it final for the turn.
        if (dedupeKey != null) {
            failedRetries[dedupeKey]?.let { return it }
        }
        val timeout = tool.timeoutMs ?: perToolTimeoutMs
        val startedAt = System.nanoTime()
        val result = try {
            // executeResult (not execute) so a tool carrying its own error
            // classification — the external MCP adapter — reaches telemetry
            // honestly. The default wraps the historical String contract.
            withTimeout(timeout) { tool.executeResult(call.arguments) }
        } catch (e: TimeoutCancellationException) {
            Timber.w("Tool %s timed out after %d ms", call.name, timeout)
            ToolResult("""{"error":"Tool timed out"}""", isError = true)
        } catch (e: CancellationException) {
            throw e // barge-in / shutdown — the session must observe it
        } catch (e: Exception) {
            Timber.e(e, "Tool %s failed", call.name)
            ToolResult(
                buildJsonObject {
                    put("error", "Tool execution failed: ${e.message}")
                }.toString(),
                isError = true,
            )
        }
        // Record AFTER the outcome is known, for both
        // success and failure; latency includes the tool's own timeout wait.
        val latencyMs = (System.nanoTime() - startedAt) / 1_000_000
        val observer = onExecuted
        if (observer != null) {
            try {
                observer(call, result, latencyMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Tool telemetry observer failed (ignored)")
            }
        }
        // Remember ONLY failures: a success must never be cached, so a
        // legitimate repeat (e.g. play a second track) still executes.
        if (dedupeKey != null && result.isError) {
            failedRetries[dedupeKey] = result
        }
        return result
    }

    /**
     * The confirmation clause shared by every [ConfirmedTool]: an external
     * [ToolRisk.EXTERNAL_WRITE] tool and a local [ToolRisk.CONTROLLED] action
     * both require an explicit affirmative on the immediately-next turn.
     * Returns null when the call may proceed (not a confirming tool, or a
     * consumed confirmation) and the model-facing [ToolResult] otherwise.
     *
     * Fail closed at every ambiguity: a tool that declares
     * [ToolRisk.requiresConfirmation] but does NOT implement [ConfirmedTool]
     * is an honest error (a mis-declared write must never run), a null key
     * (malformed/non-object arguments) is an honest error, and an absent store
     * is an honest error. The [WriteGate.NeedsConfirmation] result is NOT an
     * error — it is the structured instruction the model needs to ask the
     * user — and it never invokes the tool.
     *
     * EXTERNAL_WRITE behavior is unchanged: its risk sets
     * `requiresConfirmation = true`, its [ConfirmedWriteTool] mapping supplies
     * the server/tool identity, and the emitted challenge is byte-for-byte the
     * same JSON. The gate additionally triggers for a [ToolRisk.CONTROLLED]
     * tool that opts in by implementing [ConfirmedTool].
     */
    private fun confirmationGate(tool: ToolContract, call: FunctionCall): ToolResult? {
        val confirmed = tool as? ConfirmedTool
        // A tool opts into the next-turn affirmative EITHER by declaring the
        // write risk (EXTERNAL_WRITE) OR by implementing ConfirmedTool as a
        // CONTROLLED local action. The MCP adapter structurally implements the
        // MCP-shaped marker for BOTH of its access classes, so an EXTERNAL
        // (read) tool must NOT be swept in by `is ConfirmedTool` alone —
        // requiring `requiresConfirmation` or the CONTROLLED risk keeps every
        // external READ behavior identical.
        val needsConfirmation = tool.risk.requiresConfirmation ||
            (tool.risk == ToolRisk.CONTROLLED && confirmed != null)
        if (!needsConfirmation) return null
        if (confirmed == null) {
            // A tool that claims a write side effect but cannot be bound to a
            // domain+action identity cannot be confirmed — refuse to run it.
            Timber.w("Tool %s requires confirmation without ConfirmedTool (fail closed)", call.name)
            return confirmationDeniedResult()
        }
        val key = WriteBinding.canonicalKey(
            confirmed.confirmationDomain,
            confirmed.confirmationAction,
            call.arguments,
        )
        if (key == null) {
            // Arguments are not a well-formed JSON object: the exact call
            // cannot be bound to a challenge, so it can never be confirmed.
            Timber.w("Tool %s confirmation arguments are not a JSON object (fail closed)", call.name)
            return confirmationDeniedResult()
        }
        return when (writeConfirmation?.gate(key, authorizedTurn?.sessionId)) {
            WriteGate.Confirmed -> null
            WriteGate.NeedsConfirmation -> needsConfirmationResult(confirmed, call)
            WriteGate.Denied, null -> confirmationDeniedResult()
        }
    }

    /**
     * The structured, content-free challenge handed BACK to the model. Echoes
     * only the model's own parsed arguments and the domain/action identity — no
     * secret, URL or server payload. `isError = false`: this is a normal tool
     * result, not a failure. The `server`/`tool` field names are deliberately
     * kept stable so the external(MCP)-write challenge is byte-for-byte
     * unchanged; for a local [ToolRisk.CONTROLLED] action they carry the
     * domain/action tokens.
     */
    private fun needsConfirmationResult(tool: ConfirmedTool, call: FunctionCall): ToolResult {
        val arguments = Json.parseToJsonElement(call.arguments)
        val content = buildJsonObject {
            put("outcome", "needs_confirmation")
            put("server", tool.confirmationDomain)
            put("tool", tool.confirmationAction)
            put("arguments", arguments)
            put("instruction", NEEDS_CONFIRMATION_INSTRUCTION)
        }.toString()
        return ToolResult(content, isError = false)
    }

    /** Honest, content-free denial for every failed-close confirmation path. */
    private fun confirmationDeniedResult(): ToolResult {
        Timber.w("External write denied: no confirmed challenge for this turn")
        return ToolResult(
            buildJsonObject {
                put("error", "Action not permitted in this context")
            }.toString(),
            isError = true,
        )
    }
}

/** Shared argument parsing helper for tools. */
object ToolArgs {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(arguments: String): kotlinx.serialization.json.JsonObject? = try {
        json.parseToJsonElement(arguments).jsonObject
    } catch (e: Exception) {
        null
    }
}

/** Receiver-style argument accessors used by every tool: `args.string("key")`. */
fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

fun kotlinx.serialization.json.JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitive?.contentOrNull?.let { content ->
        // LLMs occasionally emit integer fields in float form ("50.0").
        // toIntOrNull() rejected those outright, so SetVolumeTool et al.
        // answered "Missing required parameter" for a value the model DID
        // supply. Accept float-form numbers by parsing through Double.
        content.toIntOrNull() ?: content.toDoubleOrNull()?.toInt()
    }

fun kotlinx.serialization.json.JsonObject.bool(key: String): Boolean? =
    this[key]?.jsonPrimitive?.contentOrNull?.let {
        when (it.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

/** The safe fallback when a tool's schema fragments do not assemble to JSON. */
const val EMPTY_PARAMETER_SCHEMA = """{"type":"object","properties":{}}"""

/**
 * Model-facing instruction carried on an [ToolRisk.EXTERNAL_WRITE]
 * `needs_confirmation` result. Deliberately a plain literal (not a resource):
 * [ToolRegistry] and its `tools` package are Android-free by contract, and the
 * LLM system prompt is Russian by product decision while this stable machine
 * directive is provider-agnostic. The exact-call binding does the enforcing —
 * this string only tells the model what to do next.
 */
private const val NEEDS_CONFIRMATION_INSTRUCTION =
    "Ask the user to confirm this exact action, then call the tool again with the same arguments after they agree."

/**
 * Builds a JSON-schema string.
 *
 * Property values are RAW JSON fragments, so an unescaped `"` inside a
 * description silently produces invalid JSON. That is exactly how `findPlace`
 * shipped a schema that failed to parse and degraded to an empty parameter
 * object on EVERY LLM turn, the only symptom being a per-turn ERROR log.
 *
 * The assembled string is validated here — the single choke point every tool's
 * `parametersJson` goes through — so a malformed schema is caught once at
 * construction (logged loudly) and replaced with [EMPTY_PARAMETER_SCHEMA]
 * rather than being discovered per turn. It deliberately does NOT throw: a
 * typo in one description must not fail `AppGraph` construction and retry
 * forever; losing one tool's parameters is the honest degradation.
 */
fun schema(properties: Map<String, String>, required: List<String> = emptyList()): String {
    val props = properties.entries.joinToString(",") { (k, v) -> "\"$k\":$v" }
    val req = if (required.isEmpty()) "" else ",\"required\":[${required.joinToString(",") { "\"$it\"" }}]"
    val json = """{"type":"object","properties":{$props}$req}"""
    val valid = runCatching { Json.parseToJsonElement(json) }.isSuccess
    if (!valid) {
        Timber.e("Tool parameter schema is not valid JSON — falling back to the empty schema: %s", json)
        return EMPTY_PARAMETER_SCHEMA
    }
    return json
}
