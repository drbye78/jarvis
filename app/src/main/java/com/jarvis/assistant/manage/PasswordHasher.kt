package com.jarvis.assistant.manage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The Argon2id cost parameters a hash was produced with. Stored per record so
 * a future parameter bump verifies old hashes without weakening new ones.
 */
data class Argon2Params(
    val memoryKb: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    companion object {
        /** OWASP first-choice Argon2id profile: m=46 MiB, t=1, p=1. */
        val DEFAULT = Argon2Params(memoryKb = 47_104, iterations = 1, parallelism = 1)

        /**
         * Constrained-CPU fallback (m=19 MiB, t=2) for low-RAM/Kirin-class
         * devices where 46 MiB per authentication is an unacceptable spike.
         * Documented alternative, not the default.
         */
        val LOW_MEMORY = Argon2Params(memoryKb = 19_456, iterations = 2, parallelism = 1)
    }
}

/**
 * A serializable Argon2id password hash. [version] is THIS record's schema
 * version (not the Argon2 algorithm version), so the wire shape can evolve.
 * The salt and tag are Base64 (standard alphabet).
 */
@Serializable
data class PasswordRecord(
    val algo: String = PasswordHasher.ALGO_ARGON2ID,
    val version: Int = PasswordHasher.RECORD_VERSION,
    val memoryKb: Int,
    val iterations: Int,
    val parallelism: Int,
    val saltB64: String,
    val hashB64: String,
)

/**
 * Argon2id hashing + constant-time verification for the management password,
 * built on BouncyCastle's pure-Java `Argon2BytesGenerator` (no JNA/JNI).
 *
 * Provider policy mirrors `TlsCertFactory`: the BC classes are used directly
 * with NO global provider registration (Android already ships a repackaged
 * provider named `BC`; registering the external one would collide).
 *
 * Verification uses [MessageDigest.isEqual], which is constant-time for equal
 * lengths and returns false without throwing on a length mismatch. Passwords
 * are passed as `CharArray` so the caller can zero them after use.
 */
object PasswordHasher {

    /** Stable KDF id carried in [PasswordRecord.algo] and the export header. */
    const val ALGO_ARGON2ID = "argon2id"

    /** Current [PasswordRecord] schema version. */
    const val RECORD_VERSION = 1

    const val SALT_BYTES = 16
    const val TAG_BYTES = 32

    /** 20 chars from an unambiguous alphabet: no O/0, I/l/1 confusables. */
    const val PASSWORD_LENGTH = 20
    const val PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

    /**
     * A shared, non-blocking `SecureRandom`. Deliberately NOT
     * `SecureRandom.getInstanceStrong()`: on Android the strong instance can
     * block on entropy at first use, which is unacceptable on the auth path.
     */
    private val random = SecureRandom()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** Generate a fresh password from [PASSWORD_ALPHABET] using [SecureRandom]. */
    fun generatePassword(length: Int = PASSWORD_LENGTH): String {
        require(length > 0) { "password length must be positive" }
        val chars = CharArray(length) { PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)] }
        return chars.concatToString()
    }

    /** Hash [password] with a fresh 16-byte salt and [params]. */
    fun hash(password: CharArray, params: Argon2Params = Argon2Params.DEFAULT): PasswordRecord {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val tag = deriveRaw(password, salt, params, TAG_BYTES)
        return PasswordRecord(
            algo = ALGO_ARGON2ID,
            version = RECORD_VERSION,
            memoryKb = params.memoryKb,
            iterations = params.iterations,
            parallelism = params.parallelism,
            saltB64 = Base64.getEncoder().encodeToString(salt),
            hashB64 = Base64.getEncoder().encodeToString(tag),
        )
    }

    /**
     * Verify [password] against [record] with [MessageDigest.isEqual]. Returns
     * false (never throws) for an unknown algo or a malformed/truncated record,
     * including a length mismatch between the derived and stored tags.
     */
    fun verify(password: CharArray, record: PasswordRecord): Boolean {
        if (record.algo != ALGO_ARGON2ID) return false
        if (record.memoryKb <= 0 || record.iterations <= 0 || record.parallelism <= 0) return false
        val salt = decodeBase64OrNull(record.saltB64) ?: return false
        val expected = decodeBase64OrNull(record.hashB64) ?: return false
        val actual = deriveRaw(
            password = password,
            salt = salt,
            params = Argon2Params(record.memoryKb, record.iterations, record.parallelism),
            outputBytes = TAG_BYTES,
        )
        return MessageDigest.isEqual(actual, expected)
    }

    /**
     * Raw Argon2id derivation, shared with [ExportCodec]'s key schedule.
     * Bytes are UTF-8 encoded from the char array (no intermediate String).
     */
    fun deriveRaw(
        password: CharArray,
        salt: ByteArray,
        params: Argon2Params,
        outputBytes: Int,
    ): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(params.memoryKb)
            .withIterations(params.iterations)
            .withParallelism(params.parallelism)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(parameters)
        val output = ByteArray(outputBytes)
        generator.generateBytes(password.concatToString().toByteArray(Charsets.UTF_8), output)
        return output
    }

    /** Serialize a record to canonical JSON (for the vault / export header). */
    fun encodeRecord(record: PasswordRecord): String =
        json.encodeToString(PasswordRecord.serializer(), record)

    /** Parse a record; null (never a throw) on malformed input. */
    fun decodeRecord(text: String?): PasswordRecord? {
        val trimmed = text?.trim()
        if (trimmed.isNullOrEmpty()) return null
        return try {
            json.decodeFromString(PasswordRecord.serializer(), trimmed)
        } catch (_: IllegalArgumentException) {
            // SerializationException extends IllegalArgumentException, so this
            // single catch covers every malformed-payload case.
            null
        }
    }

    private fun decodeBase64OrNull(value: String): ByteArray? = try {
        Base64.getDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        null
    }
}
