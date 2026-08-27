package com.jarvis.assistant.llm

/**
 * Accumulates the streamed fragments of a single tool/function call across
 * LLM chunk deltas until the call is complete. This is the single, shared
 * implementation used by both the streaming LLM SSE clients and the session
 * turn logic (P7/m15 consolidation — previously duplicated as `ToolCallAccum`
 * in two places).
 */
class ToolCallAccumulator(val index: Int) {
    var name: String? = null
    var id: String? = null
    val args = StringBuilder()
}
