package com.jarvis.assistant.home

/**
 * Authorization tier for a smart-home action. Mirrors the role
 * `ToolRisk.CONTROLLED` plays in `tools/ToolAuthorization` but at ACTION
 * granularity: one home tool dispatches many verbs, so the risk cannot be a
 * static per-tool property.
 */
enum class HomeTier {
    /** Observation only; no state mutated. */
    T0_READ,

    /** A recoverable write (lighting, sockets, climate setpoints in range). */
    T1_REVERSIBLE,

    /** A safety-critical write (locks, doors, cooking/water appliances) or an unresolvable action. */
    T2_CRITICAL,
}

/**
 * Pure, total risk classifier (NO Android imports — fully JVM-testable).
 *
 * Rules, in precedence order T0 → T2 → T1:
 *  - T0 when the verb is [ActionVerb.READ] (the only read verb), or the target
 *    capability is [Capability.SENSOR]/[Capability.TEMPERATURE] and the verb is
 *    a read.
 *  - T2 when ANY of:
 *      * [HomeAction.kind] is one of [CRITICAL_KINDS] (any write), or
 *      * the verb is [ActionVerb.UNLOCK]/[ActionVerb.OPEN]/[ActionVerb.CLOSE], or
 *      * the capability is [Capability.TARGET_TEMPERATURE] and [HomeAction.level]
 *        is null or outside [[SAFE_MIN_C], [SAFE_MAX_C]], or
 *      * the kind OR capability is UNKNOWN (fail-closed on anything unmapped).
 *  - T1 otherwise (a mapped, reversible write).
 *
 * The `when`/predicate branches are exhaustive over the enums with no `else`
 * fallback that could silently downgrade an unknown value: an unrecognized
 * kind/capability is T2 by construction.
 */
object HomeRiskClassifier {

    /**
     * The inclusive safe setpoint band for [Capability.TARGET_TEMPERATURE], in
     * degrees Celsius. A setpoint outside it (or absent) is T2 because a wrong
     * extreme can scald/freeze or defeat heating. 5.0..35.0 covers domestic
     * comfort and heat-pump/AC ranges while excluding off-scale values.
     */
    const val SAFE_MIN_C: Double = 5.0
    const val SAFE_MAX_C: Double = 35.0

    /** Kinds where ANY write is safety-critical. */
    private val CRITICAL_KINDS: Set<DeviceKind> = setOf(
        DeviceKind.LOCK,
        DeviceKind.ALARM_PANEL,
        DeviceKind.COVER_GARAGE,
        DeviceKind.COVER_DOOR,
        DeviceKind.APPLIANCE_COOKING,
        DeviceKind.WATER_HEATER,
    )

    /** Verbs that are always safety-critical regardless of device. */
    private val CRITICAL_VERBS: Set<ActionVerb> = setOf(
        ActionVerb.UNLOCK,
        ActionVerb.OPEN,
        ActionVerb.CLOSE,
    )

    fun classify(action: HomeAction): HomeTier = when {
        isRead(action) -> HomeTier.T0_READ
        isCritical(action) -> HomeTier.T2_CRITICAL
        else -> HomeTier.T1_REVERSIBLE
    }

    /**
     * A read is observation-only. The capability clause is kept explicit (rather
     * than dropped as subsumed) to document that SENSOR/TEMPERATURE targets are
     * never writes and to fail closed if a future read verb is added.
     */
    private fun isRead(action: HomeAction): Boolean {
        if (action.verb == ActionVerb.READ) return true
        val observable = action.capability == Capability.SENSOR || action.capability == Capability.TEMPERATURE
        return observable && action.verb == ActionVerb.READ
    }

    private fun isCritical(action: HomeAction): Boolean {
        if (action.kind in CRITICAL_KINDS) return true
        if (action.verb in CRITICAL_VERBS) return true
        if (action.kind == DeviceKind.UNKNOWN || action.capability == Capability.UNKNOWN) return true
        return isUnsafeSetpoint(action)
    }

    private fun isUnsafeSetpoint(action: HomeAction): Boolean {
        if (action.capability != Capability.TARGET_TEMPERATURE) return false
        val level = action.level ?: return true
        return level < SAFE_MIN_C || level > SAFE_MAX_C
    }
}
