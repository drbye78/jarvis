package com.jarvis.assistant.manage

import android.content.Context
import com.jarvis.assistant.util.KeystoreVault
import com.jarvis.assistant.util.SecretVault
import timber.log.Timber

/**
 * The process-stable pieces `AppGraph` builds and hands to
 * [ManagementServerProvider] (R13 §14). Nothing here touches the socket; the
 * graph only supplies the durable bridges, and the provider owns the runtime.
 */
class ManagementComponents(
    val vault: SecretVault,
    val bindings: ManagementBindings,
    val core: ManagementCore,
)

/**
 * Everything the provider needs that is NOT process state: the SPA bytes, the
 * LAN address lookup and the clock. Injectable so the provider is JVM-testable
 * without an Android Context (the production [ManagementServerProvider.get]
 * wires [AndroidAssetSource] and [ManagementNetwork]).
 */
internal class ManagementEnvironment(
    val assets: AssetSource,
    val lanAddress: () -> String?,
    val nowMs: () -> Long = System::currentTimeMillis,
)

/**
 * Process-scoped owner of the R13 §14 management listener.
 *
 * WHY PROCESS-SCOPED: `AppGraph` is rebuilt on every service start and on every
 * watchdog revive WITHIN THE SAME PROCESS, so a graph-owned listener would be
 * torn down and rebound (or worse, leak a second Netty bind) on each rebuild.
 * The [ManagementActivation] (in-memory, never persisted) and the running
 * [ManagementServerController] must outlive a graph, so they live here,
 * mirroring [com.jarvis.assistant.geo.mapkit.MapKitInitializerProvider] and
 * [com.jarvis.assistant.tools.RingCoordinatorProvider].
 *
 * NO DOUBLE-BIND ACROSS A GRAPH REBUILD: `AppGraph` calls [install] with fresh
 * components on every build, but [reconcile] hands the SAME controller to
 * `ManagementServerController.reconcile`, whose `server == null` guard leaves an
 * already-open listener alone. The factory reads the LATEST installed
 * components only when a listener is actually built, so a stale graph's core
 * can never own a rebound listener.
 *
 * DISABLED IS INERT: with `managementMode == "disabled"` the persisted mode is
 * applied as inactive ([ManagementMode]) and [reconcile] never invokes the
 * server factory — nothing binds, nothing listens. The activation rule itself is
 * pinned by `ManagementModeTest`; `ManagementServerProviderTest` pins that the
 * factory is never called while disabled.
 */
