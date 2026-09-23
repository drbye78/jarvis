package com.jarvis.assistant.speech

import com.jarvis.assistant.R
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.speech.tts.VoiceCatalog
import com.jarvis.assistant.speech.tts.YandexVoiceSpec
import org.junit.Assert.assertEquals
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
    fun `a voice with no documented roles falls back to the whole vocabulary`() {
        // "undocumented" is not "unsupported": the role field stays free text,
        // so the menu must not be empty.
        assertEquals(VoiceCatalog.YANDEX_ROLES, VoiceCatalog.yandexRolesFor("filipp"))
        assertEquals(VoiceCatalog.YANDEX_ROLES, VoiceCatalog.yandexRolesFor("madi_ru"))
    }

    @Test
    fun `an unknown voice id falls back to the whole vocabulary`() {
        assertEquals(VoiceCatalog.YANDEX_ROLES, VoiceCatalog.yandexRolesFor("not-a-voice"))
        assertEquals(VoiceCatalog.YANDEX_ROLES, VoiceCatalog.yandexRolesFor(""))
    }
}
