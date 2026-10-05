package com.jarvis.assistant.manage

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Metadata is a pure description and the [ManagedValue] projection must be
 * loss-free across the JSON boundary and strict on a type mismatch (no
 * coercion, no silent string fallback).
 */
class ManagedSettingTest {

    @Test
    fun `metadata is carried verbatim`() {
        val setting = ManagedSetting(
            key = "speechBackend",
            category = "speech",
            type = ManagedSettingType.ENUM,
            policy = ManagedApplyPolicy.SERVICE_RESTART,
            essential = true,
            secret = false,
        )
        assertEquals("speechBackend", setting.key)
        assertEquals("speech", setting.category)
        assertEquals(ManagedSettingType.ENUM, setting.type)
        assertEquals(ManagedApplyPolicy.SERVICE_RESTART, setting.policy)
        assertTrue(setting.essential)
        assertFalse(setting.secret)
    }

    @Test
    fun `blank key is rejected`() {
        val failure = runCatching {
            ManagedSetting("", "speech", ManagedSettingType.STRING, ManagedApplyPolicy.LIVE)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `options default to empty and are carried verbatim`() {
        val plain = ManagedSetting("ttsVoice", "speech", ManagedSettingType.STRING, ManagedApplyPolicy.LIVE)
        assertTrue("non-enum settings carry no vocabulary", plain.options.isEmpty())

        val enum = ManagedSetting(
            key = "speechBackend",
            category = "speech",
            type = ManagedSettingType.ENUM,
            policy = ManagedApplyPolicy.SERVICE_RESTART,
            options = listOf("sber", "yandex"),
        )
        assertEquals(listOf("sber", "yandex"), enum.options)
    }

    @Test
    fun `all managed apply policies exist`() {
        assertEquals(3, ManagedApplyPolicy.entries.size)
        assertTrue(ManagedApplyPolicy.entries.contains(ManagedApplyPolicy.APP_RESTART))
    }

    @Test
    fun `managed values round trip through json`() {
        val cases = listOf(
            ManagedSettingType.BOOLEAN to ManagedValue.Bool(true),
            ManagedSettingType.INT to ManagedValue.IntValue(42),
            ManagedSettingType.LONG to ManagedValue.LongValue(9_000_000_000L),
            ManagedSettingType.FLOAT to ManagedValue.FloatValue(1.5),
            ManagedSettingType.STRING to ManagedValue.StringValue("привет"),
            ManagedSettingType.ENUM to ManagedValue.EnumValue("project_eol"),
            ManagedSettingType.JSON_BLOB to ManagedValue.JsonValue(
                JsonObject(mapOf("a" to JsonPrimitive(1), "b" to JsonArray(listOf(JsonPrimitive("x"))))),
            ),
        )
        for ((type, value) in cases) {
            val decoded = ManagedValue.fromJsonElement(type, value.toJsonElement())
            assertEquals("round trip for $type", value, decoded)
        }
    }

    @Test
    fun `type mismatch yields null rather than coercing`() {
        assertNull(ManagedValue.fromJsonElement(ManagedSettingType.ENUM, JsonPrimitive(7)))
        assertNull(ManagedValue.fromJsonElement(ManagedSettingType.INT, JsonPrimitive("not a number")))
        assertNull(ManagedValue.fromJsonElement(ManagedSettingType.BOOLEAN, JsonPrimitive("maybe")))
    }

    @Test
    fun `string primitive stays a string for enum`() {
        val decoded = ManagedValue.fromJsonElement(ManagedSettingType.ENUM, JsonPrimitive("lan"))
        assertEquals(ManagedValue.EnumValue("lan"), decoded)
    }
}
