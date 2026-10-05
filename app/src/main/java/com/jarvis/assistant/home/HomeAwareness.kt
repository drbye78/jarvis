package com.jarvis.assistant.home

/** A content-free state transition worth surfacing to the user. */
data class HomeNotice(
    val key: HomeDeviceKey,
    val kind: DeviceKind,
    val transition: TransitionClass,
    val atMs: Long,
)

/** The interesting transition classes. Deliberately a small closed set. */
enum class TransitionClass {
    APPLIANCE_DONE,
    DOOR_OPENED,
    LOCK_CHANGED,
    COVER_OPENED,
    SENSOR_TRIPPED,
}

/**
 * Pure transition classifier. Given the device [kind] and the old/new states it
 * reports the interesting change, or null when nothing notable happened.
 *
 * CONTENT-FREE: only enum keys and typed values are inspected; no device name,
 * room or raw attribute ever enters the decision. [kind] is required because a
 * state carries no kind and an `ON_OFF` true→false is only "an appliance
 * finished" for a cooking appliance, never for a light.
 */
object HomeNoticePolicy {

    /** Kinds that can ever produce a notice — the single source of truth used by
     * both [classify] and the awareness curation UI, so they cannot drift. */
    fun interesting(kind: DeviceKind): Boolean = kind in NOTIFIABLE_KINDS

    fun classify(kind: DeviceKind, old: HomeState, new: HomeState): TransitionClass? {
        if (kind !in NOTIFIABLE_KINDS) return null
        return when (kind) {
            DeviceKind.SENSOR -> if (tripped(old, new)) TransitionClass.SENSOR_TRIPPED else null
            DeviceKind.LOCK -> if (changed(old, new, Capability.LOCK)) TransitionClass.LOCK_CHANGED else null
            DeviceKind.COVER_DOOR -> if (opened(old, new)) TransitionClass.DOOR_OPENED else null
            DeviceKind.COVER_GARAGE -> if (opened(old, new)) TransitionClass.COVER_OPENED else null
            DeviceKind.COVER_BLIND -> if (opened(old, new)) TransitionClass.COVER_OPENED else null
            DeviceKind.APPLIANCE_COOKING -> if (finished(old, new)) TransitionClass.APPLIANCE_DONE else null
            else -> null
        }
    }

    private val NOTIFIABLE_KINDS: Set<DeviceKind> = setOf(
        DeviceKind.SENSOR,
        DeviceKind.LOCK,
        DeviceKind.COVER_DOOR,
        DeviceKind.COVER_GARAGE,
        DeviceKind.COVER_BLIND,
        DeviceKind.APPLIANCE_COOKING,
    )

    /** A binary sensor false → true. */
    private fun tripped(old: HomeState, new: HomeState): Boolean =
        boolValue(old, Capability.SENSOR) == false && boolValue(new, Capability.SENSOR) == true

    /** A lock state changed at all (locked↔unlocked/unknown). */
    private fun changed(old: HomeState, new: HomeState, capability: Capability): Boolean {
        val before = old.values[capability] ?: return false
        val after = new.values[capability] ?: return false
        return before != after
    }

    /**
     * A cover went from closed to open: a positive position, or an ON_OFF flag
     * false → true when no position is reported.
     */
    private fun opened(old: HomeState, new: HomeState): Boolean {
        val oldPosition = levelValue(old, Capability.COVER_POSITION)
        val newPosition = levelValue(new, Capability.COVER_POSITION)
        if (oldPosition != null && newPosition != null) return oldPosition <= 0.0 && newPosition > 0.0
        return boolValue(old, Capability.ON_OFF) == false && boolValue(new, Capability.ON_OFF) == true
    }

    /** A cooking appliance stopped running. */
    private fun finished(old: HomeState, new: HomeState): Boolean =
        boolValue(old, Capability.ON_OFF) == true && boolValue(new, Capability.ON_OFF) == false

    private fun boolValue(state: HomeState, capability: Capability): Boolean? =
        (state.values[capability] as? HomeValue.Bool)?.value

    private fun levelValue(state: HomeState, capability: Capability): Double? =
        (state.values[capability] as? HomeValue.Level)?.value
}

/**
 * Per-(device, transition) cooldown so a flapping sensor does not spam the
 * user. Pure state and thread-safe: [accept] records the instant of the last
 * accepted notice for the key and rejects one within [cooldownMs].
 */
class HomeNoticeDebouncer(private val cooldownMs: Long = DEFAULT_COOLDOWN_MS) {

    private val lastAcceptedAt = HashMap<String, Long>()

    fun accept(notice: HomeNotice, nowMs: Long = notice.atMs): Boolean = synchronized(lastAcceptedAt) {
        val id = "${notice.key.wire}|${notice.transition.name}"
        val at = lastAcceptedAt[id]
        if (at != null && nowMs - at < cooldownMs) return false
        lastAcceptedAt[id] = nowMs
        return true
    }

    private companion object {
        const val DEFAULT_COOLDOWN_MS = 5_000L
    }
}
