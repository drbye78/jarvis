package com.jarvis.assistant.manage

import com.jarvis.assistant.audio.aec.AecMode
import com.jarvis.assistant.cognitive.embed.EmbedderChoice
import com.jarvis.assistant.config.ProviderSettings
import com.jarvis.assistant.mcp.McpServerConfig
import com.jarvis.assistant.mcp.McpServerConfigCodec
import com.jarvis.assistant.mcp.McpServerDecodeResult
import com.jarvis.assistant.settings.ApplyPolicy
import com.jarvis.assistant.settings.SettingsInventory
import com.jarvis.assistant.speech.SpeechBackend
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.SecretVault
import com.jarvis.assistant.weather.WeatherProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import timber.log.Timber

/**
 * The explicit, reflection-free bridge from one `SettingsInventory` key to its
 * real `AppPrefs` / vault accessor (R13 §14.4).
 *
 * Release is R8-minified, so a runtime-reflection walk over `AppPrefs` would
 * silently disappear from the shipped APK; instead every managed key names its
 * own getter/setter here. The [ManagementBindingsTest] coverage assertion is
 * the anti-drop guard: adding a key to `SettingsInventory` without a binding
 * fails the build.
 *
 * [category]/[policy]/[essential] are NOT restated — they are read from the
 * already-frozen `SettingsInventory` entry (and its `ApplyPolicies`) so the two
 * registries cannot drift. The `manage/` package importing `settings` is
 * deliberate: these two files ARE the bridge; the pure codec/hasher/session
 * files stay settings-free.
 *
 * A [secret] binding is write-only: [get] returns ONLY presence
 * ([ManagedValue.Bool]) — never the plaintext — while [set] writes the real
 * value to [secretVaultKey]. Secrets are read back (for an opt-in export) only
 * through [ManagementCore]'s vault handle.
 */
data class Binding(
    val key: String,
    val category: String,
    val type: ManagedSettingType,
    val policy: ManagedApplyPolicy,
    val essential: Boolean,
    val secret: Boolean,
    /**
     * The `SecretVault` slot this binding reads/writes when [secret] is true,
     * else null. It lives here (rather than being derived from [key]) because
     * the property name and the vault key differ (`picovoiceKey` vs
     * `picovoice_key`) and the constants are the single source of truth.
     */
    val secretVaultKey: String? = null,
    /**
     * The allowed persisted values for an [ManagedSettingType.ENUM] binding,
     * derived from the same enum/constant the [get]/[set] pair round-trips (so
     * the vocabulary cannot drift). Empty for every non-enum binding.
     */
    val options: List<String> = emptyList(),
    /** Reads the current value. Secret bindings return presence only. */
    val get: (AppPrefs) -> ManagedValue,
    /** Writes a value the caller has already type-checked against [type]. */
    val set: (AppPrefs, ManagedValue) -> Unit,
) {
    /** The metadata projection the REST/UI layer lists (values excluded). */
    fun toManagedSetting(): ManagedSetting =
        ManagedSetting(
            key = key,
            category = category,
            type = type,
            policy = policy,
            essential = essential,
            secret = secret,
            options = options,
        )
}

/**
 * All 45 managed settings, each bound to its real accessor.
 *
 * @param vault the secret store; injected so JVM tests can pass an
 *   [com.jarvis.assistant.util.InMemoryVault] and production the Keystore one.
 */
class ManagementBindings(private val vault: SecretVault) {

