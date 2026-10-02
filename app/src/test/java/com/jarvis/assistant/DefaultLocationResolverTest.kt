package com.jarvis.assistant

import com.jarvis.assistant.location.DefaultLocationResolver
import com.jarvis.assistant.location.LocationFix
import com.jarvis.assistant.location.LocationOutcome
import com.jarvis.assistant.location.LocationProvider
import com.jarvis.assistant.location.ResolvedLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared location precedence seam, pinned on the already-written
 * [DefaultLocationResolver] with a fake [LocationProvider]:
 *
 * - a configured location ALWAYS wins and the provider is never consulted;
 * - `resolveDevice()` ignores a configured city and always uses a device fix
 *   (the explicit «где я» path), with the same failure/cancellation semantics;
 * - a missing permission degrades to `PermissionDenied` without a fix request;
 * - a usable fix resolves to coordinates with the localized label;
 * - every provider failure (null or a thrown non-cancellation exception)
 *   degrades to `Unavailable` — a lookup must never crash;
 * - `CancellationException` (barge-in) propagates rather than turning into
 *   `Unavailable`;
 * - the configured value is trimmed (whitespace-only counts as blank).
 */
class DefaultLocationResolverTest {

    /** Scriptable provider: records how often a fix was requested. */
    private class FakeProvider(
        var granted: Boolean = true,
        var fix: LocationFix? = LocationFix(43.5855, 39.7231, 500L, "gps"),
        var failure: Exception? = null,
    ) : LocationProvider {

        var fixCalls = 0
            private set

        override fun hasPermission(): Boolean = granted

        override suspend fun getFix(maxAgeMs: Long, timeoutMs: Long): LocationFix? {
            fixCalls++
            failure?.let { throw it }
            return fix
        }
    }

    private fun resolver(
        configured: String,
        provider: LocationProvider,
        label: String = "текущее местоположение",
    ) = DefaultLocationResolver(
        configuredLocation = { configured },
        provider = provider,
        coordsLabel = { label },
        maxAgeMs = 600_000L,
        timeoutMs = 3_000L,
    )

    @Test
    fun `configured location wins and never consults the provider`() = runTest {
        // Permission and a fresh fix are both available: GPS must still lose.
        val provider = FakeProvider(granted = true, fix = LocationFix(1.0, 2.0, 10L, "gps"))
        val outcome = resolver("Сочи", provider).resolve()

        assertEquals(LocationOutcome.Resolved(ResolvedLocation.Place("Сочи")), outcome)
        assertEquals(0, provider.fixCalls)
    }

    @Test
    fun `blank configured plus no permission is PermissionDenied without a fix request`() = runTest {
        val provider = FakeProvider(granted = false)
        val outcome = resolver("", provider).resolve()

        assertEquals(LocationOutcome.PermissionDenied, outcome)
        assertEquals(0, provider.fixCalls)
    }

    @Test
    fun `blank configured with permission and a fix resolves to coords with the label`() = runTest {
        val provider = FakeProvider(fix = LocationFix(43.5855, 39.7231, 500L, "gps"))
        val outcome = resolver("", provider, label = "текущее местоположение").resolve()

        assertEquals(
            LocationOutcome.Resolved(ResolvedLocation.Coords(43.5855, 39.7231, "текущее местоположение")),
            outcome,
        )
        assertEquals(1, provider.fixCalls)
    }

    @Test
    fun `blank configured with permission but no fix is Unavailable`() = runTest {
        val provider = FakeProvider(fix = null)
        val outcome = resolver("", provider).resolve()

        assertEquals(LocationOutcome.Unavailable, outcome)
        assertEquals(1, provider.fixCalls)
    }

    @Test
    fun `a throwing provider degrades to Unavailable instead of crashing the turn`() = runTest {
        val provider = FakeProvider(failure = IllegalStateException("no provider"))
        val outcome = resolver("", provider).resolve()

        assertEquals(LocationOutcome.Unavailable, outcome)
        assertEquals(1, provider.fixCalls)
    }

    @Test
    fun `cancellation from the provider propagates and is never swallowed`() = runTest {
        val provider = FakeProvider(failure = CancellationException("barge-in"))
        var propagated = false

        try {
            resolver("", provider).resolve()
        } catch (e: CancellationException) {
            propagated = true
        }

        assertTrue("barge-in must not degrade into Unavailable", propagated)
    }

    @Test
    fun `configured location is trimmed`() = runTest {
        val provider = FakeProvider(granted = false)
        val outcome = resolver("  Сочи ", provider).resolve()

        assertEquals(LocationOutcome.Resolved(ResolvedLocation.Place("Сочи")), outcome)
        assertEquals(0, provider.fixCalls)
    }

    @Test
    fun `whitespace-only configured counts as blank`() = runTest {
        val provider = FakeProvider(granted = false)
        val outcome = resolver("   ", provider).resolve()

        assertEquals(LocationOutcome.PermissionDenied, outcome)
        assertEquals(0, provider.fixCalls)
    }

    // ---- resolveDevice: the explicit «где я» path -------------------------

    @Test
    fun `resolveDevice ignores a configured city and returns the device fix`() = runTest {
        // The owner decision: an explicit «где я» must NOT be answered with the
        // configured city — otherwise the device fix is dead code whenever one
        // is set (the old resolve() behaviour).
        val provider = FakeProvider(granted = true, fix = LocationFix(55.75, 37.61, 10L, "network"))
        val outcome = resolver("Сочи", provider, label = "текущее местоположение").resolveDevice()

        assertEquals(
            LocationOutcome.Resolved(ResolvedLocation.Coords(55.75, 37.61, "текущее местоположение")),
            outcome,
        )
        assertEquals("the provider must be consulted despite the configured city", 1, provider.fixCalls)
    }

    @Test
    fun `resolve still returns the configured place unchanged`() = runTest {
        // Guard against a resolverDevice refactor leaking into resolve().
        val provider = FakeProvider(granted = true, fix = LocationFix(55.75, 37.61, 10L, "network"))
        val outcome = resolver("Сочи", provider).resolve()

        assertEquals(LocationOutcome.Resolved(ResolvedLocation.Place("Сочи")), outcome)
        assertEquals(0, provider.fixCalls)
    }

    @Test
    fun `resolveDevice with no permission is PermissionDenied without a fix request`() = runTest {
        val provider = FakeProvider(granted = false)
        val outcome = resolver("Сочи", provider).resolveDevice()

        assertEquals(LocationOutcome.PermissionDenied, outcome)
        assertEquals(0, provider.fixCalls)
    }

    @Test
    fun `resolveDevice with no usable fix is Unavailable`() = runTest {
        val provider = FakeProvider(fix = null)
        val outcome = resolver("Сочи", provider).resolveDevice()

        assertEquals(LocationOutcome.Unavailable, outcome)
        assertEquals(1, provider.fixCalls)
    }

    @Test
    fun `resolveDevice propagates cancellation`() = runTest {
        val provider = FakeProvider(failure = CancellationException("barge-in"))
        var propagated = false

        try {
            resolver("Сочи", provider).resolveDevice()
        } catch (e: CancellationException) {
            propagated = true
        }

        assertTrue("barge-in must not degrade into Unavailable", propagated)
    }
}
