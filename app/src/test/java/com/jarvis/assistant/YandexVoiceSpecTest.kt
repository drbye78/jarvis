package com.jarvis.assistant

import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.speech.tts.YandexVoiceSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The in-band `"<voice>:<role>"` voice convention, pinned in both directions.
 *
 * This matters more than its size suggests: the packing has three independent
 * producers/consumers (the settings UI, [com.jarvis.assistant.di.AppGraph]'s
 * `voiceSource`, and the TTS client at synthesis time) and a drift between the
 * encode and the decode does NOT fail loudly — Yandex receives the role glued
 * into the speaker name and either rejects it as an unknown voice or speaks
 * with the wrong one. Round-tripping is therefore the assertion that counts.
 */
class YandexVoiceSpecTest {

    @Test
    fun `voice and role round-trip through the packing`() {
        val packed = YandexVoiceSpec.join(voice = "marina", role = "good")
        assertEquals("marina:good", packed)

        val (voice, role) = YandexVoiceSpec.split(packed)
        assertEquals("marina", voice)
        assertEquals("good", role)
    }

    @Test
    fun `a blank or whitespace role collapses to the bare voice`() {
        // The service then applies its own default role — an empty Hints entry
        // would be a request error, not a "no preference".
        assertEquals("marina", YandexVoiceSpec.join("marina", ""))
        assertEquals("marina", YandexVoiceSpec.join("marina", "   "))
        assertEquals("marina", YandexVoiceSpec.join("marina", "\n"))
    }

    @Test
    fun `surrounding whitespace is trimmed on both halves`() {
        assertEquals("ermil:strict", YandexVoiceSpec.join("  ermil ", " strict "))
    }

    @Test
    fun `a blank voice stays blank rather than becoming a role-only spec`() {
        // A role names no speaker, so there is nothing sensible to send. The
        // caller substitutes the config default; the codec must not invent one,
        // or a deliberately blank pref would be indistinguishable from it.
        assertEquals("", YandexVoiceSpec.join("", "good"))
        assertEquals("", YandexVoiceSpec.join("   ", "good"))
    }

    @Test
    fun `a spec with no separator is all voice`() {
        val (voice, role) = YandexVoiceSpec.split("filipp")
        assertEquals("filipp", voice)
        assertNull(role)
    }

    @Test
    fun `a leading separator is treated as part of the voice name`() {
        // "indexOf <= 0" — an empty speaker means the whole string is the
        // speaker. Reinterpreting ":good" as (voice="", role="good") would
        // silently drop the user's text and ask for a nameless voice.
        val (voice, role) = YandexVoiceSpec.split(":good")
        assertEquals(":good", voice)
        assertNull(role)
    }

    @Test
    fun `a trailing separator yields a null role`() {
        // This is the shape the settings UI produces when the role field is
        // emptied: it must degrade to the voice alone, not to an empty role.
        val (voice, role) = YandexVoiceSpec.split("marina:")
        assertEquals("marina", voice)
        assertNull(role)
    }

    @Test
    fun `only the first separator splits`() {
        // Voice ids cannot contain ':', but a user can type one. Splitting on
        // the FIRST separator keeps the speaker intact and treats the rest as
        // the role, so the voice is still a name Yandex can look up.
        val (voice, role) = YandexVoiceSpec.split("marina:good:evil")
        assertEquals("marina", voice)
        assertEquals("good:evil", role)
    }

    @Test
    fun `the empty spec decodes to an empty voice with no role`() {
        val (voice, role) = YandexVoiceSpec.split("")
        assertEquals("", voice)
        assertNull(role)
    }

    @Test
    fun `the codec default matches the config default voice`() {
        // The settings card and the graph both fall back to one of these for a
        // blank pref. If they disagreed, «Проверить голос» would preview a
        // different speaker than the assistant then used — and users would
        // report it as "the test button lies".
        assertEquals(YandexVoiceSpec.DEFAULT_VOICE, JarvisConfig().yandexTtsVoice)
    }
}
