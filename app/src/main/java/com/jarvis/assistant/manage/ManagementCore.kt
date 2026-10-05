package com.jarvis.assistant.manage

import com.jarvis.assistant.mcp.McpServerConfig
import com.jarvis.assistant.mcp.McpServerConfigCodec
import com.jarvis.assistant.settings.PendingChanges
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.SecretVault
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The `GET /status` payload (R13 §14.4). */
data class ManagementStatus(
    val mode: ManagementMode,
    val port: Int,
    val idleTimeoutMs: Long,
    val appVersion: String,
    val pendingPolicies: Set<ManagedApplyPolicy>,
    val formatVersion: Int,
)

/** Metadata for one write-only secret: never the value. */
data class SecretStatus(val key: String, val set: Boolean)

/**
 * The outcome of an import. [errors] being non-empty means NOTHING was
 * applied — validation is a separate pass from application (§14.6).
 */
data class ImportResult(
    val applied: Int,
    val skipped: Int,
    val errors: List<String>,
) {
    val ok: Boolean get() = errors.isEmpty()
}

/**
 * The pure-ish management facade (R13 §14.4): the REST API and the web UI are
 * clients of this class, never of `AppPrefs` directly.
 *
 * It is JVM-testable via `AppPrefs(context = null, prefsOverride = …,
 * vaultOverride = …)`; it holds no Android types. The `settings` import is
 * deliberate — this class IS the bridge between the durable `AppPrefs`/vault
 * layer and the transport-neutral management model.
 *
 * Security invariants:
 *  - secrets are write-only: [getSetting] and [listSettings] never return a
 *    value for a secret binding, and [listSecrets] carries presence only;
 *  - [export] includes secrets ONLY when `includeSecrets = true`;
 *  - [import] validates every key and type BEFORE writing, so a rejected
 *    document is never partially applied.
 *
 * @param pendingPolicies read lazily (default: the live `PendingChanges` set)
 *   so `/status` reports “stored but not yet active” without coupling this
 *   class to the banner's lifecycle.
 */
