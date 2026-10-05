package com.jarvis.assistant.home

/**
 * One pre-authorizable action of a device: the `(capability, verb)` pair a
 * fast-path grant is keyed by.
 */
data class Grantable(val capability: Capability, val verb: ActionVerb)

/**
 * Derives the actions a user MAY pre-authorize for a device.
 *
 * PURE and STRUCTURAL: a candidate is offered ONLY when a representative
 * [HomeAction] classifies [HomeTier.T1_REVERSIBLE]. Because T2 (locks, doors,
 * garage/cooking/water appliances, `UNLOCK`/`OPEN`/`CLOSE`, an out-of-band
 * setpoint, `UNKNOWN`) and T0 (reads) are filtered by the SAME classifier that
 * gates execution, the grants editor cannot manufacture a grant for a critical
 * action even if it tried — and a stale or hand-edited grant is still
 * re-classified at execution, so this is convenience, not the boundary.
 *
 * The verb map mirrors the actions `HomeTools.buildAction` can construct, and
 * the classifier filter (not the map) is what excludes locks/covers/appliances:
 * those candidates ARE generated and then dropped as T2, which is what makes the
 * guarantee testable. Because the map is the tool's own surface, a grant is
 * always exercisable — there are no dead grants.
 */
object HomeGrantables {

    /** Mid-band representative setpoint so a climate candidate classifies T1. */
    private const val REPRESENTATIVE_SETPOINT_C = 22.0

    /**
     * The verbs each capability can produce — EXACTLY the `(capability, verb)`
     * pairs `HomeTools.buildAction` can construct for a model action token. A
     * capability that the tool cannot drive (`COLOR`, `MODE`, `FAN_SPEED`,
     * `SET_POSITION`) is deliberately absent: a grant for an action the tool can
     * never issue is a dead grant, and offering it would mislead the user.
     */
    private val VERBS: Map<Capability, List<ActionVerb>> = mapOf(
        Capability.ON_OFF to listOf(ActionVerb.TURN_ON, ActionVerb.TURN_OFF),
        Capability.BRIGHTNESS to listOf(ActionVerb.SET_LEVEL),
        Capability.TARGET_TEMPERATURE to listOf(ActionVerb.SET_TEMPERATURE),
        Capability.LOCK to listOf(ActionVerb.LOCK, ActionVerb.UNLOCK),
        Capability.COVER_POSITION to listOf(ActionVerb.OPEN, ActionVerb.CLOSE),
    )

    /** The grantable actions of [device], in a stable order, T1 only. */
    fun forDevice(device: HomeDevice): List<Grantable> =
        candidates(device.capabilities)
            .filter { HomeRiskClassifier.classify(actionFor(device, it)) == HomeTier.T1_REVERSIBLE }
            .distinct()

    /** True when [grant] is one of [device]'s current grantable actions. */
    fun isGrantable(device: HomeDevice, grant: HomeGrant): Boolean =
        device.key.provider.id == grant.provider &&
            device.key.nativeId == grant.nativeId &&
            forDevice(device).any { it.capability.name == grant.capability && it.verb.name == grant.verb }

    private fun candidates(capabilities: Set<Capability>): List<Grantable> =
        capabilities.flatMap { capability ->
            VERBS[capability].orEmpty().map { verb -> Grantable(capability, verb) }
        }

    private fun actionFor(device: HomeDevice, grantable: Grantable): HomeAction = HomeAction(
        key = device.key,
        kind = device.kind,
        capability = grantable.capability,
        verb = grantable.verb,
        level = if (grantable.capability == Capability.TARGET_TEMPERATURE) REPRESENTATIVE_SETPOINT_C else null,
    )
}
