package com.jarvis.assistant.di

/**
 * Process-wide holder giving UI components access to the live [AppGraph]
 * (state machine, conversation transcript). The graph itself is owned by the
 * foreground service; the holder just exposes a nullable reference.
 */
object GraphHolder {
    @Volatile
    var graph: AppGraph? = null

    val isRunning: Boolean get() = graph != null
}
