package com.jarvis.assistant.speech

import com.jarvis.assistant.R
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.speech.tts.VoiceCatalog
import com.jarvis.assistant.speech.tts.YandexVoiceSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-backend TTS voice catalog.
 *
 * What is worth pinning here is PROVIDER ISOLATION plus the role metadata the
 * Settings dropdown depends on. The two backends have disjoint voice
 * namespaces, so an id only means something next to the client that will speak
 * it; and the role suggestions are only a real improvement over a flat list if
 * every suggested role is one the v3 docs actually list for that voice — a
 * wrong suggestion sends a pair the service rejects, which surfaces as a failed
 * spoken sentence rather than a compile error.
 */
class VoiceCatalogTest {

    @Test
    fun `the Sber catalog ships exactly the verified Mila preset`() {
        val sber = VoiceCatalog.SBER_VOICES.single()
        assertEquals("Mila", sber.id)
        assertEquals(R.string.voice_mila, sber.labelRes!!)
    }

    @Test
    fun `the Sber preset declares no roles`() {
        // Salute has no role concept at all: a non-empty list here would invite
        // the UI to send a role hint the Sber request shape cannot carry.
        assertTrue(VoiceCatalog.SBER_VOICES.all { it.roles.isEmpty() })
    }

    @Test
    fun `each backend voice list is non-empty, unique and trimmed`() {
        val catalogs = mapOf(
            "Sber" to VoiceCatalog.SBER_VOICES,
            "Yandex" to VoiceCatalog.YANDEX_VOICES,
        )
        for ((backend, voices) in catalogs) {
            assertTrue("$backend must expose at least one voice", voices.isNotEmpty())
            assertEquals(
                "$backend has a duplicate voice id",
                voices.size,
                voices.map { it.id }.toSet().size,
            )
            assertTrue(
                "$backend voice ids must not carry stray whitespace",
                voices.none { it.id != it.id.trim() },
            )
        }
    }

    @Test
    fun `the yandex catalog contains the service default`() {
        assertTrue(
            "the codec's default voice must be selectable in the UI",
            VoiceCatalog.YANDEX_VOICES.any { it.id == YandexVoiceSpec.DEFAULT_VOICE },
        )
        // The config is what an install with a blank pref falls back to, so a
        // mismatch would make the Settings preview a different speaker than the
        // assistant then uses.
        assertEquals(YandexVoiceSpec.DEFAULT_VOICE, JarvisConfig().yandexTtsVoice)
    }

    @Test
    fun `yandex voice ids are rendered verbatim, with no label resource`() {
        // The v3 voice names are API proper nouns, not translatable UI copy.
        assertTrue(VoiceCatalog.YANDEX_VOICES.all { it.labelRes == null })
    }

    @Test
    fun `the Sber and Yandex voice sets are disjoint`() {
        val sber = VoiceCatalog.SBER_VOICES.map { it.id.lowercase() }.toSet()
        val yandex = VoiceCatalog.YANDEX_VOICES.map { it.id.lowercase() }.toSet()
        assertEquals(
            "a shared id would make the active backend ambiguous",
            emptySet<String>(),
            sber intersect yandex,
        )
    }

    @Test
    fun `the yandex role vocabulary is non-blank and unique`() {
        assertTrue(VoiceCatalog.YANDEX_ROLES.isNotEmpty())
        assertEquals(
            VoiceCatalog.YANDEX_ROLES.size,
            VoiceCatalog.YANDEX_ROLES.toSet().size,
        )
        assertTrue(VoiceCatalog.YANDEX_ROLES.none { it.isBlank() })
    }

    @Test
    fun `every documented per-voice role belongs to the role vocabulary`() {
        val vocabulary = VoiceCatalog.YANDEX_ROLES.toSet()
        for (voice in VoiceCatalog.YANDEX_VOICES) {
            assertTrue(
                "${voice.id} lists a role outside the vocabulary",
                vocabulary.containsAll(voice.roles),
            )
            assertEquals(
                "${voice.id} repeats a role",
                voice.roles.size,
                voice.roles.toSet().size,
            )
            assertTrue("${voice.id} lists a blank role", voice.roles.none { it.isBlank() })
        }
    }

