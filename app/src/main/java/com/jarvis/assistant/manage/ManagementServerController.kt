package com.jarvis.assistant.manage

/**
 * Owns the start/stop lifecycle of the [ManagementServer] from the pure
 * [ManagementActivation] state, including the LAN idle auto-close.
 *
 * Deliberately decoupled from the listener construction: [serverFactory] builds
 * a server for a mode (the caller knows the bind address and route tree), so
 * this class is testable with a fake and carries no Android types. It is
 * constructible but NOT wired into `AppGraph`/the FGS yet.
 *
 * The controller never mutates [ManagementActivation]; it only observes it. A
 * caller therefore drives intent through the activation (mode change, explicit
 * enable, disable), calls [reconcile] to apply the resulting socket state, and
 * calls [onIdleCheck] on a schedule to enforce the LAN idle window.
 */
class ManagementServerController(
    private val activation: ManagementActivation,
    private val serverFactory: (ManagementMode) -> ManagementServer,
) {

    private var server: ManagementServer? = null

    /** True while the listener is open. */
    val isRunning: Boolean
        get() = server != null

    /**
     * Starts the listener when the activation is active, stops it otherwise.
     * Idempotent: an already-open listener is left alone. Returns whether the
     * listener is running afterwards.
     */
    @Synchronized
    fun reconcile(): Boolean {
        if (activation.isActive) {
            if (server == null) {
                val started = serverFactory(activation.mode)
                started.start()
                server = started
            }
        } else {
            stopServer()
        }
        return server != null
    }

    /**
     * Idle sweep: closes the listener when the LAN window has expired, then
     * reconciles. Returns whether the listener is running afterwards.
     */
    @Synchronized
    fun onIdleCheck(): Boolean {
        activation.closeIfIdle()
        return reconcile()
    }

    /** Close the listener unconditionally (mode change to DISABLED, shutdown). */
    @Synchronized
    fun stop() {
        stopServer()
    }

    private fun stopServer() {
        val current = server ?: return
        server = null
        current.stop()
    }
}
