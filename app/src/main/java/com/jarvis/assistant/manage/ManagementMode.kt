package com.jarvis.assistant.manage

/**
 * The user's persisted **intent** for the optional R13 management surface.
 *
 * [id] is the stable lowercase token that lands in `AppPrefs.managementMode`;
 * the enum constant name is never persisted, so renaming a constant cannot
 * orphan a stored preference. [fromId] is deliberately tolerant (the same
 * idiom as [com.jarvis.assistant.weather.WeatherProvider.fromId] and
 * `McpServerKind`): a null/blank/unknown value degrades to [DISABLED], the
 * safe default, instead of throwing during startup.
 *
 * Intent is NOT the same as runtime state. A mode of [LAN] only means the user
 * *asked* for LAN reachability at some point; the listener is open only while
 * `ManagementActivation.isActive` is true, and that flag is in-memory only and
 * never written to disk (a reboot always drops LAN).
 */
enum class ManagementMode(val id: String) {
    /** The surface is off. The default, and the only safe fresh-install state. */
    DISABLED("disabled"),

    /**
     * Loopback-only listener. It auto-activates on process start because the
     * sole route in is an authorized `adb forward`; there is no external
     * exposure and therefore no idle auto-close.
     */
    LOCALHOST("localhost"),

    /**
     * Listener bound to the specific LAN IPv4. It does NOT auto-activate on
     * process start — a deliberate re-enable (UI or voice) is required — and
     * it auto-closes after `managementIdleTimeoutMs` without an authenticated
     * request.
     */
    LAN("lan"),
    ;

    companion object {
        /** The fresh-install default; see [fromId]. */
        val DEFAULT = DISABLED

        /** Tolerant parse: null/blank/unknown → [DEFAULT]. */
        fun fromId(raw: String?): ManagementMode =
            entries.firstOrNull { it.id == raw?.trim()?.lowercase() } ?: DEFAULT
    }
}

/**
 * The runtime activation rule, as a pure function.
 *
 * `explicitEnable` is a deliberate user/voice enable issued at the same moment
 * as the process start decision (it is normally false on a real process start,
 * because an explicit enable at runtime goes through [ManagementActivation]
 * instead). [LOCALHOST] auto-activates; [DISABLED] never does; [LAN] requires
 * the explicit flag.
 */
fun initialManagementActive(mode: ManagementMode, explicitEnable: Boolean = false): Boolean = when (mode) {
    ManagementMode.DISABLED -> false
    ManagementMode.LOCALHOST -> true
    ManagementMode.LAN -> explicitEnable
}

/**
 * Pure, clock-injected holder for the **in-memory** activation state of the
 * management listener. Nothing here is persisted: a reboot always returns to
 * the process-start rule, which is what makes "LAN is dropped on reboot"
 * structural rather than a special case.
 *
 * The idle timer is refreshed ONLY by [touch] (an authenticated request);
 * `/health` and unauthenticated probes must never call it. Only [LAN] has
 * idle auto-close — [LOCALHOST] stays up (loopback is adb-gated) and
 * [DISABLED] is never active.
 *
 * @param idleTimeoutMs LAN idle window; `<= 0` disables idle auto-close.
 * @param nowMs injected epoch-millis clock, so expiry is deterministic in tests.
 */
class ManagementActivation(
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** The persisted intent most recently applied at runtime. */
    var mode: ManagementMode = ManagementMode.DISABLED
        private set

    /** The socket-is-open state; in-memory only. */
    var isActive: Boolean = false
        private set

    private var lastAuthenticatedAtMs: Long = 0L

    /**
     * Applies a mode as if at process start (or on a deliberate mode change).
     * [LOCALHOST] becomes active immediately; [LAN] only with [explicitEnable].
     */
    fun start(startMode: ManagementMode, explicitEnable: Boolean = false) {
        mode = startMode
        isActive = initialManagementActive(startMode, explicitEnable)
        lastAuthenticatedAtMs = nowMs()
    }

    /**
     * A deliberate enable after start (UI or a confirmed voice turn). A no-op
     * while the intent is [ManagementMode.DISABLED]; enabling LAN this way is
     * exactly the `explicitEnable` case of [start].
     */
    fun enable() {
        if (mode == ManagementMode.DISABLED) return
        isActive = true
        lastAuthenticatedAtMs = nowMs()
    }

    /** Close the listener. Disabling is always fail-safe and needs no confirmation. */
    fun disable() {
        isActive = false
    }

    /** Refresh the idle timer; call ONLY for an authenticated request. */
    fun touch() {
        if (isActive) lastAuthenticatedAtMs = nowMs()
    }

    /** True when an active [LAN] session has gone idle past [idleTimeoutMs]. */
    fun isIdleExpired(): Boolean =
        isActive &&
            mode == ManagementMode.LAN &&
            idleTimeoutMs > 0 &&
            nowMs() - lastAuthenticatedAtMs >= idleTimeoutMs

    /**
     * Closes the listener on idle expiry. Returns true only on the transition
     * from active to closed, so a caller can log/observe the auto-close once.
     * The persisted `mode` is left as-is (the UI shows "LAN, stopped").
     */
    fun closeIfIdle(): Boolean {
        if (!isIdleExpired()) return false
        isActive = false
        return true
    }

    companion object {
        /** §14.1 default: 15 minutes. */
        const val DEFAULT_IDLE_TIMEOUT_MS = 15L * 60 * 1000
    }
}
