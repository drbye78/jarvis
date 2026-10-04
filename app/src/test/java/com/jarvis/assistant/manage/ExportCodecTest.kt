package com.jarvis.assistant.manage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The export envelope is always encrypted: valid round-trips, wrong passphrase
 * and tampered ciphertext both fail GCM auth, a newer format version is
 * rejected before decryption, and secrets are stripped unless explicitly opted
 * in.
 */
class ExportCodecTest {

    private val passphrase = "correct horse battery".toCharArray()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private fun document(
        settings: Map<String, JsonElement> = mapOf(
            "speechBackend" to JsonPrimitive("sber"),
            "followUpWindowMs" to JsonPrimitive(3_000),
            "memoryEnabled" to JsonPrimitive(true),
        ),
        secrets: Map<String, JsonElement> = mapOf("yandexApiKey" to JsonPrimitive("secret-value")),
    ) = ConfigDocument(
        appVersion = "0.2.2",
        exportedAt = 1_700_000_000_000L,
        settings = settings,
        secrets = secrets,
        mcpServers = JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive("srv-1"))))),
        home = JsonObject(mapOf("reserved" to JsonPrimitive(true))),
    )

    @Test
    fun `round trip preserves settings and reserved sections`() {
        val original = document()
        val decoded = ExportCodec.decode(ExportCodec.encode(original, passphrase), passphrase)

        assertEquals(original.settings, decoded.settings)
        assertEquals(ExportCodec.FORMAT, decoded.format)
        assertEquals(ExportCodec.FORMAT_VERSION, decoded.formatVersion)
        assertEquals("0.2.2", decoded.appVersion)
        assertEquals(original.mcpServers, decoded.mcpServers)
        assertEquals(original.home, decoded.home)
    }

    @Test
    fun `secrets are excluded by default`() {
        val decoded = ExportCodec.decode(ExportCodec.encode(document(), passphrase), passphrase)
        assertTrue(decoded.secrets.isEmpty())
        assertTrue(decoded.settings.isNotEmpty())
    }

    @Test
    fun `secrets are included only with the explicit flag`() {
        val original = document()
        val encoded = ExportCodec.encode(original, passphrase, includeSecrets = true)
        val decoded = ExportCodec.decode(encoded, passphrase)
        assertEquals(original.secrets, decoded.secrets)
        assertEquals(original.settings, decoded.settings)
    }

    @Test
    fun `wrong passphrase fails with a typed auth error`() {
        val encoded = ExportCodec.encode(document(), passphrase)
        expectFailure<ExportException.WrongPassphrase> {
            ExportCodec.decode(encoded, "not the passphrase".toCharArray())
        }
    }

    @Test
    fun `tampered ciphertext fails GCM authentication`() {
        val encoded = ExportCodec.encode(document(), passphrase)
        val envelope = json.decodeFromString(ExportEnvelope.serializer(), encoded)
        val ciphertext = Base64.getDecoder().decode(envelope.ciphertext)
        ciphertext[0] = (ciphertext[0].toInt() xor 0x01).toByte()
        val tampered = json.encodeToString(
            ExportEnvelope.serializer(),
            envelope.copy(ciphertext = Base64.getEncoder().encodeToString(ciphertext)),
        )
        expectFailure<ExportException.WrongPassphrase> { ExportCodec.decode(tampered, passphrase) }
    }

    @Test
    fun `a newer format version is rejected before decryption`() {
        val encoded = ExportCodec.encode(document(), passphrase)
        val envelope = json.decodeFromString(ExportEnvelope.serializer(), encoded)
        val bumped = json.encodeToString(
            ExportEnvelope.serializer(),
            envelope.copy(formatVersion = ExportCodec.FORMAT_VERSION + 1),
        )
        val failure = expectFailure<ExportException.VersionTooNew> { ExportCodec.decode(bumped, passphrase) }
        assertEquals(ExportCodec.FORMAT_VERSION + 1, failure.found)
        assertEquals(ExportCodec.FORMAT_VERSION, failure.supported)
    }

    @Test
    fun `an unexpected format id is rejected`() {
        val encoded = ExportCodec.encode(document(), passphrase)
        val envelope = json.decodeFromString(ExportEnvelope.serializer(), encoded)
        val foreign = json.encodeToString(
            ExportEnvelope.serializer(),
            envelope.copy(format = "someone.else"),
        )
        expectFailure<ExportException.Malformed> { ExportCodec.decode(foreign, passphrase) }
    }

    @Test
    fun `malformed input is reported as malformed`() {
        for (raw in listOf("not json", "{}", "[1,2,3]", "")) {
            expectFailure<ExportException.Malformed> { ExportCodec.decode(raw, passphrase) }
        }
    }

    @Test
    fun `an empty passphrase is refused on both directions`() {
        expectFailure<ExportException.Malformed> { ExportCodec.encode(document(), CharArray(0)) }
        val encoded = ExportCodec.encode(document(), passphrase)
        expectFailure<ExportException.Malformed> { ExportCodec.decode(encoded, CharArray(0)) }
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        val failure = runCatching(block).exceptionOrNull()
        if (failure !is T) {
            throw AssertionError("expected ${T::class.simpleName} but was $failure")
        }
        return failure
    }
}
