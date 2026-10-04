package com.jarvis.assistant.manage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The plaintext document wrapped by an encrypted export (§14.6).
 *
 * `settings` is the JSON-native projection of the [ManagedSetting] values
 * (annotated by `ManagementBindings` in a later wave); [ManagedValue] is the
 * typed view used by the REST layer. [secrets] is kept SEPARATE and is stripped
 * by default: it only survives [ExportCodec.encode] when the caller passes
 * `includeSecrets = true`. [mcpServers] and [home] are reserved placeholders —
 * `home` is reserved from day one even though its R4 screens are not built yet.
 */
@Serializable
data class ConfigDocument(
    val format: String = ExportCodec.FORMAT,
    val formatVersion: Int = ExportCodec.FORMAT_VERSION,
    val appVersion: String = "",
    val exportedAt: Long = 0L,
    val settings: Map<String, JsonElement> = emptyMap(),
    val secrets: Map<String, JsonElement> = emptyMap(),
    val mcpServers: JsonElement = JsonArray(emptyList()),
    val home: JsonElement = JsonObject(emptyMap()),
)

/** Cleartext KDF parameters in the envelope header, so import can derive the key. */
@Serializable
data class ExportKdfHeader(
    val algo: String,
    val memoryKb: Int,
    val iterations: Int,
    val salt: String,
)

/**
 * The on-disk artifact: a single AES-256-GCM envelope with only this small
 * cleartext header. Everything user-meaningful lives in [ciphertext].
 */
@Serializable
data class ExportEnvelope(
    val format: String,
    val formatVersion: Int,
    val appVersion: String,
    val kdf: ExportKdfHeader,
    val nonce: String,
    val ciphertext: String,
)

/**
 * Typed failures from the export codec. [decode] never returns a partial
 * document; a caller distinguishes an auth failure from a format failure
 * without string-matching.
 */
sealed class ExportException(message: String) : Exception(message) {
    /**
     * The GCM tag did not verify. Cryptographically this is indistinguishable
     * from a tampered ciphertext, so it covers both.
     */
    class WrongPassphrase : ExportException("wrong passphrase or tampered export")

    /** The artifact was written by a newer app than this one. */
    class VersionTooNew(val found: Int, val supported: Int) :
        ExportException("export format version $found is newer than supported $supported")

    /** Structurally unusable input (bad JSON, bad Base64, unknown KDF, empty passphrase). */
    class Malformed(reason: String) : ExportException("malformed export: $reason")
}

/**
 * The config export/import codec. **The export is always encrypted — there is
 * no plaintext path.** The on-disk artifact is one AES-256-GCM envelope whose
 * key is derived from the user passphrase with Argon2id (16-byte salt,
 * 12-byte nonce); only a small versioned header is cleartext.
 *
 * Secrets are excluded by default. Including them is an explicit opt-in; it is
 * never automatic and, because the container is always encrypted, it is never
 * a *plaintext* exposure (the explicit trade is documented in §14.6).
 */
object ExportCodec {

    /** Stable artifact format id; a mismatch is rejected on import. */
    const val FORMAT = "jarvis.config"

    /** Current artifact version; a larger stored version is [ExportException.VersionTooNew]. */
    const val FORMAT_VERSION = 1

    const val KDF_ALGO = PasswordHasher.ALGO_ARGON2ID
    const val SALT_BYTES = 16
    const val NONCE_BYTES = 12
    const val KEY_BYTES = 32
    const val GCM_TAG_BITS = 128

    private const val CIPHER = "AES/GCM/NoPadding"
    private const val AES = "AES"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val random = SecureRandom()