class ManagementServerProvider internal constructor(
    private val env: ManagementEnvironment,
    /**
     * Vault-backed owner of the TLS material. Production passes a process-stable
     * [ManagementTlsStore] so the cert fingerprint survives a restart; a
     * directly-constructed provider (tests) may omit it and [ensureTls] falls
     * back to the installed components' vault. It must NEVER be a bare
     * [TlsCertFactory.generate] call, or the verified fingerprint would change
     * on every service restart.
     */
    private val tlsStore: ManagementTlsStore? = null,
    /**
     * Test seam: replaces the real Netty factory. Production leaves it null and
     * [buildServer] is used.
     */
    private val serverFactory: ((ManagementMode, ManagementComponents) -> ManagementServer)? = null,
) {

    @Volatile
    private var components: ManagementComponents? = null

    /**
     * In-process cache of the vault-backed material; the vault is the durable
     * source, so a new process loads the SAME certificate (stable fingerprint).
     */
    @Volatile
    private var tls: TlsMaterial? = null

    private var activation: ManagementActivation? = null

    private var controller: ManagementServerController? = null

    /** The port the last-built listener actually bound, for the status row. */
    @Volatile
    private var activePort: Int? = null

    /** False until the persisted mode has been applied exactly once this process. */
    private var initialized = false

    /** `AppGraph` hands the graph-owned components here; safe to call per rebuild. */
    @Synchronized
    fun install(components: ManagementComponents) {
        this.components = components
    }

    /** True while the listener is open. */
    val isRunning: Boolean
        get() = controller?.isRunning == true

    /** The bound port while running, or null when stopped (for the status row). */
    val boundPort: Int?
        get() = if (isRunning) activePort else null

    /** True while the in-memory activation intends the listener to be open. */
    val isActive: Boolean
        get() = activation?.isActive == true

    /**
     * Build/keep or stop the listener so it matches the persisted mode AND the
     * in-memory activation. Idempotent across graph rebuilds (see the class
     * KDoc). Returns whether the listener is running afterwards.
     */
    @Synchronized
    fun reconcile(): Boolean {
        if (!ensureReady()) return false
        syncMode()
        return controller!!.reconcile()
    }

    /**
     * Explicit user enable (Settings action): apply the persisted mode, then
     * force the activation on. A [ManagementMode.DISABLED] intent stays off
     * (enable is a no-op there), and an [ManagementMode.LAN] enable with no LAN
     * address degrades honestly to "stopped" instead of throwing.
     */
    @Synchronized
    fun activate(): Boolean {
        if (!ensureReady()) return false
        syncMode()
        val current = activation!!
        if (current.mode == ManagementMode.LAN && env.lanAddress() == null) {
            Timber.e("Management: LAN activation skipped — no LAN IPv4 available")
            return controller!!.reconcile()
        }
        current.enable()
        return controller!!.reconcile()
    }

    /** Explicit user disable (fail-safe direction; needs no confirmation). */
    @Synchronized
    fun deactivate(): Boolean {
        if (!ensureReady()) return false
        activation!!.disable()
        return controller!!.reconcile()
    }

    /**
     * Idle sweep for the service tick loop: close the listener when the LAN
     * window has expired, then reconcile.
     */
    @Synchronized
    fun onIdleCheck(): Boolean {
        if (!ensureReady()) return false
        return controller!!.onIdleCheck()
    }

    /** Close the listener unconditionally (service teardown). */
    @Synchronized
    fun stop() {
        controller?.stop()
    }

    /**
     * The certificate fingerprint the listener uses. The material is loaded
     * from (or created once and persisted to) the vault, so the value shown in
     * Settings is stable across service/process restarts and always matches the
     * running server. Heavy (RSA-2048 on first creation) — callers must keep
     * this OFF the main thread.
     */
    @Synchronized
    fun fingerprint(): String = ensureTls().sha256Fingerprint

    // ------------------------------------------------------------------
    // Internals.
    // ------------------------------------------------------------------

    /** Creates the activation/controller on first use; false when no graph yet. */
    private fun ensureReady(): Boolean {
        if (components == null) return false
        if (controller == null) {
            val created = ManagementActivation(
                // Read LIVE from the current components so an idle-timeout edit
                // applies to a running listener (the pref is re-read on every
                // isIdleExpired check); capturing the value here would make the
                // Settings field inert until a process restart.
                idleTimeoutMs = { requireComponents().core.status().idleTimeoutMs },
                nowMs = env.nowMs,
            )
            activation = created
            controller = ManagementServerController(created) { mode -> buildServer(mode, requireComponents()) }
        }
        return true
    }

    /**
     * Applies the persisted mode to the in-memory activation. The FIRST apply
     * uses the process-start rule (LAN does NOT auto-activate); a later mode
     * change is deliberate and enables, closing any listener bound for the old
     * host so the next reconcile rebinds it.
     */
    private fun syncMode() {
        val persisted = requireComponents().core.status().mode
        val current = requireActivation()
        if (!initialized) {
            initialized = true
            current.start(persisted, explicitEnable = false)
            return
        }
        if (current.mode != persisted) {
            current.start(persisted, explicitEnable = true)
            controller?.stop()
        }
    }

    private fun buildServer(mode: ManagementMode, comps: ManagementComponents): ManagementServer {
        serverFactory?.let { return it(mode, comps) }
        val status = comps.core.status()
        activePort = status.port
        val material = ensureTls()
        val lanIp = env.lanAddress()
        val bindHost = when (mode) {
            ManagementMode.LOCALHOST -> ManagementServer.LOOPBACK_HOST
            ManagementMode.LAN -> lanIp ?: error("LAN mode has no LAN address to bind")
            ManagementMode.DISABLED -> ManagementServer.LOOPBACK_HOST
        }
        val routes = ManagementRoutes(
            core = comps.core,
            auth = ManagementAuth(
                readStoredSecret = { comps.vault.getString(SecretVault.KEY_MANAGEMENT_PASSWORD) },
                writeStoredSecret = { comps.vault.putString(SecretVault.KEY_MANAGEMENT_PASSWORD, it) },
            ),
            sessions = SessionStore(),
            activation = requireActivation(),
            assets = env.assets,
            tlsFingerprint = material.sha256Fingerprint,
            allowedHosts = allowedHostsFor(mode, lanIp),
        )
        return ManagementServer(material, status.port, bindHost) { routes.install(this) }
    }

    private fun allowedHostsFor(mode: ManagementMode, lanIp: String?): Set<String> =
        if (mode == ManagementMode.LAN && lanIp != null) {
            ManagementRoutes.DEFAULT_ALLOWED_HOSTS + lanIp
        } else {
            ManagementRoutes.DEFAULT_ALLOWED_HOSTS
        }

    private fun ensureTls(): TlsMaterial = tls ?: synchronized(this) {
        tls ?: resolveTlsStore().loadOrCreate().also { tls = it }
    }

    /**
     * The store that owns TLS creation. Production supplies one at construction
     * (vault resolved from the app context); a test-constructed provider falls
     * back to the vault in the installed components.
     */
    private fun resolveTlsStore(): ManagementTlsStore =
        tlsStore ?: ManagementTlsStore(requireComponents().vault)

    private fun requireComponents(): ManagementComponents = requireNotNull(components) {
        "ManagementServerProvider has no components — AppGraph.install must run first"
    }

    private fun requireActivation(): ManagementActivation = requireNotNull(activation) {
        "ManagementServerProvider activation not created — call reconcile/activate first"
    }

    companion object {
        @Volatile
        private var instance: ManagementServerProvider? = null

        /**
         * The one provider for this process. Safe to call on every graph build
         * and from Settings; the first call constructs it.
         */
        fun get(context: Context): ManagementServerProvider {
            val appContext = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: ManagementServerProvider(
                    env = ManagementEnvironment(
                        assets = AndroidAssetSource(appContext),
                        lanAddress = ManagementNetwork::lanIpv4,
                    ),
                    tlsStore = ManagementTlsStore(KeystoreVault.get(appContext)),
                ).also { instance = it }
            }
        }

        /** Service teardown: close the listener, keep the process-lifetime state. */
        fun stop() {
            instance?.stop()
        }

        /** Test seam: close and drop the memoized provider. */
        fun clearForTests() {
            instance?.stop()
            instance = null
        }
    }
}
