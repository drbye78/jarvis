package com.jarvis.assistant.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Argon2id hashing contract: generate/hash/verify, constant-time compare on a
 * length mismatch (never a throw), record JSON round-trip, and the documented
 * OWASP parameters.
 */
class PasswordHasherTest {

    private val fast = Argon2Params.LOW_MEMORY

    @Test
    fun `owasp default parameters are pinned`() {
        assertEquals(47_104, Argon2Params.DEFAULT.memoryKb)
        assertEquals(1, Argon2Params.DEFAULT.iterations)
        assertEquals(1, Argon2Params.DEFAULT.parallelism)
        assertEquals(19_456, Argon2Params.LOW_MEMORY.memoryKb)
        assertEquals(2, Argon2Params.LOW_MEMORY.iterations)
    }

    @Test
    fun `generated password uses the unambiguous alphabet and length`() {
        val password = PasswordHasher.generatePassword()
        assertEquals(PasswordHasher.PASSWORD_LENGTH, password.length)
        assertTrue(password.all { it in PasswordHasher.PASSWORD_ALPHABET })
        assertFalse(password.contains('O'))
        assertFalse(password.contains('0'))
        assertNotEquals(PasswordHasher.generatePassword(), PasswordHasher.generatePassword())
    }

    @Test
    fun `hash then verify succeeds and wrong password fails`() {
        val record = PasswordHasher.hash("correct horse".toCharArray(), fast)
        assertTrue(PasswordHasher.verify("correct horse".toCharArray(), record))
        assertFalse(PasswordHasher.verify("wrong horse".toCharArray(), record))
    }

    @Test
    fun `salt and tag have the documented sizes`() {
        val record = PasswordHasher.hash("secret".toCharArray(), fast)
        assertEquals(PasswordHasher.ALGO_ARGON2ID, record.algo)
        assertEquals(PasswordHasher.RECORD_VERSION, record.version)
        assertEquals(PasswordHasher.SALT_BYTES, Base64.getDecoder().decode(record.saltB64).size)
        assertEquals(PasswordHasher.TAG_BYTES, Base64.getDecoder().decode(record.hashB64).size)
    }

    @Test
    fun `record serialization round trips`() {
        val record = PasswordHasher.hash("secret".toCharArray(), fast)
        val decoded = PasswordHasher.decodeRecord(PasswordHasher.encodeRecord(record))
        assertEquals(record, decoded)
    }

    @Test
    fun `malformed record decodes to null`() {
        assertNull(PasswordHasher.decodeRecord(null))
        assertNull(PasswordHasher.decodeRecord(""))
        assertNull(PasswordHasher.decodeRecord("{not json"))
        assertNull(PasswordHasher.decodeRecord("{}"))
    }

    @Test
    fun `length mismatch on the stored tag does not throw and fails closed`() {
        val record = PasswordHasher.hash("secret".toCharArray(), fast)
        val truncated = record.copy(hashB64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
        assertFalse(PasswordHasher.verify("secret".toCharArray(), truncated))
    }

    @Test
    fun `unknown algorithm and invalid base64 fail closed`() {
        val record = PasswordHasher.hash("secret".toCharArray(), fast)
        assertFalse(PasswordHasher.verify("secret".toCharArray(), record.copy(algo = "pbkdf2")))
        assertFalse(PasswordHasher.verify("secret".toCharArray(), record.copy(saltB64 = "!!!!")))
        assertFalse(PasswordHasher.verify("secret".toCharArray(), record.copy(hashB64 = "!!!!")))
        assertFalse(PasswordHasher.verify("secret".toCharArray(), record.copy(iterations = 0)))
    }

    @Test
    fun `same salt and params derive the same key`() {
        val salt = ByteArray(PasswordHasher.SALT_BYTES) { it.toByte() }
        val first = PasswordHasher.deriveRaw("pw".toCharArray(), salt, fast, PasswordHasher.TAG_BYTES)
        val second = PasswordHasher.deriveRaw("pw".toCharArray(), salt, fast, PasswordHasher.TAG_BYTES)
        assertTrue(first.contentEquals(second))
        val other = PasswordHasher.deriveRaw("other".toCharArray(), salt, fast, PasswordHasher.TAG_BYTES)
        assertFalse(first.contentEquals(other))
    }
}
