package com.jarvis.assistant.contracts

import kotlinx.coroutines.flow.Flow

sealed interface Detection {
    /** Emitted while in LISTENING state. */
    data object WakeWord : Detection
    /** Emitted while in SPEAKING state (user interrupted the assistant). */
    data object BargeIn : Detection
}

/**
 * Single owner of the Porcupine engine. All frames are processed by ONE actor,
 * eliminating the concurrent `process()` race from the v2.1 blueprint.
 * Consumers interpret detections by current AssistantState.
 */
interface WakeWordDetector {
    fun detections(): Flow<Detection>
    fun release()
}
