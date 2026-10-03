package com.jarvis.assistant

import com.jarvis.assistant.tools.WriteBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Truth table for [WriteBinding] — the canonical digest that binds a write
 * confirmation to one exact `(server, tool, arguments)` call. Ordering and
 * whitespace must be irrelevant; any value difference must produce a
 * different digest; malformed/non-object input must fail closed (null).
 */
class WriteBindingTest {

    @Test
    fun `key order and whitespace do not change the digest`() {
        val a = WriteBinding.canonicalKey("srv", "send", """{"to":"a","body":"hi"}""")
        val b = WriteBinding.canonicalKey("srv", "send", """{ "body" : "hi" , "to" : "a" }""")
        assertEquals(a, b)
    }

    @Test
    fun `nested key order also does not change the digest`() {
        val a = WriteBinding.canonicalKey("srv", "send", """{"outer":{"x":1,"y":2}}""")
        val b = WriteBinding.canonicalKey("srv", "send", """{"outer":{"y":2,"x":1}}""")
        assertEquals(a, b)
    }

    @Test
    fun `a mutated value changes the digest`() {
        val a = WriteBinding.canonicalKey("srv", "send", """{"to":"a","body":"hi"}""")
        val b = WriteBinding.canonicalKey("srv", "send", """{"to":"a","body":"bye"}""")
        assertNotEquals(a, b)
    }

    @Test
    fun `a different server or tool changes the digest`() {
        val base = WriteBinding.canonicalKey("srv", "send", """{"to":"a"}""")
        assertNotEquals(base, WriteBinding.canonicalKey("other", "send", """{"to":"a"}"""))
        assertNotEquals(base, WriteBinding.canonicalKey("srv", "other_tool", """{"to":"a"}"""))
    }

    @Test
    fun `malformed or non-object arguments fail closed with null`() {
        listOf(
            "{not json}",
            "",
            "   ",
            "[]",
            "[1,2,3]",
            "42",
            "1.0",
            "\"a string\"",
            "null",
            "true",
        ).forEach { raw ->
            assertNull("must be null for: $raw", WriteBinding.canonicalKey("srv", "send", raw))
        }
    }

    @Test
    fun `numeric literals are not normalized`() {
        // `1` and `1.0` are the same number logically but DISTINCT literals;
        // the digest must not silently treat them as interchangeable.
        val int = WriteBinding.canonicalKey("srv", "send", """{"n":1}""")
        val dec = WriteBinding.canonicalKey("srv", "send", """{"n":1.0}""")
        assertNotEquals(int, dec)
    }

    @Test
    fun `an empty object still yields a stable non-null digest`() {
        assertEquals(
            WriteBinding.canonicalKey("srv", "send", "{}"),
            WriteBinding.canonicalKey("srv", "send", "{ }"),
        )
    }
}
