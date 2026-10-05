package com.jarvis.assistant.manage

import com.jarvis.assistant.FakeSharedPreferences
import com.jarvis.assistant.config.ProviderSettings
import com.jarvis.assistant.mcp.McpServerConfig
import com.jarvis.assistant.mcp.McpServerConfigCodec
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.InMemoryVault
import com.jarvis.assistant.util.SecretVault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The management core over the 0.7 JVM seam (in-memory prefs + vault): status,
 * secret write-only discipline, MCP CRUD, and the always-encrypted
 * export/import with atomic validation.
 */
class ManagementCoreTest {

    private val passphrase = "correct horse battery".toCharArray()

    private fun newPrefs(vault: SecretVault = InMemoryVault()): AppPrefs =
        AppPrefs(context = null, vaultOverride = vault, prefsOverride = FakeSharedPreferences())

    private fun coreOf(prefs: AppPrefs, vault: SecretVault): ManagementCore = ManagementCore(
        prefs = prefs,
        bindings = ManagementBindings(vault),
        vault = vault,
        appVersion = "0.2.2",
        pendingPolicies = { emptySet() },
    )

    @Test
    fun `status reflects the stored mode port idle and version`() {
        val vault = InMemoryVault()
        val prefs = newPrefs(vault)
        prefs.managementMode = "lan"
        prefs.managementPort = 9000
        prefs.managementIdleTimeoutMs = 1_234L

        val status = coreOf(prefs, vault).status()

        assertEquals(ManagementMode.LAN, status.mode)
        assertEquals(9000, status.port)
        assertEquals(1_234L, status.idleTimeoutMs)
        assertEquals("0.2.2", status.appVersion)
        assertEquals(ExportCodec.FORMAT_VERSION, status.formatVersion)
        assertTrue(status.pendingPolicies.isEmpty())
    }

    @Test
    fun `status surfaces the injected pending policies`() {
        val vault = InMemoryVault()
        val prefs = newPrefs(vault)
        val core = ManagementCore(
            prefs = prefs,
            bindings = ManagementBindings(vault),
            vault = vault,
            appVersion = "0.2.2",
            pendingPolicies = { setOf(ManagedApplyPolicy.SERVICE_RESTART) },
        )

        assertEquals(setOf(ManagedApplyPolicy.SERVICE_RESTART), core.status().pendingPolicies)
    }

    @Test
    fun `listSettings advertises every key without values`() {
        val vault = InMemoryVault()
        val settings = coreOf(newPrefs(vault), vault).listSettings()

        assertEquals(50, settings.size)
        assertEquals(ManagedSettingType.BOOLEAN, settings.first { it.key == "memoryEnabled" }.type)
        assertTrue(settings.any { it.key == "yandexApiKey" && it.secret })
    }

    @Test
    fun `get and set refuse secret keys and unknown keys`() {
        val vault = InMemoryVault()
        val prefs = newPrefs(vault)
        val core = coreOf(prefs, vault)

        assertNull(core.getSetting("yandexApiKey"))
        assertNull(core.setSetting("yandexApiKey", ManagedValue.StringValue("leak")))
        assertFalse(vault.hasNonBlank(SecretVault.KEY_YANDEX_API_KEY))
        assertNull(core.getSetting("notARealKey"))
        assertNull(core.setSetting("notARealKey", ManagedValue.Bool(true)))
    }

    @Test
    fun `setSetting writes and returns the policy and rejects a type mismatch`() {
        val vault = InMemoryVault()
        val prefs = newPrefs(vault)
        val core = coreOf(prefs, vault)

        assertEquals(ManagedApplyPolicy.LIVE, core.setSetting("memoryEnabled", ManagedValue.Bool(false)))
        assertFalse(prefs.memoryEnabled)

        assertEquals(
            ManagedApplyPolicy.SERVICE_RESTART,
            core.setSetting("managementPort", ManagedValue.IntValue(9000)),
        )
        assertEquals(9000, prefs.managementPort)

        assertNull(core.setSetting("managementPort", ManagedValue.Bool(true)))
        assertEquals(9000, prefs.managementPort)
    }

    @Test
    fun `listSettings carries the enum vocabulary and omits it for plain settings`() {
        val vault = InMemoryVault()
        val core = coreOf(newPrefs(vault), vault)
        val byKey = core.listSettings().associateBy { it.key }

        assertEquals(listOf("gigachat", "openai", "yandex"), byKey.getValue("providerType").options)
        assertTrue(byKey.getValue("memoryEnabled").options.isEmpty())
        assertEquals(listOf("gigachat", "openai", "yandex"), core.setting("providerType")?.options)
        assertTrue(core.setting("memoryEnabled")?.options?.isEmpty() == true)
    }

    @Test
    fun `setSetting rejects an out-of-vocabulary enum and writes nothing`() {
        val vault = InMemoryVault()
        val prefs = newPrefs(vault)
        val core = coreOf(prefs, vault)

        assertNull(core.setSetting("weatherProvider", ManagedValue.EnumValue("bogus")))
        assertEquals("project_eol", prefs.weatherProvider)

        assertEquals(
            ManagedApplyPolicy.LIVE,
            core.setSetting("weatherProvider", ManagedValue.EnumValue("open_meteo")),
        )
        assertEquals("open_meteo", prefs.weatherProvider)
        assertEquals(ManagedValue.EnumValue("open_meteo"), core.getSetting("weatherProvider"))
    }

