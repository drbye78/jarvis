package com.jarvis.assistant.contracts

import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.model.AssistantState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/** Shared audio format contract. */
data class AudioSpec(
    val sampleRate: Int,
    val channels: Int,      // 1 = mono
    val encodingBits: Int,  // 16
) {
    companion object {
        val MIC = AudioSpec(16_000, 1, 16)
        val TTS = AudioSpec(24_000, 1, 16)
    }
}

interface AudioSource {
    fun start()
    fun read(): ShortArray
    fun stop()
}

/**
 * What a wake-word-class engine heard.
 *
 * FIXPLAN B: engines now carry keyword IDENTITY — the same on-device KWS
 * spotter recognizes the wake phrase AND the stop phrase ("стоп"/"stop",
 * the same spoken word in Russian and English), so the user can interrupt a
 * playing answer without the wake word.
 */
sealed interface Detection {
    /** A wake phrase — starts (or barges into) a session. */
    data object WakeWord : Detection

    /**
     * The stop phrase was heard while the assistant THINKS or SPEAKS —
     * cancel the active turn. Ignored in every other state (a "стоп" inside
     * a normal command must not nuke the turn).
     */
    data class StopPhrase(val keyword: String) : Detection

    /** Wake-word engine failed to initialize; assistant cannot listen. */
    data class DetectorError(val message: String) : Detection
}

/**
 * Lifecycle of the wake-word engine, observable synchronously. Exists because
 * a failure emitted into a replay-less SharedFlow before any subscriber is
 * simply dropped — [Failed] must be readable at any time (M1).
 */
sealed interface DetectorState {
    data object Bootstrapping : DetectorState
    data object Ready : DetectorState
    data class Failed(val reason: String) : DetectorState

    /** Terminal: engine released; restart flows must re-init, not reuse. */
    data object Released : DetectorState
}

interface WakeWordDetector {
    val state: kotlinx.coroutines.flow.StateFlow<DetectorState>
    fun detections(): kotlinx.coroutines.flow.Flow<Detection>
    fun release()

    /**
     * Swap the active engine + model live. The implementation builds the new
     * engine off the calling thread and swaps it under a mutex, releasing the
     * old engine only after the new one is in place.
     */
    suspend fun reconfigure(req: WakeWordRequest)

    /** Rebuild the active engine with a new sensitivity (keeps the model). */
    suspend fun setSensitivity(value: Float)

    /**
     * FIXPLAN B: feed the dedicated stop-phrase lane while the assistant
     * THINKS or SPEAKS. No-op for engines whose keyword set already carries
     * the stop phrase (Sherpa primary); for Porcupine-primary it gates the
     * extra KWS engine's feed — zero idle CPU either way.
     */
    fun setStopLaneEnabled(enabled: Boolean) {}
}

/**
 * Explicit barge-in policy (M7). TTS answers containing «Джарвис» used to
 * truncate themselves because ANY single detection during playback barged in;
 * interrupting playback now requires an explicit, configurable gesture:
 *
 * - Outside SPEAKING every wake-word detection passes (mode irrelevant).
 * - During SPEAKING with [Mode.REPEAT_DURING_PLAYBACK] (default) the FIRST
 *   detection only opens a candidate window; a SECOND detection within
 *   [repeatWindowMs] passes (repeat-to-interrupt UX). [Mode.SINGLE] lets the
 *   first detection pass immediately (quieter rooms).
 * - After ANY accepted detection further detections are suppressed for
 *   [postAcceptCooldownMs] (replaces the old trailing-audio cooldown).
 * - [Detection.DetectorError] always passes ungated.
 */
data class BargeInPolicy(
    val mode: Mode,
    val repeatWindowMs: Long = 1_200,
    val postAcceptCooldownMs: Long = 600,
) {
    enum class Mode { SINGLE, REPEAT_DURING_PLAYBACK }

    companion object {
        fun from(config: JarvisConfig): BargeInPolicy = BargeInPolicy(
            mode = if (config.bargeInSingleShot) Mode.SINGLE else Mode.REPEAT_DURING_PLAYBACK,
            repeatWindowMs = config.bargeInRepeatWindowMs,
        )
    }
}

/**
 * Gate a wake-word [Detection] flow by the current [AssistantState] and a
 * [BargeInPolicy]; see the policy KDoc for the exact semantics.
 *
 * Pure function of its inputs apart from time: [nowMs] is injectable so JVM
 * tests drive the clock deterministically. Wired into the session in
 * SessionManager.startListening via detections().gatedBy(BargeInPolicy.from(config), stateMachine.state).
 *
 * D5: the DEFAULT clock is monotonic (nanoTime/1e6), not wall time — a
 * backwards wall-clock jump (NTP correction, manual time set) made
 * `now - lastAccepted` negative and suppressed every detection until the
 * wall clock caught back up. Monotonic differences are immune to that.
 *
 * FIXPLAN B: [Detection.StopPhrase] passes UNGATED in every state — the
 * whole point of the stop phrase is that ONE utterance cancels playback,
 * with no repeat-to-interrupt barrier and no cooldown. State-conditional
 * ROUTING (which states honor a stop) lives in SessionManager, which knows
 * the machine; this gate only filters the wake-word gesture.
 */
fun Flow<Detection>.gatedBy(
    policy: BargeInPolicy,
    assistantState: StateFlow<AssistantState>,
    nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
): Flow<Detection> = flow {
    // Null = "never". Numeric sentinels (e.g. Long.MIN_VALUE) would overflow
    // on subtraction and permanently suppress detections.
    var lastAcceptedAt: Long? = null
    var candidateAt: Long? = null // open repeat-window start
    collect { detection ->
        when (detection) {
            is Detection.DetectorError -> emit(detection)
            is Detection.StopPhrase -> emit(detection) // always passes; routed by state downstream
            Detection.WakeWord -> {
                val now = nowMs()
                val lastAccepted = lastAcceptedAt
                if (lastAccepted != null && now - lastAccepted < policy.postAcceptCooldownMs) {
                    return@collect
                }
                if (assistantState.value != AssistantState.SPEAKING) {
                    lastAcceptedAt = now
                    candidateAt = null
                    emit(detection)
                    return@collect
                }
                when (policy.mode) {
                    BargeInPolicy.Mode.SINGLE -> {
                        lastAcceptedAt = now
                        candidateAt = null
                        emit(detection)
                    }
                    BargeInPolicy.Mode.REPEAT_DURING_PLAYBACK -> {
                        val window = candidateAt
                        val inWindow = window != null && now - window <= policy.repeatWindowMs
                        if (inWindow) {
                            lastAcceptedAt = now
                            candidateAt = null
                            emit(detection)
                        } else {
                            // First detection (or stale window): open/restart it.
                            candidateAt = now
                        }
                    }
                }
            }
        }
    }
}