    /** Every binding, in `SettingsInventory` order. */
    val all: List<Binding> = listOf(
        // --- BRAIN (LLM provider) ---
        binding(
            key = "providerType",
            type = ManagedSettingType.ENUM,
            options = ProviderSettings.Type.entries.map { providerWire(it) },
            get = { p -> ManagedValue.EnumValue(providerWire(p.providerType)) },
            set = { p, v -> v.stringOrNull()?.let { p.providerType = providerFromWire(it) } },
        ),
        binding(
            key = "gigaChatModel",
            type = ManagedSettingType.STRING,
            get = { p -> ManagedValue.StringValue(p.gigaChatModel) },
            set = { p, v -> v.stringOrNull()?.let { p.gigaChatModel = it } },
        ),
        binding(
            key = "yandexModel",
            type = ManagedSettingType.STRING,
            get = { p -> ManagedValue.StringValue(p.yandexModel) },
            set = { p, v -> v.stringOrNull()?.let { p.yandexModel = it } },
        ),
        binding(
            key = "yandexFolderId",
            type = ManagedSettingType.STRING,
            get = { p -> ManagedValue.StringValue(p.yandexFolderId) },
            set = { p, v -> v.stringOrNull()?.let { p.yandexFolderId = it } },
        ),
        binding(
            key = "openAiBaseUrl",
            type = ManagedSettingType.STRING,
            get = { p -> ManagedValue.StringValue(p.openAiBaseUrl) },
            set = { p, v -> v.stringOrNull()?.let { p.openAiBaseUrl = it } },
        ),
        binding(
            key = "openAiModel",
            type = ManagedSettingType.STRING,
            get = { p -> ManagedValue.StringValue(p.openAiModel) },
            set = { p, v -> v.stringOrNull()?.let { p.openAiModel = it } },
        ),

        // --- SPEECH (backends + voice) ---
        binding(
            key = "speechBackend",
            type = ManagedSettingType.ENUM,
            options = SpeechBackend.entries.map { SpeechBackend.toPref(it) },
            get = { p -> ManagedValue.EnumValue(SpeechBackend.toPref(p.speechBackend)) },
            set = { p, v -> v.stringOrNull()?.let { p.speechBackend = SpeechBackend.fromPref(it) } },
        ),
        binding(
            key = "ttsVoice",
            type = ManagedSettingType.STRING,
            get = { p -> ManagedValue.StringValue(p.ttsVoice) },
            set = { p, v -> v.stringOrNull()?.let { p.ttsVoice = it } },
        ),
        binding(
            key = "yandexTtsVoice",
            type = ManagedSettingType.STRING,
            get = { p -> ManagedValue.StringValue(p.yandexTtsVoice) },
            set = { p, v -> v.stringOrNull()?.let { p.yandexTtsVoice = it } },
        ),
        binding(
            key = "yandexTtsRole",
            type = ManagedSettingType.STRING,
            get = { p -> ManagedValue.StringValue(p.yandexTtsRole) },
            set = { p, v -> v.stringOrNull()?.let { p.yandexTtsRole = it } },
        ),
        binding(
            key = "yandexTtsSpeed",
            type = ManagedSettingType.FLOAT,
            get = { p -> ManagedValue.FloatValue(p.yandexTtsSpeed.toDouble()) },
            set = { p, v -> (v as? ManagedValue.FloatValue)?.value?.let { p.yandexTtsSpeed = it.toFloat() } },
        ),

        // --- LISTENING (wake word + echo cancellation) ---
        binding(
            key = "voiceStopEnabled",
            type = ManagedSettingType.BOOLEAN,
            get = { p -> ManagedValue.Bool(p.voiceStopEnabled) },
            set = { p, v -> (v as? ManagedValue.Bool)?.value?.let { p.voiceStopEnabled = it } },
        ),
        binding(
            key = "followUpEnabled",
            type = ManagedSettingType.BOOLEAN,
            get = { p -> ManagedValue.Bool(p.followUpEnabled) },
            set = { p, v -> (v as? ManagedValue.Bool)?.value?.let { p.followUpEnabled = it } },
        ),
        binding(
            key = "followUpWindowMs",
            type = ManagedSettingType.LONG,
            get = { p -> ManagedValue.LongValue(p.followUpWindowMs) },
            set = { p, v -> (v as? ManagedValue.LongValue)?.value?.let { p.followUpWindowMs = it } },
        ),
        binding(
            key = "wakeWordEngine",
            type = ManagedSettingType.ENUM,
            options = WAKE_WORD_ENGINE_OPTIONS,
            get = { p -> ManagedValue.EnumValue(p.wakeWordEngine) },
            set = { p, v -> v.stringOrNull()?.let { p.wakeWordEngine = it } },
        ),
        binding(
            key = "wakeWordModel",
            type = ManagedSettingType.ENUM,
            options = WAKE_WORD_MODEL_OPTIONS,
            get = { p -> ManagedValue.EnumValue(p.wakeWordModel) },
            set = { p, v -> v.stringOrNull()?.let { p.wakeWordModel = it } },
        ),
        binding(
            key = "customWakeWordPath",
            type = ManagedSettingType.STRING,
            get = { p -> ManagedValue.StringValue(p.customWakeWordPath) },
            set = { p, v -> v.stringOrNull()?.let { p.customWakeWordPath = it } },
        ),
        binding(
            key = "sherpaCustomKeyword",
            type = ManagedSettingType.STRING,
            get = { p -> ManagedValue.StringValue(p.sherpaCustomKeyword) },
            set = { p, v -> v.stringOrNull()?.let { p.sherpaCustomKeyword = it } },
        ),
        binding(
            key = "wakeSensitivity",
            type = ManagedSettingType.FLOAT,
            get = { p -> ManagedValue.FloatValue(p.wakeSensitivity.toDouble()) },
            set = { p, v -> (v as? ManagedValue.FloatValue)?.value?.let { p.wakeSensitivity = it.toFloat() } },
        ),
        binding(
            key = "aecMode",
            type = ManagedSettingType.ENUM,
            options = AecMode.entries.map { AecMode.toPref(it) },
            get = { p -> ManagedValue.EnumValue(p.aecMode) },
            set = { p, v -> v.stringOrNull()?.let { p.aecMode = it } },
        ),

        // --- WEATHER_MAPS ---
        binding(
            key = "weatherProvider",
            type = ManagedSettingType.ENUM,
            options = WeatherProvider.entries.map { it.id },
            get = { p -> ManagedValue.EnumValue(p.weatherProvider) },
            set = { p, v -> v.stringOrNull()?.let { p.weatherProvider = it } },
        ),
        binding(
            key = "weatherLocation",
            type = ManagedSettingType.STRING,
            get = { p -> ManagedValue.StringValue(p.weatherLocation) },
            set = { p, v -> v.stringOrNull()?.let { p.weatherLocation = it } },
        ),
        secretBinding("mapKitApiKey", SecretVault.KEY_MAPKIT_API_KEY),
        secretBinding("openMeteoProxy", SecretVault.KEY_OPEN_METEO_PROXY),

        // --- MEMORY ---
        binding(
            key = "memoryEnabled",
            type = ManagedSettingType.BOOLEAN,
            get = { p -> ManagedValue.Bool(p.memoryEnabled) },
            set = { p, v -> (v as? ManagedValue.Bool)?.value?.let { p.memoryEnabled = it } },
        ),
        binding(
            key = "memoryAutoExtract",
            type = ManagedSettingType.BOOLEAN,
            get = { p -> ManagedValue.Bool(p.memoryAutoExtract) },
            set = { p, v -> (v as? ManagedValue.Bool)?.value?.let { p.memoryAutoExtract = it } },
        ),
        binding(
            key = "memoryCloudEnabled",
            type = ManagedSettingType.BOOLEAN,
            get = { p -> ManagedValue.Bool(p.memoryCloudEnabled) },
            set = { p, v -> (v as? ManagedValue.Bool)?.value?.let { p.memoryCloudEnabled = it } },
        ),
        binding(
            key = "memorySensitiveVisible",
            type = ManagedSettingType.BOOLEAN,
            get = { p -> ManagedValue.Bool(p.memorySensitiveVisible) },
            set = { p, v -> (v as? ManagedValue.Bool)?.value?.let { p.memorySensitiveVisible = it } },
        ),
        binding(
            key = "memoryEmbedder",
            type = ManagedSettingType.ENUM,
            options = EmbedderChoice.entries.map { it.name },
            get = { p -> ManagedValue.EnumValue(p.memoryEmbedder) },
            set = { p, v -> v.stringOrNull()?.let { p.memoryEmbedder = it } },
        ),

        // --- PROACTIVITY ---
        binding(
            key = "behaviorEnabled",
            type = ManagedSettingType.BOOLEAN,
            get = { p -> ManagedValue.Bool(p.behaviorEnabled) },
            set = { p, v -> (v as? ManagedValue.Bool)?.value?.let { p.behaviorEnabled = it } },
        ),
        binding(
            key = "behaviorQuietStart",
            type = ManagedSettingType.INT,
            get = { p -> ManagedValue.IntValue(p.behaviorQuietStart) },
            set = { p, v -> (v as? ManagedValue.IntValue)?.value?.let { p.behaviorQuietStart = it } },
        ),
        binding(
            key = "behaviorQuietEnd",
            type = ManagedSettingType.INT,
            get = { p -> ManagedValue.IntValue(p.behaviorQuietEnd) },
            set = { p, v -> (v as? ManagedValue.IntValue)?.value?.let { p.behaviorQuietEnd = it } },
        ),
        binding(
            key = "behaviorDailyQuota",
            type = ManagedSettingType.INT,
            get = { p -> ManagedValue.IntValue(p.behaviorDailyQuota) },
            set = { p, v -> (v as? ManagedValue.IntValue)?.value?.let { p.behaviorDailyQuota = it } },
        ),

        // --- MUSIC ---
        binding(
            key = "preferredMusicPlayer",
            type = ManagedSettingType.STRING,
            get = { p -> ManagedValue.StringValue(p.preferredMusicPlayer) },
            set = { p, v -> v.stringOrNull()?.let { p.preferredMusicPlayer = it } },
        ),

        // --- ACCOUNTS (write-only secrets) ---
        secretBinding("openAiApiKey", SecretVault.KEY_OPENAI_API_KEY),
        secretBinding("picovoiceKey", SecretVault.KEY_PICOVOICE),
        secretBinding("saluteClientId", SecretVault.KEY_SALUTE_ID),
        secretBinding("saluteClientSecret", SecretVault.KEY_SALUTE_SECRET),
        secretBinding("yandexApiKey", SecretVault.KEY_YANDEX_API_KEY),
        secretBinding("gigaChatClientId", SecretVault.KEY_GIGA_ID),
        secretBinding("gigaChatClientSecret", SecretVault.KEY_GIGA_SECRET),

        // --- MCP (one composite JSON blob; per-server secrets are argument-keyed) ---
        binding(
            key = "mcpServers",
            type = ManagedSettingType.JSON_BLOB,
            get = { p -> ManagedValue.JsonValue(parseMcpServersElement(p.mcpServers)) },
            set = { p, v ->
                val element = (v as? ManagedValue.JsonValue)?.value ?: return@binding
                decodeMcpServers(element)?.let { p.mcpServers = McpServerConfigCodec.encode(it) }
            },
        ),

        // --- MANAGEMENT (R13 external management surface) ---
        binding(
            key = "managementMode",
            type = ManagedSettingType.ENUM,
            options = ManagementMode.entries.map { it.id },
            get = { p -> ManagedValue.EnumValue(ManagementMode.fromId(p.managementMode).id) },
            set = { p, v -> v.stringOrNull()?.let { p.managementMode = ManagementMode.fromId(it).id } },
        ),
        binding(
            key = "managementPort",
            type = ManagedSettingType.INT,
            get = { p -> ManagedValue.IntValue(p.managementPort) },
            set = { p, v -> (v as? ManagedValue.IntValue)?.value?.let { p.managementPort = it } },
        ),
        binding(
            key = "managementIdleTimeoutMs",
            type = ManagedSettingType.LONG,
            get = { p -> ManagedValue.LongValue(p.managementIdleTimeoutMs) },
            set = { p, v -> (v as? ManagedValue.LongValue)?.value?.let { p.managementIdleTimeoutMs = it } },
        ),

        // --- HOME (R4 unified smart-home surface) ---
        // The four blobs are edited through their own codecs (and sent to the
        // running backend), so the management surface treats them as opaque
        // JSON-safe strings; per-provider tokens are argument-keyed vault
        // secrets and are intentionally absent here.
        binding(
            key = "homeProviders",
            type = ManagedSettingType.JSON_BLOB,
            get = { p -> ManagedValue.JsonValue(parseStringElement(p.homeProviders)) },
            set = { p, v -> (v as? ManagedValue.JsonValue)?.value?.let { p.homeProviders = it.toString() } },
        ),
        binding(
            key = "homeEntities",
            type = ManagedSettingType.JSON_BLOB,
            get = { p -> ManagedValue.JsonValue(parseStringElement(p.homeEntities)) },
            set = { p, v -> (v as? ManagedValue.JsonValue)?.value?.let { p.homeEntities = it.toString() } },
        ),
        binding(
            key = "homeAliases",
            type = ManagedSettingType.JSON_BLOB,
            get = { p -> ManagedValue.JsonValue(parseStringElement(p.homeAliases)) },
            set = { p, v -> (v as? ManagedValue.JsonValue)?.value?.let { p.homeAliases = it.toString() } },
        ),
        binding(
            key = "homeGrants",
            type = ManagedSettingType.JSON_BLOB,
            get = { p -> ManagedValue.JsonValue(parseStringElement(p.homeGrants)) },
            set = { p, v -> (v as? ManagedValue.JsonValue)?.value?.let { p.homeGrants = it.toString() } },
        ),
        binding(
            key = "homeAwarenessEnabled",
            type = ManagedSettingType.BOOLEAN,
            get = { p -> ManagedValue.Bool(p.homeAwarenessEnabled) },
            set = { p, v -> (v as? ManagedValue.Bool)?.value?.let { p.homeAwarenessEnabled = it } },
        ),
    )

