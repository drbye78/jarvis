package com.jarvis.assistant.media

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.service.media.MediaBrowserService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.assistant.service.JarvisNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DEVICE-ONLY coverage for the MEDIA/TRANSPORT lane, which previously had none:
 * every rule was asserted only against JVM fakes. CI merely COMPILES this source
 * set (`:app:assembleDebugAndroidTest`); the test itself needs a real device with
 * real players, so every environment-dependent branch self-skips via [assumeTrue]
 * instead of failing. Written against a Huawei AGS6-W09 (API 29) carrying Yandex
 * Music, Zvuk and VK Music.
 *
 * What a device adds that a JVM fake cannot: the real package ids resolve, the
 * OEM's real `enabled_notification_listeners` string is matched, real
 * MediaSession action masks are decoded, the real MediaBrowserService table is
 * enumerated AND bound (T7 — no permission, no activity start), and each
 * player's legacy `MEDIA_PLAY_FROM_SEARCH` advertisement is resolved (T6).
 *
 * API-29 LIMITATION (honest): package-visibility filtering via manifest
 * `<queries>` only exists from Android 11 (API 30). On API 29 every installed
 * package is visible to `getLaunchIntentForPackage`/`queryIntentServices`
 * regardless of the allowlist, so the T1/T5 assertions below prove the target
 * package IDs are correct and resolvable, but do NOT exercise the API-30+
 * filter. They still fail if an id is typo'd or a player renames itself.
 */
