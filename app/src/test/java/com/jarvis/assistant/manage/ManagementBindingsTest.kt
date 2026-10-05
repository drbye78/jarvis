package com.jarvis.assistant.manage

import com.jarvis.assistant.FakeSharedPreferences
import com.jarvis.assistant.config.ProviderSettings
import com.jarvis.assistant.mcp.McpServerConfig
import com.jarvis.assistant.mcp.McpServerConfigCodec
import com.jarvis.assistant.settings.ApplyPolicies
import com.jarvis.assistant.settings.SettingsInventory
import com.jarvis.assistant.speech.SpeechBackend
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.InMemoryVault
import com.jarvis.assistant.util.SecretVault
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bindings are the reflection-free bridge §14.4 demands, so the coverage
 * assertion is the whole point: it compares the binding key set against
 * `SettingsInventory.entries` in BOTH directions.
 *
 * Why that fails on an unbound setting: adding a key to `SettingsInventory`
 * without a `ManagementBindings` entry leaves that key out of `byKey`, so
 * `assertEquals(inventory, byKey.keys)` fails naming the missing key. A binding
 * whose key is NOT in the inventory fails even earlier: [binding] throws
 * `error(...)` at construction. Metadata itself is read from the inventory, so
 * a policy/category can never drift.
 */
class ManagementBindingsTest {

    private fun newPrefs(vault: SecretVault = InMemoryVault()): AppPrefs =
        AppPrefs(context = null, vaultOverride = vault, prefsOverride = FakeSharedPreferences())

    private fun bindings(vault: SecretVault = InMemoryVault()): ManagementBindings = ManagementBindings(vault)

    @Test
    fun `every inventory setting is bound and no binding is unregistered`() {
        val inventory = SettingsInventory.entries.map { it.key }.toSet()
        val bound = bindings().byKey.keys

        assertEquals("inventory ⊄ bindings (missing/unbound key)", inventory, bound)
        assertEquals("bindings ⊄ inventory (stray binding)", bound, inventory)
        assertEquals(SettingsInventory.entries.size, bound.size)
        assertEquals(50, bound.size)
    }

    @Test
    fun `binding metadata and policy mirror the inventory entry`() {
        val byKey = bindings().byKey
        SettingsInventory.entries.forEach { entry ->
            val binding = byKey.getValue(entry.key)
            assertEquals(entry.category.id, binding.category)
            assertEquals(entry.essential, binding.essential)
            assertEquals(ApplyPolicies.of(entry.key).toManagedPolicy(), binding.policy)
        }
    }

    @Test
    fun `every enum binding advertises non-empty options that round trip`() {
        val prefs = newPrefs()
        val enums = bindings().all.filter { it.type == ManagedSettingType.ENUM }
        assertEquals(8, enums.size)
        enums.forEach { binding ->
            assertTrue("enum ${binding.key} exposes no options", binding.options.isNotEmpty())
            binding.options.forEach { option ->
                binding.set(prefs, ManagedValue.EnumValue(option))
                assertEquals(
                    "enum ${binding.key} does not round-trip '$option'",
                    ManagedValue.EnumValue(option),
                    binding.get(prefs),
                )
            }
        }
    }

    @Test
    fun `enum options are derived from the persisted vocabulary`() {
        val byKey = bindings().byKey
        assertEquals(listOf("gigachat", "openai", "yandex"), byKey.getValue("providerType").options)
        assertEquals(listOf("sber", "yandex"), byKey.getValue("speechBackend").options)
        assertEquals(listOf("sherpa", "porcupine"), byKey.getValue("wakeWordEngine").options)
        assertEquals(
            listOf("builtin", "custom_bundled", "custom_user"),
            byKey.getValue("wakeWordModel").options,
        )
        assertEquals(listOf("off", "hardware", "software"), byKey.getValue("aecMode").options)
        assertEquals(listOf("open_meteo", "project_eol"), byKey.getValue("weatherProvider").options)
        assertEquals(listOf("AUTO", "CLOUD", "LOCAL", "OFF"), byKey.getValue("memoryEmbedder").options)
        assertEquals(listOf("disabled", "localhost", "lan"), byKey.getValue("managementMode").options)
    }