    /** Key → binding; the primary lookup for get/set/import validation. */
    val byKey: Map<String, Binding> = all.associateBy { it.key }

    /** Metadata-only projection for the UI/REST listing (no values). */
    val settings: List<ManagedSetting> = all.map { it.toManagedSetting() }

    /**
     * Builds one binding and fills its metadata from the frozen
     * `SettingsInventory` entry. A key with no inventory entry is a programming
     * error and fails fast at construction, so a typo cannot silently ship an
     * unreachable binding.
     */
    private fun binding(
        key: String,
        type: ManagedSettingType,
        secret: Boolean = false,
        secretVaultKey: String? = null,
        options: List<String> = emptyList(),
        get: (AppPrefs) -> ManagedValue,
        set: (AppPrefs, ManagedValue) -> Unit,
    ): Binding {
        val entry = SettingsInventory.entries.firstOrNull { it.key == key }
            ?: error("ManagementBindings: no SettingsInventory entry for '$key'")
        return Binding(
            key = key,
            category = entry.category.id,
            type = type,
            policy = entry.policy.toManagedPolicy(),
            essential = entry.essential,
            secret = secret,
            secretVaultKey = secretVaultKey,
            options = options,
            get = get,
            set = set,
        )
    }

    /**
     * A write-only vault binding. Its [Binding.get] reports presence only; its
     * [Binding.set] stores the real secret. Used by every ACCOUNTS key plus the
     * MapKit key and the Open-Meteo proxy.
     */
    private fun secretBinding(key: String, vaultKey: String): Binding = binding(
        key = key,
        type = ManagedSettingType.STRING,
        secret = true,
        secretVaultKey = vaultKey,
        get = { ManagedValue.Bool(vault.hasNonBlank(vaultKey)) },
        set = { _, v -> vault.putString(vaultKey, v.stringOrNull().orEmpty().trim()) },
    )
}