class ManagementCore(
    private val prefs: AppPrefs,
    private val bindings: ManagementBindings,
    private val vault: SecretVault,
    private val appVersion: String = "",
    private val pendingPolicies: () -> Set<ManagedApplyPolicy> = {
        PendingChanges.changes.value.map { it.toManagedPolicy() }.toSet()
    },
) {

    /** Runtime state for `/status`; never includes secret material. */
    fun status(): ManagementStatus = ManagementStatus(
        mode = ManagementMode.fromId(prefs.managementMode),
        port = prefs.managementPort,
        idleTimeoutMs = prefs.managementIdleTimeoutMs,
        appVersion = appVersion,
        pendingPolicies = pendingPolicies(),
        formatVersion = ExportCodec.FORMAT_VERSION,
    )

    /** Metadata for every managed key (no values), in registry order. */
    fun listSettings(): List<ManagedSetting> = bindings.settings

    /** Metadata for one key, or null when unbound. */
    fun setting(key: String): ManagedSetting? = bindings.byKey[key]?.toManagedSetting()

    /**
     * The current value of a non-secret setting, or null for an unknown key OR
     * a secret key (secrets are write-only — never readable here).
     */
    fun getSetting(key: String): ManagedValue? {
        val binding = bindings.byKey[key] ?: return null
        if (binding.secret) return null
        return binding.get(prefs)
    }

    /**
     * Validate and write a non-secret setting, returning its apply policy so
     * the caller can mark it pending. Returns null (nothing written) for an
     * unknown key, a secret key, or a value that does not match the binding's
     * declared type.
     */
    fun setSetting(key: String, value: ManagedValue): ManagedApplyPolicy? {
        val binding = bindings.byKey[key] ?: return null
        if (binding.secret) return null
        if (!isValid(binding, value)) return null
        binding.set(prefs, value)
        return binding.policy
    }

    /** Presence metadata for every secret binding; values are never returned. */
    fun listSecrets(): List<SecretStatus> = bindings.all
        .filter { it.secret }
        .map { SecretStatus(key = it.key, set = it.secretVaultKey?.let(vault::hasNonBlank) == true) }

    /**
     * Write-only secret set. Returns false for an unknown/non-secret key (and
     * writes nothing), so the caller cannot accidentally store a secret through
     * the plain-setting path.
     */
    fun setSecret(key: String, value: String): Boolean {
        val binding = bindings.byKey[key] ?: return false
        if (!binding.secret) return false
        val vaultKey = binding.secretVaultKey ?: return false
        vault.putString(vaultKey, value.trim())
        return true
    }

    /** Remove a stored secret; false for an unknown/non-secret key. */
    fun clearSecret(key: String): Boolean {
        val binding = bindings.byKey[key] ?: return false
        if (!binding.secret) return false
        val vaultKey = binding.secretVaultKey ?: return false
        vault.remove(vaultKey)
        return true
    }

    // ------------------------------------------------------------------
    // MCP servers: a composite blob edited through typed operations rather
    // than raw-blob writes. Per-server auth secrets stay in the vault and are
    // keyed by server id (write-only).
    // ------------------------------------------------------------------

    /** The configured servers, decoded from the pref blob. */
    fun listMcpServers(): List<McpServerConfig> =
        decodeMcpServers(parseMcpServersElement(prefs.mcpServers)).orEmpty()

    /** Append (or replace by id) [server]; returns the updated list. */
    fun addMcpServer(server: McpServerConfig): List<McpServerConfig> {
        val current = listMcpServers()
        val updated = current.filterNot { it.id == server.id } + server
        writeMcpServers(updated)
        return updated
    }

    /** Replace the server with the same id; false (no write) when absent. */
    fun updateMcpServer(server: McpServerConfig): Boolean {
        val current = listMcpServers()
        val index = current.indexOfFirst { it.id == server.id }
        if (index < 0) return false
        writeMcpServers(current.toMutableList().also { it[index] = server })
        return true
    }

    /** Delete by id (and its stored secret); false when the id was absent. */
    fun deleteMcpServer(serverId: String): Boolean {
        val current = listMcpServers()
        val updated = current.filterNot { it.id == serverId }
        if (updated.size == current.size) return false
        writeMcpServers(updated)
        vault.remove(SecretVault.mcpSecretKey(serverId))
        return true
    }

    /** Store (write-only) the auth secret for one server. */
    fun setMcpSecret(serverId: String, value: String) {
        vault.putString(SecretVault.mcpSecretKey(serverId), value.trim())
    }

    /** Clear the stored secret for one server. */
    fun clearMcpSecret(serverId: String) {
        vault.remove(SecretVault.mcpSecretKey(serverId))
    }

    /** Presence check for one server's secret; the value is never returned. */
    fun mcpSecretSet(serverId: String): Boolean =
        vault.hasNonBlank(SecretVault.mcpSecretKey(serverId))

    // ------------------------------------------------------------------
    // Export / import (§14.6). Building the document is the bindings' job;
    // the codec owns encryption, and secrets only join when opted in.
    // ------------------------------------------------------------------

    /**
     * Encrypt the current configuration into a single opaque envelope. Secrets
     * are excluded unless [includeSecrets] is true; even then they leave the
     * device only inside the always-encrypted container.
     */
    fun export(passphrase: CharArray, includeSecrets: Boolean = false): String {
        val settings = LinkedHashMap<String, JsonElement>()
        bindings.all.forEach { binding ->
            if (!binding.secret) settings[binding.key] = binding.get(prefs).toJsonElement()
        }
        val secrets = LinkedHashMap<String, JsonElement>()
        if (includeSecrets) {
            bindings.all.forEach { binding ->
                val vaultKey = binding.secretVaultKey ?: return@forEach
                val value = vault.getString(vaultKey)
                if (!value.isNullOrBlank()) secrets[binding.key] = JsonPrimitive(value.trim())
            }
        }
        val document = ConfigDocument(
            format = ExportCodec.FORMAT,
            formatVersion = ExportCodec.FORMAT_VERSION,
            appVersion = appVersion,
            exportedAt = System.currentTimeMillis(),
            settings = settings,
            secrets = secrets,
            mcpServers = parseMcpServersElement(prefs.mcpServers),
            home = JsonObject(emptyMap()),
        )
        return ExportCodec.encode(document, passphrase, includeSecrets)
    }

    /**
     * Decrypt and apply a document. Validation is a full pass first; on ANY
     * error nothing is written and [ImportResult.errors] explains why.
     * Recognized-but-unchanged values are counted as skipped.
     */
    fun import(text: String, passphrase: CharArray): ImportResult {
        val document = try {
            ExportCodec.decode(text, passphrase)
        } catch (failure: ExportException) {
            return ImportResult(applied = 0, skipped = 0, errors = listOf(failure.message ?: "import failed"))
        }
        val errors = mutableListOf<String>()
        val settingWrites = mutableListOf<Pair<Binding, ManagedValue>>()
        val secretWrites = mutableListOf<Pair<Binding, String>>()
        collectSettingWrites(document, errors, settingWrites)
        collectMcpServersWrite(document, errors, settingWrites)
        collectSecretWrites(document, errors, secretWrites)
        if (errors.isNotEmpty()) return ImportResult(applied = 0, skipped = 0, errors = errors)

        var applied = 0
        var skipped = 0
        settingWrites.forEach { (binding, value) ->
            if (binding.get(prefs) == value) {
                skipped++
            } else {
                binding.set(prefs, value)
                applied++
            }
        }
        secretWrites.forEach { (binding, value) ->
            val vaultKey = binding.secretVaultKey ?: return@forEach
            if (vault.getString(vaultKey)?.trim() == value) {
                skipped++
            } else {
                vault.putString(vaultKey, value)
                applied++
            }
        }
        return ImportResult(applied = applied, skipped = skipped, errors = emptyList())
    }

    /** Validates the `settings` section into pending writes, collecting errors. */
    private fun collectSettingWrites(
        document: ConfigDocument,
        errors: MutableList<String>,
        writes: MutableList<Pair<Binding, ManagedValue>>,
    ) {
        document.settings.forEach { (key, element) ->
            val binding = bindings.byKey[key]
            if (binding == null) {
                errors += "unknown setting '$key'"
            } else if (binding.secret) {
                errors += "secret '$key' must be supplied via the secrets section"
            } else {
                val value = ManagedValue.fromJsonElement(binding.type, element)
                if (value == null || !isValid(binding, value)) {
                    errors += "type mismatch for setting '$key'"
                } else {
                    writes += binding to value
                }
            }
        }
    }

    /**
     * The reserved top-level `mcpServers` section is accepted for the §14.6
     * document shape. It is applied only when the `settings` map did NOT carry
     * the `mcpServers` binding (our own exports carry it there) and the section
     * is non-empty, so a settings-only document can never silently wipe the
     * configured server list.
     */
    private fun collectMcpServersWrite(
        document: ConfigDocument,
        errors: MutableList<String>,
        writes: MutableList<Pair<Binding, ManagedValue>>,
    ) {
        if (document.settings.containsKey(MCP_SERVERS_KEY)) return
        val binding = bindings.byKey[MCP_SERVERS_KEY] ?: return
        val servers = decodeMcpServers(document.mcpServers)
        if (servers == null) {
            errors += "malformed mcpServers section"
        } else if (servers.isNotEmpty()) {
            writes += binding to ManagedValue.JsonValue(document.mcpServers)
        }
    }

    /** Validates the `secrets` section into pending vault writes. */
    private fun collectSecretWrites(
        document: ConfigDocument,
        errors: MutableList<String>,
        writes: MutableList<Pair<Binding, String>>,
    ) {
        document.secrets.forEach { (key, element) ->
            val binding = bindings.byKey[key]
            if (binding == null || !binding.secret) {
                errors += "unknown secret '$key'"
            } else {
                val value = ManagedValue.fromJsonElement(binding.type, element)
                if (value !is ManagedValue.StringValue) {
                    errors += "invalid secret value for '$key'"
                } else {
                    writes += binding to value.value.trim()
                }
            }
        }
    }

    /** Type check plus the composite-blob structure check for `mcpServers`. */
    private fun isValid(binding: Binding, value: ManagedValue): Boolean {
        if (!matchesType(binding.type, value)) return false
        // An ENUM write must be inside the binding's advertised vocabulary in
        // ADDITION to being string-shaped (fail-closed: an empty options list
        // rejects every value). This mirrors the type-mismatch path, so a
        // hand-built request can never persist an out-of-vocabulary enum.
        if (binding.type == ManagedSettingType.ENUM &&
            (value as? ManagedValue.EnumValue)?.value !in binding.options
        ) {
            return false
        }
        if (binding.key != MCP_SERVERS_KEY) return true
        val element = (value as? ManagedValue.JsonValue)?.value ?: return false
        return decodeMcpServers(element) != null
    }

    /** Exhaustive with NO `else`: a new [ManagedSettingType] fails compilation. */
    private fun matchesType(type: ManagedSettingType, value: ManagedValue): Boolean = when (type) {
        ManagedSettingType.BOOLEAN -> value is ManagedValue.Bool
        ManagedSettingType.INT -> value is ManagedValue.IntValue
        ManagedSettingType.LONG -> value is ManagedValue.LongValue
        ManagedSettingType.FLOAT -> value is ManagedValue.FloatValue
        ManagedSettingType.STRING -> value is ManagedValue.StringValue
        ManagedSettingType.ENUM -> value is ManagedValue.EnumValue
        ManagedSettingType.JSON_BLOB -> value is ManagedValue.JsonValue
    }

    private fun writeMcpServers(servers: List<McpServerConfig>) {
        prefs.mcpServers = McpServerConfigCodec.encode(servers)
    }

    private companion object {
        const val MCP_SERVERS_KEY = "mcpServers"
    }
}
