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

    suspend fun execute(arguments: String): String
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
) {

    /**
     * The turn currently authorized to run tools. Held with the originating
     * session id so a superseded turn's end-of-turn clear cannot wipe the
     * context a newer turn has just bound (the turn hand-off races).
     */
    @Volatile
    private var authorizedTurn: AuthorizedTurn? = null

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
        authorizedTurn = if (context == null) {
            authorizedTurn?.takeUnless { it.sessionId == sessionId }
        } else {
            AuthorizedTurn(sessionId, context)
        }
    }

    fun available(): List<ToolContract> = tools

    fun getToolDefinitions(): List<ToolDefinition> = tools.map { tool ->
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
        val tool = tools.find { it.name == call.name }
            ?: return ToolResult(
                buildJsonObject {
                    put("error", "Unknown function: ${call.name}")
                }.toString(),
                isError = true,
            )
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
        val timeout = tool.timeoutMs ?: perToolTimeoutMs
        val startedAt = System.nanoTime()
        val result = try {
            ToolResult(withTimeout(timeout) { tool.execute(call.arguments) })
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
        return result
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