/** `ApplyPolicy` → the local mirror; one-to-one, exhaustive with no `else`. */
internal fun ApplyPolicy.toManagedPolicy(): ManagedApplyPolicy = when (this) {
    ApplyPolicy.LIVE -> ManagedApplyPolicy.LIVE
    ApplyPolicy.SERVICE_RESTART -> ManagedApplyPolicy.SERVICE_RESTART
    ApplyPolicy.APP_RESTART -> ManagedApplyPolicy.APP_RESTART
}

/**
 * The persisted provider wire spelling (`gigachat` | `openai` | `yandex`),
 * mirroring `AppPrefs.providerType`'s storage. Kept local so the binding can
 * expose an enum value without reaching into `AppPrefs` internals.
 */
private fun providerWire(type: ProviderSettings.Type): String = when (type) {
    ProviderSettings.Type.GIGACHAT -> "gigachat"
    ProviderSettings.Type.OPENAI_COMPAT -> "openai"
    ProviderSettings.Type.YANDEX -> "yandex"
}

/** Tolerant inverse of [providerWire]; an unknown value degrades to GigaChat. */
private fun providerFromWire(raw: String): ProviderSettings.Type = when (raw.trim().lowercase()) {
    "openai" -> ProviderSettings.Type.OPENAI_COMPAT
    "yandex" -> ProviderSettings.Type.YANDEX
    else -> ProviderSettings.Type.GIGACHAT
}

