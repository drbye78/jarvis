package com.jarvis.assistant.di

import com.jarvis.assistant.home.HomeAliasCodec
import com.jarvis.assistant.home.HomeBackend
import com.jarvis.assistant.home.HomeConfigCodec
import com.jarvis.assistant.home.HomeConfigDecodeResult
import com.jarvis.assistant.home.HomeDevice
import com.jarvis.assistant.home.HomeDeviceKey
import com.jarvis.assistant.home.HomeEventSource
import com.jarvis.assistant.home.HomeGrantCodec
import com.jarvis.assistant.home.HomeGrantStore
import com.jarvis.assistant.home.HomeProviderConfig
import com.jarvis.assistant.home.HomeProviderId
import com.jarvis.assistant.home.HomeRepository
import com.jarvis.assistant.home.HomeResult
import com.jarvis.assistant.home.HomeState
import com.jarvis.assistant.home.providers.ha.HaConnection
import com.jarvis.assistant.home.providers.ha.HomeAssistantClientProvider
import com.jarvis.assistant.home.providers.tuya.TuyaBackend
import com.jarvis.assistant.home.providers.tuya.TuyaClientProvider
import com.jarvis.assistant.home.providers.tuya.TuyaConnection
import com.jarvis.assistant.home.providers.tuya.TuyaRegion
import com.jarvis.assistant.tools.HomeTools
import com.jarvis.assistant.tools.ToolStrings
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.CredentialsStore
import timber.log.Timber

/**
 * Composition of the smart-home lane for the tool surface.
 *
 * Cheap to construct: it parses nothing and starts nothing until a home tool
 * actually runs (or the graph warm-up calls [refresh]). Discovery/state reads
 * are bounded and always run off the construction path.
 *
 * The backend factory is an exhaustive `when(HomeProviderId)` with NO `else`,
 * so a new provider is a COMPILE error until it is wired — the same idiom as
 * the LLM-provider `when` in [AppGraph]. Only Home Assistant is implemented in
 * this phase; Yandex/Tuya are later phases and honestly degrade to "not
 * implemented" (no backend, a DEBUG log, no device).
 *
 * The Home Assistant backend is process-scoped
 * ([HomeAssistantClientProvider]) because [AppGraph] is rebuilt on every
 * service start / watchdog revive: a per-graph backend would churn its
 * websocket. A changed provider list therefore needs a service restart (the
 * Settings apply-policy says so); the token is still read through a live
 * lambda so rotating it is picked up by the next request.
 */
class HomeGraph(private val appPrefs: AppPrefs) {

    /** The live catalog shared by the resolver, tools and (later) awareness. */
    val repository = HomeRepository()

    /** Throttles the invalid-config warning to once per graph (not per refresh). */
    private val warnedInvalid = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Enabled backends, keyed by provider identity. Lazily built so graph
     * construction performs no I/O and no process-scoped client creation.
     */
    private val backendMap: Map<HomeProviderId, HomeBackend> by lazy { buildBackends() }

    /** The active device backends (one per configured, implemented provider). */
    fun backends(): Map<HomeProviderId, HomeBackend> = backendMap

    /**
     * The push event source, when the configured HA backend supports it. Null
     * for poll-only providers (Yandex/Tuya) and when no backend is configured,
     * so the awareness lane degrades to silence rather than demanding a method
     * they cannot honour.
     */
    fun eventSource(): HomeEventSource? =
        backendMap[HomeProviderId.HOME_ASSISTANT] as? HomeEventSource

    /** Spoken-alias map, decoded LIVE from [AppPrefs.homeAliases]. */
    fun aliases(): Map<String, HomeDeviceKey> = HomeAliasCodec.decodeOrEmpty(appPrefs.homeAliases)

    /** Persisted grants, decoded LIVE from [AppPrefs.homeGrants]. */
    fun grants(): HomeGrantStore = HomeGrantStore(HomeGrantCodec.decodeOrEmpty(appPrefs.homeGrants))

    /** The injected, JVM-testable tool group over this graph's live pieces. */
    fun tools(strings: ToolStrings): HomeTools = HomeTools(
        repository = repository,
        aliases = { aliases() },
        backends = { backends() },
        grants = { grants() },
        strings = strings,
        refresh = { refresh() },
    )