    /**
     * Encrypt [document] under [passphrase]. When [includeSecrets] is false
     * (the default) the `secrets` section is dropped before encryption.
     */
    fun encode(
        document: ConfigDocument,
        passphrase: CharArray,
        includeSecrets: Boolean = false,
    ): String {
        requirePassphrase(passphrase)
        val effective = if (includeSecrets) document else document.copy(secrets = emptyMap())
        val salt = randomBytes(SALT_BYTES)
        val nonce = randomBytes(NONCE_BYTES)
        val key = PasswordHasher.deriveRaw(passphrase, salt, Argon2Params.DEFAULT, KEY_BYTES)
        val plaintext = json.encodeToString(ConfigDocument.serializer(), effective)
            .toByteArray(Charsets.UTF_8)
        val ciphertext = encrypt(key, nonce, plaintext)
        val envelope = ExportEnvelope(
            format = FORMAT,
            formatVersion = FORMAT_VERSION,
            appVersion = document.appVersion,
            kdf = ExportKdfHeader(
                algo = KDF_ALGO,
                memoryKb = Argon2Params.DEFAULT.memoryKb,
                iterations = Argon2Params.DEFAULT.iterations,
                salt = base64(salt),
            ),
            nonce = base64(nonce),
            ciphertext = base64(ciphertext),
        )
        return json.encodeToString(ExportEnvelope.serializer(), envelope)
    }

    /**
     * Decrypt [text] with [passphrase]. The version gate runs BEFORE decryption
     * so a newer artifact is reported as [ExportException.VersionTooNew] rather
     * than as a wrong passphrase.
     */
    fun decode(text: String, passphrase: CharArray): ConfigDocument {
        requirePassphrase(passphrase)
        val envelope = parseEnvelope(text)
        validateEnvelope(envelope)
        val salt = decodeBase64(envelope.kdf.salt, "kdf salt")
        val nonce = decodeBase64(envelope.nonce, "nonce")
        val ciphertext = decodeBase64(envelope.ciphertext, "ciphertext")
        val params = Argon2Params(
            memoryKb = envelope.kdf.memoryKb,
            iterations = envelope.kdf.iterations,
            parallelism = Argon2Params.DEFAULT.parallelism,
        )
        val key = PasswordHasher.deriveRaw(passphrase, salt, params, KEY_BYTES)
        return parseDocument(decrypt(key, nonce, ciphertext))
    }

    /**
     * Rejects a newer format BEFORE decryption (so it is not misreported as a
     * wrong passphrase) and any header mismatch. At most two throws so the
     * detekt ThrowsCount rule stays satisfied.
     */
    private fun validateEnvelope(envelope: ExportEnvelope) {
        if (envelope.formatVersion > FORMAT_VERSION) {
            throw ExportException.VersionTooNew(envelope.formatVersion, FORMAT_VERSION)
        }
        val reason = when {
            envelope.format != FORMAT -> "unexpected format '${envelope.format}'"
            envelope.kdf.algo != KDF_ALGO -> "unsupported kdf '${envelope.kdf.algo}'"
            else -> null
        }
        if (reason != null) throw ExportException.Malformed(reason)
    }

    private fun requirePassphrase(passphrase: CharArray) {
        if (passphrase.isEmpty()) throw ExportException.Malformed("passphrase is required")
    }

    private fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, AES), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(plaintext)
    }

    private fun decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, AES), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.doFinal(ciphertext)
        } catch (_: AEADBadTagException) {
            throw ExportException.WrongPassphrase()
        } catch (_: GeneralSecurityException) {
            throw ExportException.Malformed("decryption failed")
        }
    }

    private fun parseEnvelope(text: String): ExportEnvelope = try {
        json.decodeFromString(ExportEnvelope.serializer(), text.trim())
    } catch (_: IllegalArgumentException) {
        throw ExportException.Malformed("not a JSON export envelope")
    }

    private fun parseDocument(bytes: ByteArray): ConfigDocument = try {
        json.decodeFromString(ConfigDocument.serializer(), bytes.toString(Charsets.UTF_8))
    } catch (_: IllegalArgumentException) {
        throw ExportException.Malformed("decrypted payload is not a config document")
    }

    private fun decodeBase64(value: String, field: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        throw ExportException.Malformed("invalid Base64 $field")
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}