    @Test
    fun `secrets are listed by presence and set clear are write only`() {
        val vault = InMemoryVault()
        val core = coreOf(newPrefs(vault), vault)

        val initial = core.listSecrets()
        assertEquals(9, initial.size)
        assertFalse(initial.first { it.key == "picovoiceKey" }.set)

        assertTrue(core.setSecret("picovoiceKey", " pv "))
        assertEquals("pv", vault.getString(SecretVault.KEY_PICOVOICE))
        assertTrue(core.listSecrets().first { it.key == "picovoiceKey" }.set)

        assertFalse(core.setSecret("memoryEnabled", "not-a-secret"))
        assertFalse(core.clearSecret("memoryEnabled"))

        assertTrue(core.clearSecret("picovoiceKey"))
        assertFalse(core.listSecrets().first { it.key == "picovoiceKey" }.set)
    }

    @Test
    fun `mcp CRUD edits the blob and clears the per server secret`() {
        val vault = InMemoryVault()
        val core = coreOf(newPrefs(vault), vault)
        val server = McpServerConfig(id = "a", displayName = "A", url = "https://a.test/mcp")

        assertEquals(listOf(server), core.addMcpServer(server))
        assertEquals(listOf(server), core.listMcpServers())

        core.setMcpSecret("a", "tok")
        assertTrue(core.mcpSecretSet("a"))

        assertTrue(core.updateMcpServer(server.copy(displayName = "A2")))
        assertEquals("A2", core.listMcpServers().single().displayName)
        assertFalse(core.updateMcpServer(server.copy(id = "missing")))

        assertTrue(core.deleteMcpServer("a"))
        assertTrue(core.listMcpServers().isEmpty())
        assertFalse(core.mcpSecretSet("a"))
        assertFalse(core.deleteMcpServer("a"))
    }

    @Test
    fun `export excludes secrets by default and includes them only on opt in`() {
        val vault = InMemoryVault()
        val prompt = newPrefs(vault)
        val core = coreOf(prompt, vault)
        core.setSecret("yandexApiKey", "the-real-secret")
        assertEquals(ManagedApplyPolicy.LIVE, core.setSetting("memoryEnabled", ManagedValue.Bool(false)))

        val withoutSecrets = ExportCodec.decode(core.export(passphrase), passphrase)
        assertTrue(withoutSecrets.secrets.isEmpty())
        assertEquals(JsonPrimitive(false), withoutSecrets.settings["memoryEnabled"])

        val withSecrets = ExportCodec.decode(core.export(passphrase, includeSecrets = true), passphrase)
        assertEquals(JsonPrimitive("the-real-secret"), withSecrets.secrets["yandexApiKey"])
    }

    @Test
    fun `import applies a valid document`() {
        val sourceVault = InMemoryVault()
        val source = coreOf(newPrefs(sourceVault), sourceVault)
        source.setSetting("memoryEnabled", ManagedValue.Bool(false))
        source.setSetting("providerType", ManagedValue.EnumValue("yandex"))
        val text = source.export(passphrase)

        val targetVault = InMemoryVault()
        val targetPrefs = newPrefs(targetVault)
        val result = coreOf(targetPrefs, targetVault).import(text, passphrase)

        assertTrue(result.ok)
        assertEquals(2, result.applied)
        assertFalse(targetPrefs.memoryEnabled)
        assertEquals(ProviderSettings.Type.YANDEX, targetPrefs.providerType)
    }

    @Test
    fun `import writes nothing when any key is unknown or mistyped`() {
        val vault = InMemoryVault()
        val prefs = newPrefs(vault)
        val core = coreOf(prefs, vault)
        assertTrue(prefs.memoryEnabled)

        val unknown = ConfigDocument(
            settings = mapOf(
                "notARealKey" to JsonPrimitive(1),
                "memoryEnabled" to JsonPrimitive(false),
            ),
        )
        val unknownResult = core.import(ExportCodec.encode(unknown, passphrase), passphrase)
        assertFalse(unknownResult.ok)
        assertTrue(unknownResult.errors.any { it.contains("notARealKey") })
        assertTrue("a validation failure must not partially apply", prefs.memoryEnabled)

        val mistyped = ConfigDocument(settings = mapOf("memoryEnabled" to JsonPrimitive("nope")))
        val mistypedResult = core.import(ExportCodec.encode(mistyped, passphrase), passphrase)
        assertFalse(mistypedResult.ok)
        assertTrue(mistypedResult.errors.any { it.contains("memoryEnabled") })
        assertTrue(prefs.memoryEnabled)
    }

    @Test
    fun `import accepts the reserved top level mcpServers section`() {
        val vault = InMemoryVault()
        val core = coreOf(newPrefs(vault), vault)
        val server = McpServerConfig(id = "srv", displayName = "S", url = "https://s.test/mcp")
        val section = Json.parseToJsonElement(McpServerConfigCodec.encode(listOf(server)))

        val result = core.import(ExportCodec.encode(ConfigDocument(mcpServers = section), passphrase), passphrase)
        assertTrue(result.ok)
        assertEquals(listOf(server), core.listMcpServers())

        val malformed = ConfigDocument(mcpServers = JsonPrimitive("not-an-array"))
        val bad = core.import(ExportCodec.encode(malformed, passphrase), passphrase)
        assertFalse(bad.ok)
        assertEquals(listOf(server), core.listMcpServers())
    }

    @Test
    fun `import rejects a wrong passphrase without writing`() {
        val vault = InMemoryVault()
        val prefs = newPrefs(vault)
        val core = coreOf(prefs, vault)
        val document = ConfigDocument(settings = mapOf("memoryEnabled" to JsonPrimitive(false)))
        val text = ExportCodec.encode(document, passphrase)

        val result = core.import(text, "not the passphrase".toCharArray())

        assertFalse(result.ok)
        assertTrue(result.errors.isNotEmpty())
        assertTrue(prefs.memoryEnabled)
    }
}