    /**
     * Bounded discovery + state read across every enabled backend, merged into
     * [repository]. Per-backend failures are logged content-free and skipped;
     * a configuration failure yields the previous/empty catalog, never a throw.
     */
    suspend fun refresh() {
        val configs = enabledConfigs()
        if (configs.isEmpty()) return
        val devices = mutableListOf<HomeDevice>()
        for (config in configs) {
            val backend = backendFor(config) ?: continue
            when (val discovered = backend.discover()) {
                is HomeResult.Ok -> devices += discovered.value
                is HomeResult.Err -> Timber.d("Home discovery failed (%s): %s", config.provider, discovered.error)
            }
        }
        repository.replace(devices, readStates(devices))
    }

    private suspend fun readStates(devices: List<HomeDevice>): Map<HomeDeviceKey, HomeState> {
        val states = mutableMapOf<HomeDeviceKey, HomeState>()
        for ((provider, providerDevices) in devices.groupBy { it.key.provider }) {
            val backend = backendMap[provider] ?: continue
            val keys = providerDevices.map { it.key }.take(MAX_STATE_READS)
            when (val read = backend.readState(keys)) {
                is HomeResult.Ok -> read.value.forEach { states[it.key] = it }
                is HomeResult.Err -> Timber.d("Home state read failed (%s): %s", provider.id, read.error)
            }
        }
        return states
    }

    /** Enabled configs whose provider is recognized; malformed JSON yields none. */
    private fun enabledConfigs(): List<HomeProviderConfig> =
        when (val decoded = HomeConfigCodec.decode(appPrefs.homeProviders)) {
            HomeConfigDecodeResult.Empty -> emptyList()

            is HomeConfigDecodeResult.Invalid -> {
                // Content-free: never echo the blob (it can carry host URLs).
                if (warnedInvalid.compareAndSet(false, true)) {
                    Timber.w("Home provider config is invalid: %s", decoded.reason)
                }
                emptyList()
            }

            is HomeConfigDecodeResult.Ok ->
                decoded.configs.filter { it.enabled && HomeProviderId.fromId(it.provider) != null }
        }

    private fun buildBackends(): Map<HomeProviderId, HomeBackend> {
        val out = linkedMapOf<HomeProviderId, HomeBackend>()
        for (config in enabledConfigs()) {
            val backend = backendFor(config) ?: continue
            out.putIfAbsent(backend.provider, backend)
        }
        return out
    }

    /**
     * The exhaustive provider factory. A recognized provider always maps to a
     * branch; an unimplemented one maps to null WITH a log rather than being
     * silently dropped.
     */
    private fun backendFor(config: HomeProviderConfig): HomeBackend? {
        val provider = HomeProviderId.fromId(config.provider) ?: return null
        return when (provider) {
            HomeProviderId.HOME_ASSISTANT -> homeAssistantBackend(config)
            HomeProviderId.TUYA -> tuyaBackend(config)
            HomeProviderId.YANDEX -> notImplemented(provider)
        }
    }

    private fun homeAssistantBackend(config: HomeProviderConfig): HomeBackend =
        HomeAssistantClientProvider.get {
            HaConnection(
                baseUrl = config.baseUrl,
                token = CredentialsStore.get().homeSecret(config.id, HA_TOKEN_FIELD),
            )
        }

    /**
     * Tuya is cloud-only: the region (a compile-time host) and the linked-account
     * UID are non-secret config metadata, while the Access ID/Secret live in the
     * vault under `(config.id, field)`. The client is process-scoped per config
     * id so its token cache survives graph rebuilds.
     */
    private fun tuyaBackend(config: HomeProviderConfig): HomeBackend =
        TuyaBackend(
            client = TuyaClientProvider.get(config.id) {
                TuyaConnection(
                    region = TuyaRegion.fromId(config.metadata[TUYA_REGION_KEY]),
                    uid = config.metadata[TUYA_UID_KEY],
                    accessId = CredentialsStore.get().homeSecret(config.id, TUYA_ACCESS_ID_FIELD),
                    secret = CredentialsStore.get().homeSecret(config.id, TUYA_ACCESS_SECRET_FIELD),
                )
            },
            uid = { config.metadata[TUYA_UID_KEY] },
        )

    private fun notImplemented(provider: HomeProviderId): HomeBackend? {
        Timber.d("Home provider not implemented yet: %s", provider.id)
        return null
    }

    private companion object {
        const val HA_TOKEN_FIELD = "token"

        /** Tuya non-secret metadata keys (in [HomeProviderConfig.metadata]). */
        const val TUYA_REGION_KEY = "region"
        const val TUYA_UID_KEY = "uid"

        /** Tuya vault fields (in [CredentialsStore.homeSecret]). */
        const val TUYA_ACCESS_ID_FIELD = "access_id"
        const val TUYA_ACCESS_SECRET_FIELD = "access_secret"

        const val MAX_STATE_READS = 80
    }
}
