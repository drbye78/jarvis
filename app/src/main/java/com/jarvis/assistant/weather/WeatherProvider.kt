package com.jarvis.assistant.weather

import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * The selectable weather data providers. Mirrors
 * [com.jarvis.assistant.settings.ProviderSettings.Type] for the LLM lane: an
 * enum with a stable storage [id], plus a tolerant parser so an unknown or
 * blank stored value degrades to the default instead of crashing.
 *
 * [id] is what lands in `AppPrefs.weatherProvider`; the enum name is NOT
 * persisted, so renaming a constant cannot orphan a stored preference.
 */
enum class WeatherProvider(val id: String) {
    /**
     * Free, keyless REST; geocodes + daily forecast in one hop. Selectable,
     * but NOT the default: `api.open-meteo.com` is DPI-blocked from Russian
     * networks, so a fresh Russian install would hang then fail.
     */
    OPEN_METEO("open_meteo"),

    /**
     * Free MCP endpoint over NOAA GFS; hourly, aggregated on-device.
     * The default — the host is Russian-hosted, so it survives RKN egress
     * blocking.
     */
    PROJECT_EOL("project_eol"),
    ;

    companion object {
        /**
         * The provider a fresh install uses; MUST match
         * [com.jarvis.assistant.util.AppPrefs.DEFAULT_WEATHER_PROVIDER], which a
         * test pins so the two cannot silently diverge.
         */
        val DEFAULT = PROJECT_EOL

        /** Tolerant parse: null/blank/unknown → [DEFAULT]. */
        fun fromId(raw: String?): WeatherProvider =
            entries.firstOrNull { it.id == raw?.trim()?.lowercase() } ?: DEFAULT
    }
}

/**
 * Routes a [WeatherQuery] to the provider the user selected, with a bounded
 * failover to the other one when the selected provider is UNREACHABLE.
 *
 * The selection is read through [providerFor] on EVERY call rather than
 * snapshotted, so the Settings radio takes effect on the next weather turn
 * without a graph rebuild ([com.jarvis.assistant.settings.ApplyPolicy.LIVE]).
 * The `when` is exhaustive with NO `else`: adding a provider becomes a compile
 * error until it is wired here, the same idiom as the LLM-provider `when` in
 * `AppGraph`.
 *
 * FAILOVER POLICY (network-class failures only):
 *  - The selected provider is attempted first; if it is in the negative cache
 *    it is moved to the BACK but still attempted last (never dropped).
 *  - Each attempt is bounded by [attemptTimeoutMs] via `withTimeoutOrNull`,
 *    NEVER `withTimeout`: `TimeoutCancellationException` is a
 *    `CancellationException`, and both clients deliberately rethrow
 *    cancellation for barge-in, so `withTimeout` would make a slow provider
 *    look like a user interrupt. `withTimeoutOrNull` returning null is our own
 *    timeout (treated as unreachable) while a genuine outer cancellation still
 *    propagates.
 *  - An [WeatherOutcome.Error] with `unreachable = false` is a real answer and
 *    is returned immediately — no failover.
 *  - After an unreachable result the provider is marked degraded (the instant
 *    of failure is recorded) for [degradedTtlMs], so the NEXT call prefers the
 *    healthy one. A SUCCESSFUL attempt CLEARS the demotion immediately.
 *  - When BOTH providers are degraded, the user's SELECTED provider stays
 *    first — never prefer a fallback that may itself be the blocked one.
 */
class SelectingWeatherClient(
    private val providerFor: () -> WeatherProvider,
    private val openMeteo: WeatherClient,
    private val projectEol: WeatherClient,
    private val attemptTimeoutMs: Long = 6_000,
    private val degradedTtlMs: Long = 600_000,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : WeatherClient {

    /** provider → epoch-ms instant of its last unreachable failure (thread-safe: graph-scoped). */
    private val degradedAt = ConcurrentHashMap<WeatherProvider, Long>()

    override suspend fun getWeatherOutcome(query: WeatherQuery): WeatherOutcome {
        var lastUnreachable: WeatherOutcome.Error? = null
        for (provider in orderFor(providerFor())) {
            when (val outcome = attempt(provider, query)) {
                is WeatherOutcome.Ok -> {
                    degradedAt.remove(provider) // recovery: drop any stale demotion
                    return outcome
                }

                is WeatherOutcome.Error -> {
                    if (!outcome.unreachable) return outcome
                    degradedAt[provider] = nowMs()
                    Timber.tag(LOG_TAG).d("provider %s unreachable; failing over", provider.id)
                    lastUnreachable = outcome
                }
            }
        }
        return lastUnreachable ?: WeatherOutcome.Error(UNREACHABLE, unreachable = true)
    }

    /** The selected provider first, the other second; a degraded provider moves back. */
    private fun orderFor(preferred: WeatherProvider): List<WeatherProvider> {
        val other = otherThan(preferred)
        if (!isDegraded(preferred)) return listOf(preferred, other)
        // Preferred is demoted. If the fallback is healthy, honour the demotion;
        // if BOTH are demoted, keep the user's choice first rather than prefer a
        // fallback that may itself be the blocked one.
        return if (isDegraded(other)) listOf(preferred, other) else listOf(other, preferred)
    }

    /** True while [provider]'s last failure is newer than [degradedTtlMs]. */
    private fun isDegraded(provider: WeatherProvider): Boolean {
        val at = degradedAt[provider] ?: return false
        return nowMs() - at < degradedTtlMs
    }

    /** One bounded attempt; a timeout is our own and counts as unreachable. */
    private suspend fun attempt(provider: WeatherProvider, query: WeatherQuery): WeatherOutcome {
        val outcome = withTimeoutOrNull(attemptTimeoutMs) { clientFor(provider).getWeatherOutcome(query) }
        if (outcome == null) {
            Timber.tag(LOG_TAG).d("provider %s timed out; failing over", provider.id)
            return WeatherOutcome.Error(UNREACHABLE, unreachable = true)
        }
        return outcome
    }

    private fun clientFor(provider: WeatherProvider): WeatherClient = when (provider) {
        WeatherProvider.OPEN_METEO -> openMeteo
        WeatherProvider.PROJECT_EOL -> projectEol
    }

    /** Exhaustive `when` with NO `else` (house idiom): a new provider is a compile error. */
    private fun otherThan(provider: WeatherProvider): WeatherProvider = when (provider) {
        WeatherProvider.OPEN_METEO -> WeatherProvider.PROJECT_EOL
        WeatherProvider.PROJECT_EOL -> WeatherProvider.OPEN_METEO
    }

    private companion object {
        const val LOG_TAG = "WeatherProvider"
        const val UNREACHABLE = "Weather service unreachable"
    }
}
