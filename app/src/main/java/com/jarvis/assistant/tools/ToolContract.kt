package com.jarvis.assistant.tools

import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.model.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
     * Optional per-tool execution timeout override (MUSIC lane). Null →
     * the registry default. `playMusic` legitimately needs ~30 s: cold-start
     * the player, wait for its media session, playFromSearch, verify.
     */
    val timeoutMs: Long? get() = null

    suspend fun execute(arguments: String): String
}

/**
 * Structured outcome of a tool execution (m1). Classification is carried by
 * [isError] instead of sniffing the content for an `"error"` substring —
 * legitimate payloads may legitimately contain that key.
 */
data class ToolResult(val content: String, val isError: Boolean = false)

/** Tool facade used by the session layer (interface for JVM testing). */
interface ToolExecutor {
    fun getToolDefinitions(): List<ToolDefinition>
    suspend fun executeResult(call: FunctionCall): ToolResult
}

/**
 * Registry + executor. Unknown tools, exceptions, and hangs are all converted
 * into JSON error results — a tool can never crash or wedge the turn.
 */
class ToolRegistry(
    private val tools: List<ToolContract>,
    private val perToolTimeoutMs: Long = 15_000,
) {

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
     * Classified execution (m1): success → isError=false; execution exception
     * or timeout → isError=true with JSON error content. The old
     * `result.contains("\"error\"")` substring sniffing is gone — a payload
     * that merely mentions "error" is no longer misclassified.
     *
     * Audit #4: [CancellationException] is RETHROWN, never converted to an
     * error result. Barge-in cancels the session mid tool call; swallowing
     * that cancellation here would break structured concurrency (the turn
     * would keep running and persist a bogus tool error instead of being
     * cancelled). [TimeoutCancellationException] is re-caught first: it is
     * a CancellationException subclass, but the per-tool timeout is OURS.
     */
    suspend fun executeResult(call: FunctionCall): ToolResult {
        val tool = tools.find { it.name == call.name }
            ?: return ToolResult("""{"error":"Unknown function: ${call.name}"}""", isError = true)
        val timeout = tool.timeoutMs ?: perToolTimeoutMs
        return try {
            ToolResult(withTimeout(timeout) { tool.execute(call.arguments) })
        } catch (e: TimeoutCancellationException) {
            Timber.w("Tool %s timed out after %d ms", call.name, timeout)
            ToolResult("""{"error":"Tool timed out"}""", isError = true)
        } catch (e: CancellationException) {
            throw e // barge-in / shutdown — the session must observe it
        } catch (e: Exception) {
            Timber.e(e, "Tool %s failed", call.name)
            ToolResult("""{"error":"Tool execution failed: ${e.message}"}""", isError = true)
        }
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
    this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

fun kotlinx.serialization.json.JsonObject.bool(key: String): Boolean? =
    this[key]?.jsonPrimitive?.contentOrNull?.let {
        when (it.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

/** Builds a JSON-schema string. */
fun schema(properties: Map<String, String>, required: List<String> = emptyList()): String {
    val props = properties.entries.joinToString(",") { (k, v) -> "\"$k\":$v" }
    val req = if (required.isEmpty()) "" else ",\"required\":[${required.joinToString(",") { "\"$it\"" }}]"
    return """{"type":"object","properties":{$props}$req}"""
}