/**
 * Wake-word engine vocabulary (`AppPrefs.wakeWordEngine`: `"sherpa"` |
 * `"porcupine"`). NOT a plain enum — the pref is a raw string written by the
 * Settings radio, and the settings lane's `ListeningSettingsController`
 * constants are private. This list is therefore the management surface's
 * single source of allowed values and must track the AppPrefs KDoc.
 */
private val WAKE_WORD_ENGINE_OPTIONS: List<String> = listOf("sherpa", "porcupine")

/**
 * Wake-word model vocabulary (`AppPrefs.wakeWordModel`): `"builtin"` |
 * `"custom_bundled"` | `"custom_user"`. NOT a plain enum (the radio has two
 * options but an imported `.ppn` persists `custom_user`); see
 * [WAKE_WORD_ENGINE_OPTIONS].
 */
private val WAKE_WORD_MODEL_OPTIONS: List<String> = listOf("builtin", "custom_bundled", "custom_user")

/** String- or enum-valued extraction shared by every string-shaped setter. */
private fun ManagedValue.stringOrNull(): String? = when (this) {
    is ManagedValue.StringValue -> value
    is ManagedValue.EnumValue -> value
    else -> null
}

/**
 * The persisted MCP list as a JSON element. A blank/absent pref yields an empty
 * array; a malformed payload is normalized to an empty array via the codec
 * (never a throw), so a GET/decode can always proceed.
 */