    @Test
    fun `every role in the vocabulary is documented for at least one voice`() {
        // Otherwise the vocabulary would advertise a role no voice claims to
        // support — reachable in the UI only for the voices that document none.
        val documented = VoiceCatalog.YANDEX_VOICES.flatMap { it.roles }.toSet()
        assertEquals(VoiceCatalog.YANDEX_ROLES.toSet(), documented)
    }

    @Test
    fun `role suggestions narrow to the selected voice's documented roles`() {
        assertEquals(
            listOf("neutral", "whisper", "friendly"),
            VoiceCatalog.yandexRolesFor("marina"),
        )
        assertEquals(listOf("neutral", "good"), VoiceCatalog.yandexRolesFor("alena"))
    }

    @Test
    fun `a voice with no documented roles offers none (fail-closed)`() {
        // An undocumented role is a HARD service error, so the catalog must not
        // widen a roleless voice to the union.
        assertTrue(VoiceCatalog.yandexRolesFor("filipp").isEmpty())
        assertTrue(VoiceCatalog.yandexRolesFor("madi_ru").isEmpty())
    }

    @Test
    fun `an unknown voice id offers no roles (fail-closed)`() {
        assertTrue(VoiceCatalog.yandexRolesFor("not-a-voice").isEmpty())
        assertTrue(VoiceCatalog.yandexRolesFor("").isEmpty())
    }

    @Test
    fun `the russian voices carry their documented roles`() {
        assertEquals(
            listOf("neutral", "strict", "whisper"),
            VoiceCatalog.yandexRolesFor("saule_ru"),
        )
        assertEquals(
            listOf("neutral", "strict", "friendly"),
            VoiceCatalog.yandexRolesFor("zamira_ru"),
        )
        assertEquals(
            listOf("neutral", "strict", "friendly"),
            VoiceCatalog.yandexRolesFor("zhanar_ru"),
        )
        assertEquals(
            listOf("neutral", "strict", "friendly", "whisper"),
            VoiceCatalog.yandexRolesFor("yulduz_ru"),
        )
    }

    @Test
    fun `validRoleFor accepts only a role the voice documents`() {
        assertEquals("whisper", VoiceCatalog.validRoleFor("marina", "whisper"))
        assertEquals("whisper", VoiceCatalog.validRoleFor("marina", "  whisper  "))
        // `good` is documented for alena but NOT for marina — the exact
        // voice-dependent case the policy exists for.
        assertEquals("good", VoiceCatalog.validRoleFor("alena", "good"))
        assertNull(VoiceCatalog.validRoleFor("marina", "good"))
        assertNull(VoiceCatalog.validRoleFor("filipp", "good"))
        assertNull(VoiceCatalog.validRoleFor("madi_ru", "good"))
        assertNull(VoiceCatalog.validRoleFor("not-a-voice", "good"))
        assertNull(VoiceCatalog.validRoleFor("marina", null))
        assertNull(VoiceCatalog.validRoleFor("marina", ""))
        assertNull(VoiceCatalog.validRoleFor("marina", "   "))
    }

    @Test
    fun `capabilities describe what each backend can express`() {
        // An exhaustive `when` with no `else`: a new backend fails compilation
        // here until this test declares its capabilities.
        for (backend in SpeechBackend.entries) {
            val caps = VoiceCatalog.capabilitiesFor(backend)
            when (backend) {
                SpeechBackend.YANDEX -> {
                    assertTrue("Yandex supports roles", caps.roles)
                    assertTrue("Yandex supports speed", caps.speed)
                }
                SpeechBackend.SBER -> {
                    assertFalse("Sber has no role concept", caps.roles)
                    assertFalse("Sber speed is SSML-only", caps.speed)
                }
            }
        }
    }
}
