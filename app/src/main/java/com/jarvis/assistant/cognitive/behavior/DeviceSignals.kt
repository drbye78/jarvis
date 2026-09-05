package com.jarvis.assistant.cognitive.behavior

/**
 * COGNITIVE_PLAN §8.3 gates 2/4: the device-level signals the arbiter needs,
 * behind an interface so the matrix stays JVM-testable. The production
 * implementation reads real Android state; tests inject fakes.
 */
interface DeviceSignals {
    /** «Не беспокоить» is active (interruption filter != ALL). */
    fun dndActive(): Boolean

    /** Battery above the floor (15%) OR charging — a wall device usually passes. */
    fun batteryOk(): Boolean

    /** External media is playing right now (gate 4 — never talk over it). */
    fun mediaActive(): Boolean

    object Static : DeviceSignals {
        override fun dndActive() = false
        override fun batteryOk() = true
        override fun mediaActive() = false
    }
}
