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
}
