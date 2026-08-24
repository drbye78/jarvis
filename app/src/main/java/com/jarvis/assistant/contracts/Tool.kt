package com.jarvis.assistant.contracts

/**
 * Interface for all assistant tools registered in the [ToolRegistry].
 *
 * Each tool advertises its name, description, and a JSON-schema string
 * ([parametersJson]) so the LLM can decide when and how to call it. The
 * [execute] method receives a raw JSON arguments string (matching
 * [FunctionCall.arguments]) and must return a result JSON string.
 *
 * Note: this interface lives alongside the [Tool] data class (models.kt).
 * The data class is used for serialization/LM-facing contract; this interface
 * is used for registry and execution.
 */
interface ToolContract {
    val name: String
    val description: String
    val parametersJson: String
    suspend fun execute(arguments: String): String
}