    @Test
    fun `the nine secret bindings are exactly the vault-backed settings`() {
        val expected = setOf(
            "openAiApiKey",
            "picovoiceKey",
            "saluteClientId",
            "saluteClientSecret",
            "yandexApiKey",
            "gigaChatClientId",
            "gigaChatClientSecret",
            "mapKitApiKey",
            "openMeteoProxy",
        )
        val actual = bindings().all.filter { it.secret }.map { it.key }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `a secret get reports presence only never the plaintext`() {
        val vault = InMemoryVault()
        val binding = bindings(vault).byKey.getValue("yandexApiKey")
        val prefs = newPrefs(vault)

        assertEquals(ManagedValue.Bool(false), binding.get(prefs))
        binding.set(prefs, ManagedValue.StringValue("super-secret"))
        assertEquals("super-secret", vault.getString(SecretVault.KEY_YANDEX_API_KEY))
        assertEquals(ManagedValue.Bool(true), binding.get(prefs))
    }

    @Test
    fun `non-secret defaults all carry their declared type`() {
        val prefs = newPrefs()
        bindings().all.filterNot { it.secret }.forEach { binding ->
            assertEquals(
                "declared type mismatch for ${binding.key}",
                binding.type,
                typeOf(binding.get(prefs)),
            )
        }
    }

    @Test
    fun `representative plain settings round-trip through the binding`() {
        val prefs = newPrefs()
        val byKey = bindings().byKey

        byKey.getValue("memoryEnabled").set(prefs, ManagedValue.Bool(false))
        assertEquals(ManagedValue.Bool(false), byKey.getValue("memoryEnabled").get(prefs))

        byKey.getValue("yandexTtsSpeed").set(prefs, ManagedValue.FloatValue(1.75))
        assertEquals(ManagedValue.FloatValue(1.75), byKey.getValue("yandexTtsSpeed").get(prefs))

        byKey.getValue("followUpWindowMs").set(prefs, ManagedValue.LongValue(4_000L))
        assertEquals(ManagedValue.LongValue(4_000L), byKey.getValue("followUpWindowMs").get(prefs))

        byKey.getValue("behaviorQuietStart").set(prefs, ManagedValue.IntValue(22))
        assertEquals(ManagedValue.IntValue(22), byKey.getValue("behaviorQuietStart").get(prefs))

        byKey.getValue("providerType").set(prefs, ManagedValue.EnumValue("yandex"))
        assertEquals(ProviderSettings.Type.YANDEX, prefs.providerType)
        assertEquals(ManagedValue.EnumValue("yandex"), byKey.getValue("providerType").get(prefs))

        byKey.getValue("speechBackend").set(prefs, ManagedValue.EnumValue("yandex"))
        assertEquals(SpeechBackend.YANDEX, prefs.speechBackend)
        assertEquals(ManagedValue.EnumValue("yandex"), byKey.getValue("speechBackend").get(prefs))

        byKey.getValue("managementMode").set(prefs, ManagedValue.EnumValue("lan"))
        assertEquals("lan", prefs.managementMode)
        assertEquals(ManagedValue.EnumValue("lan"), byKey.getValue("managementMode").get(prefs))

        byKey.getValue("managementMode").set(prefs, ManagedValue.EnumValue("nonsense"))
        assertEquals("disabled", prefs.managementMode)
    }

    @Test
    fun `mcpServers binds a composite blob through the codec`() {
        val prefs = newPrefs()
        val binding = bindings().byKey.getValue("mcpServers")
        val servers = listOf(
            McpServerConfig(id = "srv-1", displayName = "Test", url = "https://example.test/mcp"),
        )
        val element = Json.parseToJsonElement(McpServerConfigCodec.encode(servers))

        binding.set(prefs, ManagedValue.JsonValue(element))

        assertEquals(McpServerConfigCodec.encode(servers), prefs.mcpServers)
        val read = (binding.get(prefs) as ManagedValue.JsonValue).value
        assertEquals(servers, decodeMcpServers(read))
    }

    private fun typeOf(value: ManagedValue): ManagedSettingType = when (value) {
        is ManagedValue.Bool -> ManagedSettingType.BOOLEAN
        is ManagedValue.IntValue -> ManagedSettingType.INT
        is ManagedValue.LongValue -> ManagedSettingType.LONG
        is ManagedValue.FloatValue -> ManagedSettingType.FLOAT
        is ManagedValue.StringValue -> ManagedSettingType.STRING
        is ManagedValue.EnumValue -> ManagedSettingType.ENUM
        is ManagedValue.JsonValue -> ManagedSettingType.JSON_BLOB
    }
}
