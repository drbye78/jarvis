package com.jarvis.assistant.manage

import com.jarvis.assistant.FakeSharedPreferences
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.InMemoryVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * The process-scoped R13 management listener wiring: DISABLED is inert, a
 * graph rebuild does not rebind, LAN needs an address + an explicit enable, and
 * the idle sweep closes the listener.
 *
 * The provider is driven with an injected [ManagementEnvironment] and a counted
 * server factory, so no Android Context is needed. The factory still returns a
 * REAL [ManagementServer] (the class is final) bound to a free loopback port;
 * the assertions read the provider/controller state, not the socket.
 */
class ManagementServerProviderTest {

    @Test
    fun `disabled never invokes the server factory`() {
        val builds = intArrayOf(0)
        val provider = newProvider(lanAddress = { null }, builds = builds, failOnBuild = true)
        provider.install(fixture("disabled").components)

        assertFalse("disabled must not start a listener", provider.reconcile())
        assertFalse(provider.isRunning)
        assertEquals(0, builds[0])
    }

    @Test
    fun `localhost binds once and a second reconcile does not rebind`() {
        val builds = intArrayOf(0)
        val provider = newProvider(lanAddress = { null }, builds = builds)
        provider.install(fixture("localhost", port = freePort()).components)

        assertTrue(provider.reconcile())
        assertTrue(provider.reconcile())
        assertEquals("a graph rebuild must not double-bind the same listener", 1, builds[0])
        provider.stop()
        assertFalse(provider.isRunning)
    }

    @Test
    fun `a graph reinstall keeps the running listener (process-scoped)`() {
        val builds = intArrayOf(0)
        val provider = newProvider(lanAddress = { null }, builds = builds)
        provider.install(fixture("localhost", port = freePort()).components)
        assertTrue(provider.reconcile())

        // A watchdog revive constructs a brand-new AppGraph and installs its
        // components; the running listener must survive.
        provider.install(fixture("localhost", port = freePort()).components)
        assertTrue(provider.reconcile())
        assertEquals(1, builds[0])
        provider.stop()
    }

    @Test
    fun `lan is inactive on start and needs an address to activate`() {
        var lan: String? = null
        val builds = intArrayOf(0)
        val provider = newProvider(lanAddress = { lan }, builds = builds)
        provider.install(fixture("lan", port = freePort()).components)

        assertFalse("LAN must not auto-activate on process start", provider.reconcile())
        assertFalse("LAN with no IPv4 must degrade to stopped", provider.activate())
        assertEquals(0, builds[0])

        lan = "127.0.0.1"
        assertTrue(provider.activate())
        assertEquals(1, builds[0])
        provider.stop()
    }

    @Test
    fun `a persisted mode change rebinds the listener`() {
        val builds = intArrayOf(0)
        val provider = newProvider(lanAddress = { "127.0.0.1" }, builds = builds)
        val fixture = fixture("localhost", port = freePort())
        provider.install(fixture.components)
        assertTrue(provider.reconcile())

        fixture.prefs.managementMode = "lan"
        assertTrue(provider.reconcile())
        assertEquals("changing the bind host must stop the old listener and rebind", 2, builds[0])
        provider.stop()
    }

    @Test
    fun `the idle sweep closes a lan listener past its window`() {
        var now = 0L
        val builds = intArrayOf(0)
        val provider = newProvider(lanAddress = { "127.0.0.1" }, builds = builds, nowMs = { now })
        provider.install(fixture("lan", port = freePort(), idleMs = 1_000L).components)
        provider.reconcile()
        assertTrue(provider.activate())
        assertTrue(provider.isRunning)

        now = 999L
        assertTrue("still inside the idle window", provider.onIdleCheck())
        now = 1_000L
        assertFalse("past the idle window the listener closes", provider.onIdleCheck())
        assertFalse(provider.isRunning)
    }

    // ------------------------------------------------------------------

    private class Fixture(val prefs: AppPrefs, val components: ManagementComponents)

    private fun fixture(mode: String, port: Int = 0, idleMs: Long = DEFAULT_IDLE_MS): Fixture {
        val vault = InMemoryVault()
        val prefs = AppPrefs(context = null, vaultOverride = vault, prefsOverride = FakeSharedPreferences())
        prefs.managementMode = mode
        prefs.managementPort = port
        prefs.managementIdleTimeoutMs = idleMs
        val bindings = ManagementBindings(vault)
        val components = ManagementComponents(
            vault = vault,
            bindings = bindings,
            core = ManagementCore(
                prefs = prefs,
                bindings = bindings,
                vault = vault,
                appVersion = "test",
                pendingPolicies = { emptySet() },
            ),
        )
        return Fixture(prefs, components)
    }

    private fun newProvider(
        lanAddress: () -> String?,
        builds: IntArray,
        failOnBuild: Boolean = false,
        nowMs: () -> Long = System::currentTimeMillis,
    ): ManagementServerProvider = ManagementServerProvider(
        env = ManagementEnvironment(
            assets = AssetSource { null },
            lanAddress = lanAddress,
            nowMs = nowMs,
        ),
    ) { _, components ->
        check(!failOnBuild) { "the server factory must not run" }
        builds[0]++
        ManagementServer(
            TlsCertFactory.generate(),
            components.core.status().port,
            ManagementServer.LOOPBACK_HOST,
        ) {}
    }

    private fun freePort(): Int =
        ServerSocket(0, 1, InetAddress.getByName(ManagementServer.LOOPBACK_HOST)).use { it.localPort }

    private companion object {
        const val DEFAULT_IDLE_MS = 900_000L
    }
}
