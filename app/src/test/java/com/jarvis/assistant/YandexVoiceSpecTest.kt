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

    @Test
    fun `every grammar shape round-trips`() {
        // voice (no role, no speed)
        val bare = YandexVoiceSpec.split(YandexVoiceSpec.join("marina", "", null))
        assertEquals("marina", bare.voice)
        assertNull(bare.role)
        assertNull(bare.speed)

        // voice:role
        assertEquals("marina:whisper", YandexVoiceSpec.join("marina", "whisper", null))
        val withRole = YandexVoiceSpec.split("marina:whisper")
        assertEquals("marina", withRole.voice)
        assertEquals("whisper", withRole.role)
        assertNull(withRole.speed)

        // voice@speed
        assertEquals("marina@1.25", YandexVoiceSpec.join("marina", "", 1.25f))
        val withSpeed = YandexVoiceSpec.split("marina@1.25")
        assertEquals("marina", withSpeed.voice)
        assertNull(withSpeed.role)
        assertEquals(1.25f, withSpeed.speed!!, 0.0f)

        // voice:role@speed
        assertEquals("marina:whisper@0.75", YandexVoiceSpec.join("marina", "whisper", 0.75f))
        val all = YandexVoiceSpec.split("marina:whisper@0.75")
        assertEquals("marina", all.voice)
        assertEquals("whisper", all.role)
        assertEquals(0.75f, all.speed!!, 0.0f)
    }

    @Test
    fun `a default or absent speed emits no separator`() {
        // Byte-identical to the pre-speed wire: 1.0 is the service default and
        // must not be sent as an explicit hint.
        assertEquals("marina", YandexVoiceSpec.join("marina", "", 1.0f))
        assertEquals("marina", YandexVoiceSpec.join("marina", "", null))
        assertEquals("marina:good", YandexVoiceSpec.join("marina", "good", 1.0f))
    }

    @Test
    fun `speed formatting is locale-independent`() {
        // Float.toString is locale-independent; a String.format path would
        // render "1,25" under a comma-decimal default locale and silently break
        // the packing (the '@' suffix would decode to null and the speed would
        // vanish).
        assertEquals("marina@1.25", YandexVoiceSpec.join("marina", "", 1.25f))
        assertEquals("marina@0.75", YandexVoiceSpec.join("marina", "", 0.75f))
    }

    @Test
    fun `a malformed or out-of-range speed decodes to null with the voice preserved`() {
        val malformed = listOf(
            "marina@abc",
            "marina@",
            "marina@0.05",
            "marina@3.5",
            "marina@NaN",
            "marina@Infinity",
            "marina@-1",
        )
        for (spec in malformed) {
            val split = YandexVoiceSpec.split(spec)
            assertEquals("voice must survive a bad suffix: $spec", "marina", split.voice)
            assertNull("$spec must fail closed", split.speed)
        }
    }

    @Test
    fun `a real speed at the interval boundaries is accepted`() {
        assertEquals(0.1f, YandexVoiceSpec.split("marina@0.1").speed!!, 0.0f)
        assertEquals(3.0f, YandexVoiceSpec.split("marina@3.0").speed!!, 0.0f)
    }

    @Test
    fun `only the first colon splits even with a speed suffix`() {
        val split = YandexVoiceSpec.split("marina:good:evil@1.5")
        assertEquals("marina", split.voice)
        assertEquals("good:evil", split.role)
        assertEquals(1.5f, split.speed!!, 0.0f)
    }
}
