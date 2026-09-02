package com.jarvis.assistant.audio.aec

/**
 * User-selected echo-cancellation mode (Settings «Эхоподавление» card).
 * All modes default OFF — these are opt-in features.
 */
enum class AecMode {
    /** Raw mic (VOICE_RECOGNITION), no platform effects, no software AEC. */
    OFF,

    /**
     * Phase A: capture through the platform comms DSP —
     * VOICE_COMMUNICATION audio source + framework AcousticEchoCanceler.
     * The HAL applies HW AEC/NS/AGC against everything the speaker plays.
     * Device-dependent; probe outcome surfaces in Settings/AecDiag.
     */
    HARDWARE,

    /**
     * Phase B: raw capture + the in-process [NlmsEchoCanceller] with
     * electrical far-end references (own TTS tap + optional playback
     * capture of other apps' music).
     */
    SOFTWARE,
    ;

    companion object {
        /** Parse from persisted prefs value; unknown/null ⇒ OFF (safe default). */
        fun fromPref(value: String?): AecMode = when (value) {
            "hardware" -> HARDWARE
            "software" -> SOFTWARE
            else -> OFF
        }

        fun toPref(mode: AecMode): String = when (mode) {
            OFF -> "off"
            HARDWARE -> "hardware"
            SOFTWARE -> "software"
        }
    }
}

/**
 * Pure decision: how [com.jarvis.assistant.audio.AudioRecordSource] should
 * capture for a given [AecMode].
 *
 * @param androidAudioSource MediaRecorder.AudioSource constant.
 * @param attachHardwareAec whether to create+enable AcousticEchoCanceler on
 *        the record's audio session.
 */
data class MicProfile(
    val androidAudioSource: Int,
    val attachHardwareAec: Boolean,
) {
    companion object {
        /** MediaRecorder.AudioSource.VOICE_RECOGNITION (6). */
        private const val VOICE_RECOGNITION = 6

        /** MediaRecorder.AudioSource.VOICE_COMMUNICATION (7). */
        private const val VOICE_COMMUNICATION = 7

        fun forMode(mode: AecMode): MicProfile = when (mode) {
            // Software AEC needs a CLEAN electrical reference — platform
            // effects on the mic would double-cancel and break the adaptive
            // filter's linearity assumption.
            AecMode.OFF, AecMode.SOFTWARE -> MicProfile(VOICE_RECOGNITION, false)
            AecMode.HARDWARE -> MicProfile(VOICE_COMMUNICATION, true)
        }
    }
}
