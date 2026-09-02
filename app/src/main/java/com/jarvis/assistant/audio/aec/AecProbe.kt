package com.jarvis.assistant.audio.aec

import android.media.audiofx.AcousticEchoCanceler
import timber.log.Timber

/**
 * Phase A probe + diagnostics: is the platform's hardware echo canceller
 * real on THIS device?
 *
 * Two levels:
 *  - [staticAvailable]: `AcousticEchoCanceler.isAvailable()` — a static
 *    declaration, callable without an active recording session. Necessary
 *    but NOT sufficient (some HALs answer yes and deliver nothing).
 *  - runtime attach outcome: when [com.jarvis.assistant.audio.AudioRecordSource]
 *    starts with [MicProfile.attachHardwareAec], the create()/enabled result
 *    is recorded here and logged under the `AecDiag` tag (MusicDiag pattern:
 *    first-line diagnostics for RUNBOOK ladders).
 *
 * The last runtime outcome is kept in memory + persisted by the caller
 * (AppPrefs) so the Settings row can tell the truth even when the service
 * is not running.
 */
object AecProbe {

    /** Runtime outcome of the last hardware-AEC attach attempt. */
    sealed interface HwAecOutcome {
        /** Static probe says unavailable — never even attempted. */
        data object Unavailable : HwAecOutcome

        /** create() returned null on an initialized AudioRecord. */
        data object CreateFailed : HwAecOutcome

        /** Attached; enabled=true accepted by the framework. */
        data class Attached(val staticAvailable: Boolean) : HwAecOutcome
    }

    @Volatile
    var lastOutcome: HwAecOutcome? = null
        private set

    /** Static framework probe — safe anywhere, no active record needed. */
    fun staticAvailable(): Boolean = AcousticEchoCanceler.isAvailable()

    /**
     * Attempt attach on a live audio session id. Framework-only side effects;
     * returns the effect (already enabled) or null. Result recorded + logged.
     */
    fun attach(audioSessionId: Int): AcousticEchoCanceler? {
        if (!staticAvailable()) {
            record(HwAecOutcome.Unavailable)
            return null
        }
        val aec = AcousticEchoCanceler.create(audioSessionId)
        if (aec == null) {
            record(HwAecOutcome.CreateFailed)
            return null
        }
        val enabled = runCatching { aec.enabled = true }.isSuccess
        if (!enabled) {
            runCatching { aec.release() }
            record(HwAecOutcome.CreateFailed)
            return null
        }
        record(HwAecOutcome.Attached(staticAvailable()))
        return aec
    }

    fun record(outcome: HwAecOutcome) {
        lastOutcome = outcome
        Timber.tag("AecDiag").i("hwAec=%s static=%s", describe(outcome), staticAvailable())
    }

    fun describe(outcome: HwAecOutcome): String = when (outcome) {
        HwAecOutcome.Unavailable -> "unavailable"
        HwAecOutcome.CreateFailed -> "create-failed"
        is HwAecOutcome.Attached -> "attached"
    }

    /** One-line dump for RUNBOOK diagnostics (adb logcat -s AecDiag). */
    fun diagLine(): String {
        val o = lastOutcome
        return "AecProbe static=${staticAvailable()} runtime=${o?.let(::describe) ?: "not-attempted"}"
    }

    fun reset() {
        lastOutcome = null
    }
}