@RunWith(AndroidJUnit4::class)
class MediaTransportDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val packageManager get() = context.packageManager

    /**
     * Installed-ness must NOT be probed through `getLaunchIntentForPackage` —
     * that is the very API under test in T1. A direct PackageManager lookup is
     * independent of the visibility/allowlist behaviour.
     */
    private fun isInstalled(pkg: String): Boolean =
        runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess

    // ------------------------------------------------------------------
    // T1 — package visibility: the allowlist must name real package ids
    // ------------------------------------------------------------------

    @Test
    fun vkMusic_launchIntent_resolvesViaRealApplicationId() {
        assumeTrue("VK Music ($VK_MUSIC) is not installed", isInstalled(VK_MUSIC))
        assertNotNull(
            "getLaunchIntentForPackage($VK_MUSIC) must be non-null when VK Music is installed",
            packageManager.getLaunchIntentForPackage(VK_MUSIC),
        )
    }

    @Test
    fun vkMusic_codeNamespace_isNotALaunchablePackage() {
        // com.vk.music is VK's code namespace, not an installed applicationId.
        // This pins that the allowlist uses the real id and that no app claims
        // the namespace as a package.
        assertNull(
            "com.vk.music is a code namespace and must not resolve to a package",
            packageManager.getLaunchIntentForPackage(VK_CODE_NAMESPACE),
        )
    }

    @Test
    fun yandexMusic_launchIntent_resolves() {
        assumeTrue("Yandex Music ($YANDEX_MUSIC) is not installed", isInstalled(YANDEX_MUSIC))
        assertNotNull(
            "getLaunchIntentForPackage($YANDEX_MUSIC) must be non-null when installed",
            packageManager.getLaunchIntentForPackage(YANDEX_MUSIC),
        )
    }

    @Test
    fun zvuk_launchIntent_resolves() {
        assumeTrue("Zvuk ($ZVUK) is not installed", isInstalled(ZVUK))
        assertNotNull(
            "getLaunchIntentForPackage($ZVUK) must be non-null when installed",
            packageManager.getLaunchIntentForPackage(ZVUK),
        )
    }

    // ------------------------------------------------------------------
    // T2 — notification-listener access must agree with the raw setting
    // ------------------------------------------------------------------

    @Test
    fun notificationListenerAccess_agreesWithRawSecureSetting() {
        val gateway = AndroidMediaGateway(context)
        val component = ComponentName(context, JarvisNotificationListener::class.java)
        val raw = Settings.Secure.getString(context.contentResolver, ENABLED_LISTENERS)
        val independent = raw?.split(':').orEmpty().any {
            NotificationListenerComponent.matches(it, component.packageName, component.className)
        }
        val gatewaySays = gateway.hasNotificationListenerAccess()

        // Device-independent assertion: the gateway is not a hardcoded true/false;
        // it must mirror an independent read of the same setting. This is what
        // exercises the M-6 tolerance of both flattened ComponentName forms on a
        // real OEM ROM (a device may legitimately have the access disabled).
        assertEquals(
            "gateway access flag must agree with the raw enabled_notification_listeners setting",
            independent,
            gatewaySays,
        )
        if (independent) {
            assertTrue(
                "when the raw setting names our listener, the gateway must report access",
                gatewaySays,
            )
        }
    }

    // ------------------------------------------------------------------
    // T3 — real MediaSession enumeration + capability decoding
    // ------------------------------------------------------------------

    @Test
    fun activeSessions_exposeInstalledPackages_andDecodeCapabilities() {
        val handles = AndroidMediaGateway(context).activeControllers()
        assumeTrue("no active media sessions on the device", handles.isNotEmpty())

        val decoded = handles.map { it to it.capabilities() }
        for ((handle, caps) in decoded) {
            assertTrue(
                "session package ${handle.packageName} must be a real installed package",
                isInstalled(handle.packageName),
            )
            assertEquals(
                "known must mirror a non-zero mask for ${handle.packageName}",
                caps.mask != 0L,
                caps.known,
            )
        }

        // A session that has published a PlaybackState decodes to a known mask.
        // Sessions are transient; skip the decode assertion rather than fail on
        // an idle device.
        val withState = decoded.filter { it.second.known }
        assumeTrue("no session currently publishes a PlaybackState", withState.isNotEmpty())
        for ((handle, caps) in withState) {
            assertTrue(
                "decoded capabilities for ${handle.packageName} must carry the raw mask",
                caps.mask != 0L,
            )
            assertTrue(
                "supported actions for ${handle.packageName} must pass supports()",
                caps.supported.all { caps.supports(it) },
            )
        }
    }

    // ------------------------------------------------------------------
    // T4 — capability-gate honesty against a REAL mask
    // ------------------------------------------------------------------

    /**
     * Feeds each session's REAL decoded capabilities through the actual gating
     * path ([TransportControl] + [MusicPlaybackOrchestrator.TransportPolicy])
     * without ever dispatching to the device session: [ReadOnlyHandle] delegates
     * only the read calls and answers every transport command locally. So this
     * asserts the gate's decision, not the player's reaction.
     *
     * Skips when there is no live session to decode (sessions are transient).
     */
    @Test
    fun likeGate_isSelfConsistentWithRealDecodedCapabilities() {
        val handles = AndroidMediaGateway(context).activeControllers()
        assumeTrue("no active media sessions to inspect", handles.isNotEmpty())

        assertEquals(
            "LIKE must require the SET_RATING session bit",
            setOf(TransportAction.SET_RATING),
            MusicPlaybackOrchestrator.TransportPolicy.requiredActions(MusicPlaybackOrchestrator.Action.LIKE),
        )

        for (handle in handles) {
            val readOnly = ReadOnlyHandle(handle)
            val caps = readOnly.capabilities()
            val outcome = runBlocking {
                TransportControl(StubGateway(listOf(readOnly)), NoPlayerResolver, DEVICE_API_LEVEL)
                    .control(MusicPlaybackOrchestrator.ControlSpec(MusicPlaybackOrchestrator.Action.LIKE), null)
            }

            if (!caps.known) {
                // M-3 fail-open: an unpublished mask must never refuse LIKE.
                assertNotEquals(
                    "an unpublished mask must fail open, not refuse LIKE (${handle.packageName})",
                    "unsupported",
                    outcome.strategy,
                )
            } else if (MusicPlaybackOrchestrator.TransportPolicy.likeAllowed(caps)) {
                // Rating type is HEART: the command reaches the session.
                assertFalse(
                    "LIKE allowed by a heart rating type must not be refused (${handle.packageName})",
                    outcome.isError,
                )
            } else {
                // Real mask says "not a heart rating style" — refuse honestly.
                assertTrue(
                    "LIKE must be refused when the real rating type is not heart (${handle.packageName})",
                    outcome.isError,
                )
                assertEquals("unsupported", outcome.strategy)
            }

            if (caps.known && !caps.supports(TransportAction.SET_RATING)) {
                // M-3/M-7: an absent SET_RATING bit must never yield a *confirmed*
                // success. The confirmed/unconfirmed distinction lives in the
                // private lowConfidence flag and is mirrored only in Outcome.detail,
                // so the publicly observable guarantee pinned here is "not the
                // confident-success detail template" (refusal is the other honest
                // outcome).
                assertTrue(
                    "LIKE without SET_RATING must be refused or flagged unconfirmed (${handle.packageName})",
                    outcome.isError || outcome.detail != "Команда отправлена плееру (${handle.packageName}).",
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // T5 — MediaBrowserService advertisement per installed player
    // ------------------------------------------------------------------

    @Test
    fun yandexMusic_advertisesMediaBrowserService() {
        assertAdvertisesBrowserService(YANDEX_MUSIC)
    }

    @Test
    fun zvuk_advertisesMediaBrowserService() {
        assertAdvertisesBrowserService(ZVUK)
    }

    @Test
    fun vkMusic_advertisesMediaBrowserService() {
        assertAdvertisesBrowserService(VK_MUSIC)
    }

    private fun assertAdvertisesBrowserService(pkg: String) {
        assumeTrue("$pkg is not installed", isInstalled(pkg))
        val services = packageManager.queryIntentServices(
            Intent(MediaBrowserService.SERVICE_INTERFACE),
            0,
        ).filter { it.serviceInfo?.packageName == pkg }
        assertTrue(
            "$pkg must advertise at least one ${MediaBrowserService.SERVICE_INTERFACE}",
            services.isNotEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // T6 — legacy MEDIA_PLAY_FROM_SEARCH activity advertisement
    // ------------------------------------------------------------------

    /**
     * Resolution-only probe — it NEVER dispatches the intent, so no activity
     * is started and the device state is untouched. The package-scoped form
     * (`.setPackage(pkg)`) is deliberate: an unscoped query would also match
     * the system resolver and prove nothing about the player.
     */
    private fun legacySearchActivityCount(pkg: String): Int =
        packageManager.queryIntentActivities(
            Intent(LEGACY_SEARCH_ACTION).setPackage(pkg),
            0,
        ).size

    @Test
    fun yandexMusic_advertisesLegacySearchActivity() {
        assumeTrue("Yandex Music ($YANDEX_MUSIC) is not installed", isInstalled(YANDEX_MUSIC))
        assertTrue(
            "Yandex Music must advertise $LEGACY_SEARCH_ACTION",
            legacySearchActivityCount(YANDEX_MUSIC) >= 1,
        )
    }

    @Test
    fun zvuk_advertisesLegacySearchActivity() {
        assumeTrue("Zvuk ($ZVUK) is not installed", isInstalled(ZVUK))
        assertTrue(
            "Zvuk must advertise $LEGACY_SEARCH_ACTION",
            legacySearchActivityCount(ZVUK) >= 1,
        )
    }

    @Test
    fun vkMusic_shipsNoLegacySearchActivity() {
        assumeTrue("VK Music ($VK_MUSIC) is not installed", isInstalled(VK_MUSIC))
        // MEASURED TRUTH on the target device: VK Music advertises ZERO
        // MEDIA_PLAY_FROM_SEARCH activities. This is expected asymmetry, not a
        // bug — VK relies on the session/browser lanes and the S4 legacy-intent
        // strategy is a clean miss for it. Asserting the absence documents that
        // shape instead of looking like a missing positive test.
        assertEquals(
            "VK Music is measured to ship no $LEGACY_SEARCH_ACTION activity",
            0,
            legacySearchActivityCount(VK_MUSIC),
        )
    }

    // ------------------------------------------------------------------
    // T7 — real MediaBrowserService bind handshake (permission-free)
    // ------------------------------------------------------------------

    private fun installedPlayers(): List<String> =
        listOf(YANDEX_MUSIC, ZVUK, VK_MUSIC).filter { isInstalled(it) }

    @Test
    fun browserDiscover_listsInstalledPlayers() {
        val gateway = AndroidMediaBrowserGateway(context)
        val services = gateway.discover()
        assumeTrue("device advertises no MediaBrowserService", services.isNotEmpty())

        for (service in services) {
            assertTrue(
                "discovered browser service ${service.packageName} must be a real installed package",
                isInstalled(service.packageName),
            )
        }

        val installed = installedPlayers()
        assumeTrue("none of the target players is installed", installed.isNotEmpty())
        val discovered = services.map { it.packageName }.toSet()
        for (pkg in installed) {
            assertTrue("discover() must list the installed player $pkg", pkg in discovered)
        }
    }

    @Test
    fun browserConnect_handshakeIsHonestAndCleansUp() {
        val gateway = AndroidMediaBrowserGateway(context)
        val installed = installedPlayers()
        assumeTrue("none of the target players is installed", installed.isNotEmpty())

        // connect() returning null is a LEGITIMATE outcome (the service refused
        // a null root, or the bind did not settle) — never a failure. The
        // invariant asserted is: WHEN a session is handed out, it belongs to the
        // package we asked for, and every bind is cleaned up.
        //
        // Bind from Dispatchers.IO DELIBERATELY — this is the PRODUCTION thread.
        // MediaBrowserCompat's constructor builds a no-arg android.os.Handler,
        // which throws on a thread without a Looper; production reaches connect()
        // from TurnRunner's Dispatchers.IO tool lane. The gateway now hops the
        // construction to the main looper internally, so binding from IO must
        // SUCCEED — and before that hop existed this test failed (every bind
        // returned null, because the throw was swallowed into a plain null).
        // Binding on Main instead would pass either way and guard nothing.
        var connectedCount = 0
        for (pkg in installed) {
            val session = runCatching {
                runBlocking { withContext(Dispatchers.IO) { gateway.connect(pkg, BROWSER_CONNECT_TIMEOUT_MS) } }
            }.getOrNull() ?: continue
            connectedCount++
            try {
                assertEquals("bound session must belong to the requested package", pkg, session.packageName)
                // controller() needs NO permission, but the service MAY withhold
                // a token — assert the handle's package only when handed one.
                // MediaControllerCompat builds no Handler, so no Looper hop is
                // needed here (asserted by running it off the main thread).
                val handle = runCatching {
                    runBlocking { withContext(Dispatchers.IO) { session.controller() } }
                }.getOrNull()
                handle?.let {
                    assertEquals("session token controller must be for $pkg", pkg, it.packageName)
                }
            } finally {
                session.disconnect()
                session.disconnect() // documented idempotent — must not throw
            }
        }

        // Honest skip: if every bind returned null the per-session invariants
        // above never ran, so do not report a vacuous PASS.
        assumeTrue("no installed player accepted the browser bind", connectedCount > 0)
    }

    /**
     * Regression guard for the threading defect above, stated as its own case so
     * the failure mode is unambiguous: the FIRST bind attempt in the process must
     * happen on a Looper-less thread and still connect. Kept separate from
     * [browserConnect_handshakeIsHonestAndCleansUp] because that test tolerates
     * null (honest degradation); this one pins that null-everywhere is a BUG, not
     * a player behaviour.
     */
    @Test
    fun browserConnect_worksFromLooperlessProductionThread() {
        val gateway = AndroidMediaBrowserGateway(context)
        val installed = installedPlayers()
        assumeTrue("none of the target players is installed", installed.isNotEmpty())

        val results = installed.associateWith { pkg ->
            runCatching {
                runBlocking { withContext(Dispatchers.IO) { gateway.connect(pkg, BROWSER_CONNECT_TIMEOUT_MS) } }
            }.getOrNull().also { it?.disconnect() }
        }

        assertTrue(
            "at least one installed player MUST bind from the production (Looper-less) " +
                "thread; all-null means the Looper hop regressed. results=$results",
            results.values.any { it != null },
        )
    }

    private companion object {
        const val VK_MUSIC = "com.uma.musicvk"
        const val VK_CODE_NAMESPACE = "com.vk.music"
        const val YANDEX_MUSIC = "ru.yandex.music"
        const val ZVUK = "com.zvooq.openplay"
        const val ENABLED_LISTENERS = "enabled_notification_listeners"
        const val LEGACY_SEARCH_ACTION = "android.media.action.MEDIA_PLAY_FROM_SEARCH"

        /** MediaBrowserService bind budget — a hung service must not wedge the test. */
        const val BROWSER_CONNECT_TIMEOUT_MS = 5_000L

        /** The connected device's API level; only LIKE gating is probed here. */
        const val DEVICE_API_LEVEL = 29
    }
}

/**
 * Read-only [MediaControllerHandle] wrapper: reads delegate to the live session,
 * every transport command is answered locally and NEVER forwarded. That lets
 * [MediaTransportDeviceTest.likeGate_isSelfConsistentWithRealDecodedCapabilities]
 * run the real gate against real capabilities with zero risk of mutating the
 * user's playback.
 */
private class ReadOnlyHandle(
    private val delegate: MediaControllerHandle,
) : MediaControllerHandle {

    override val packageName: String get() = delegate.packageName

    override fun snapshot(): NowPlaying = delegate.snapshot()

    override fun capabilities(): MediaCapabilities = delegate.capabilities()

    override fun playFromSearch(query: String): Boolean = true

    override fun play(): Boolean = true

    override fun pause(): Boolean = true

    override fun skipToNext(): Boolean = true

    override fun skipToPrevious(): Boolean = true

    override fun stop(): Boolean = true

    /** Simulates a session that accepted the command, without touching it. */
    override fun like(): Boolean = true
}

/** Minimal gateway exposing a fixed handle list to [TransportControl]. */
private class StubGateway(
    private val handles: List<MediaControllerHandle>,
) : MediaGateway {

    override fun hasNotificationListenerAccess(): Boolean = true

    override fun activeControllers(): List<MediaControllerHandle> = handles

    override fun dispatchMediaKey(keyCode: Int) = Unit

    override fun openAppSearch(app: MediaAppInfo, query: String): Boolean = false

    override fun launchApp(app: MediaAppInfo): Boolean = false
}

/** No player hint is passed in the LIKE probe, so this is never consulted. */
private object NoPlayerResolver : MusicAppResolver {
    override fun resolve(appHint: String?): MediaAppInfo? = null
}
