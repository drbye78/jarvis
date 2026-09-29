package com.jarvis.assistant

import com.jarvis.assistant.location.ResolvedLocation
import com.jarvis.assistant.weather.SelectingWeatherClient
import com.jarvis.assistant.weather.WeatherClient
import com.jarvis.assistant.weather.WeatherOutcome
import com.jarvis.assistant.weather.WeatherProvider
import com.jarvis.assistant.weather.WeatherQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Weather failover: a network-class failure of the selected provider must fall
 * through to the other one, while a reachable server's LOGICAL answer must not,
 * and a genuine barge-in cancellation must keep propagating.
 */
class SelectingWeatherClientTest {

    private val query = WeatherQuery(ResolvedLocation.Place("Москва"), 1)

    private fun unreachable() = WeatherOutcome.Error("Weather service unreachable", unreachable = true)

    /** A fake with a mutable outcome and a call counter. */
    private class FakeClient(var outcome: WeatherOutcome) : WeatherClient {
        var calls = 0

        override suspend fun getWeatherOutcome(query: WeatherQuery): WeatherOutcome {
            calls++
            return outcome
        }
    }

    /** Records the order in which providers are attempted. */
    private class RecordingClient(
        var outcome: WeatherOutcome,
        private val order: MutableList<String>,
        private val label: String,
    ) : WeatherClient {
        override suspend fun getWeatherOutcome(query: WeatherQuery): WeatherOutcome {
            order += label
            return outcome
        }
    }

    /** Answers only after [delayMs], so the selector's own timeout fires. */
    private class SlowClient(private val delayMs: Long) : WeatherClient {
        override suspend fun getWeatherOutcome(query: WeatherQuery): WeatherOutcome {
            delay(delayMs)
            return WeatherOutcome.Ok("slow")
        }
    }

    /** Simulates a barge-in interrupt, which must never be mistaken for a timeout. */
    private class CancellingClient : WeatherClient {
        override suspend fun getWeatherOutcome(query: WeatherQuery): WeatherOutcome =
            throw CancellationException("barge-in")
    }

    @Test
    fun `an unreachable preferred provider fails over to the other`() = runBlocking {
        val selecting = SelectingWeatherClient(
            providerFor = { WeatherProvider.OPEN_METEO },
            openMeteo = FakeClient(unreachable()),
            projectEol = FakeClient(WeatherOutcome.Ok("project-eol-doc")),
        )

        assertEquals("project-eol-doc", selecting.getWeather(query))
    }

    @Test
    fun `a logical error is a real answer and is not failed over`() = runBlocking {
        val fallback = FakeClient(WeatherOutcome.Ok("project-eol-doc"))
        val selecting = SelectingWeatherClient(
            providerFor = { WeatherProvider.OPEN_METEO },
            openMeteo = FakeClient(WeatherOutcome.Error("Location not found: Атлантида", unreachable = false)),
            projectEol = fallback,
        )

        val out = selecting.getWeather(query)

        assertTrue("the real answer must be returned as-is", out.contains("Location not found"))
        assertEquals("no failover for a reachable server's answer", 0, fallback.calls)
    }

    @Test
    fun `a timed-out attempt counts as unreachable and fails over`() = runBlocking {
        val selecting = SelectingWeatherClient(
            providerFor = { WeatherProvider.OPEN_METEO },
            openMeteo = SlowClient(delayMs = 10_000),
            projectEol = FakeClient(WeatherOutcome.Ok("project-eol-doc")),
            attemptTimeoutMs = 20,
        )

        assertEquals("project-eol-doc", selecting.getWeather(query))
    }

    @Test
    fun `genuine cancellation propagates and does not fail over`() {
        val fallback = FakeClient(WeatherOutcome.Ok("project-eol-doc"))
        val selecting = SelectingWeatherClient(
            providerFor = { WeatherProvider.OPEN_METEO },
            openMeteo = CancellingClient(),
            projectEol = fallback,
        )

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { selecting.getWeather(query) }
        }