internal fun parseMcpServersElement(raw: String?): JsonElement {
    val servers = when (val decoded = McpServerConfigCodec.decode(raw)) {
        is McpServerDecodeResult.Ok -> decoded.servers
        McpServerDecodeResult.Empty -> emptyList()
        is McpServerDecodeResult.Invalid -> emptyList()
    }
    return Json.parseToJsonElement(McpServerConfigCodec.encode(servers))
}

/**
 * Parse a JSON-blob pref string into a [JsonElement], tolerating a corrupt /
 * blank value: an unparseable payload degrades to an empty JSON array so a GET
 * never throws. Used for the R4 home blobs, which are edited by their own
 * screens/codecs elsewhere.
 */
internal fun parseStringElement(raw: String?): JsonElement {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return Json.parseToJsonElement("[]")
    return try {
        Json.parseToJsonElement(text)
    } catch (e: IllegalArgumentException) {
        Timber.w("Home: stored JSON blob could not be parsed (%s)", e::class.java.simpleName)
        Json.parseToJsonElement("[]")
    }
}

/**
 * Decode an MCP JSON element into servers, or null when it is not a valid array
 * of [McpServerConfig]. Used to reject a malformed import/write before the pref
 * is touched.
 */
internal fun decodeMcpServers(element: JsonElement?): List<McpServerConfig>? {
    if (element == null) return null
    return when (val decoded = McpServerConfigCodec.decode(element.toString())) {
        is McpServerDecodeResult.Ok -> decoded.servers
        McpServerDecodeResult.Empty -> emptyList()
        is McpServerDecodeResult.Invalid -> null
    }
}