        assertEquals("barge-in", thrown.message)
        assertEquals("a user interrupt must not trigger failover", 0, fallback.calls)
    }

    @Test
    fun `the negative cache moves a failing provider back until its TTL elapses`() = runBlocking {
        var now = 0L
        val order = mutableListOf<String>()
        val open = RecordingClient(unreachable(), order, "open")
        val eol = RecordingClient(WeatherOutcome.Ok("eol"), order, "eol")
        val selecting = SelectingWeatherClient(
            providerFor = { WeatherProvider.OPEN_METEO },
            openMeteo = open,
            projectEol = eol,
            degradedTtlMs = 1_000,
            nowMs = { now },
        )

        // First call: open is tried first, fails; eol answers.
        assertEquals("eol", selecting.getWeather(query))
        assertEquals(listOf("open", "eol"), order)

        // Still degraded: the healthy provider is now preferred and answers alone.
        order.clear()
        assertEquals("eol", selecting.getWeather(query))
        assertEquals(listOf("eol"), order)

        // TTL elapsed: open is preferred again.
        now = 1_001
        order.clear()
        open.outcome = WeatherOutcome.Ok("open")
        assertEquals("open", selecting.getWeather(query))
        assertEquals(listOf("open"), order)
    }

    @Test
    fun `a successful attempt clears the demotion`() = runBlocking {
        val order = mutableListOf<String>()
        val open = RecordingClient(unreachable(), order, "open")
        val eol = RecordingClient(unreachable(), order, "eol")
        val selecting = SelectingWeatherClient(
            providerFor = { WeatherProvider.OPEN_METEO },
            openMeteo = open,
            projectEol = eol,
            degradedTtlMs = 10_000,
            nowMs = { 0L },
        )

        // First call: the selected provider is attempted first and fails, then the
        // fallback also fails unreachable — so BOTH end up demoted.
        assertTrue(selecting.getWeather(query).contains("unreachable"))
        assertEquals(listOf("open", "eol"), order)

        // The selected provider recovers. It must be attempted FIRST and the
        // successful attempt must drop its demotion (not just return its document).
        open.outcome = WeatherOutcome.Ok("open-doc")
        order.clear()
        assertEquals("open-doc", selecting.getWeather(query))
        assertEquals(listOf("open"), order)
    }

    /**
     * Both providers demoted: the reorder must NOT flip the user's choice behind
     * a fallback that may itself be the blocked one.
     */
    @Test
    fun `both degraded keeps the user's choice first`() = runBlocking {
        val order = mutableListOf<String>()
        val open = RecordingClient(unreachable(), order, "open")
        val eol = RecordingClient(unreachable(), order, "eol")
        val selecting = SelectingWeatherClient(
            providerFor = { WeatherProvider.OPEN_METEO },
            openMeteo = open,
            projectEol = eol,
        )

        // Demote both: the first call tries the selected provider, then the fallback.
        assertTrue(selecting.getWeather(query).contains("unreachable"))
        assertEquals(listOf("open", "eol"), order)

        // Both are now demoted — the selected provider must still lead.
        order.clear()
        assertTrue(selecting.getWeather(query).contains("unreachable"))
        assertEquals("the user's selected provider must stay first", "open", order.first())
    }

    /**
     * FALSIFICATION: if failover were neutered (the selected provider's outcome
     * returned as-is), this observes the error document and the fallback is
     * never attempted — both below fail. It pins that failover is real, not
     * decorative.
     */
    @Test
    fun `FALSIFICATION - neutering failover breaks this test`() = runBlocking {
        val fallback = FakeClient(WeatherOutcome.Ok("project-eol-doc"))
        val selecting = SelectingWeatherClient(
            providerFor = { WeatherProvider.OPEN_METEO },
            openMeteo = FakeClient(unreachable()),
            projectEol = fallback,
        )

        val out = selecting.getWeather(query)

        assertNotEquals("""{"error":"Weather service unreachable"}""", out)
        assertEquals("project-eol-doc", out)
        assertEquals("the fallback provider must actually have been attempted", 1, fallback.calls)
    }
}
