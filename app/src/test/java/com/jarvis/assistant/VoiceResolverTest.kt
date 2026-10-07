package com.jarvis.assistant

import com.jarvis.assistant.speech.SpeechBackend
import com.jarvis.assistant.speech.tts.VoiceResolver
import com.jarvis.assistant.speech.tts.YandexVoiceSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The backend voice-namespace boundary.
 *
 * This is the fix for a confirmed production bug: an unvalidated
 * `yandexTtsVoice` carrying the Sber id `Mila` was sent to Yandex and answered
 * with a hard `PERMISSION_DENIED: ... voice=Mila`, aborting the whole spoken
 * sentence. The two backends have disjoint voice namespaces, so the resolver
 * must pass Sber free text through but never forward a Yandex-unknown speaker.
 */
class VoiceResolverTest {

    @Test
    fun `sber passes a free-text voice through verbatim`() {
        // The Salute pool is documented as drifting, so a free-text id is
        // intentional and must survive untouched.
        assertEquals("May_24000", VoiceResolver.resolve(SpeechBackend.SBER, "May_24000", "Mila"))
        // Free text is preserved apart from the trim the resolver always applies.
        assertEquals("May_24000", VoiceResolver.resolve(SpeechBackend.SBER, "  May_24000  ", "Mila"))
        assertEquals("custom_pool_voice", VoiceResolver.resolve(SpeechBackend.SBER, "custom_pool_voice", "Mila"))
    }

    @Test
    fun `sber falls back for a blank or absent voice`() {
        assertEquals("Mila", VoiceResolver.resolve(SpeechBackend.SBER, "", "Mila"))
        assertEquals("Mila", VoiceResolver.resolve(SpeechBackend.SBER, "   ", "Mila"))
        assertEquals("Mila", VoiceResolver.resolve(SpeechBackend.SBER, null, "Mila"))
    }

    @Test
    fun `yandex keeps a valid voice id`() {
        assertEquals("marina", VoiceResolver.resolve(SpeechBackend.YANDEX, "marina", "marina"))
        assertEquals("alena", VoiceResolver.resolve(SpeechBackend.YANDEX, "alena", "marina"))
    }

    @Test
    fun `yandex canonicalizes a valid voice id case-insensitively`() {
        assertEquals("marina", VoiceResolver.resolve(SpeechBackend.YANDEX, "MARINA", "marina"))
        assertEquals("alena", VoiceResolver.resolve(SpeechBackend.YANDEX, "  Alena  ", "marina"))
    }

    @Test
    fun `yandex maps a foreign sber id to the fallback`() {
        // The exact regression: `Mila` is a Salute pool id, not a Yandex v3
        // speaker. Sending it through aborts the sentence, so it must resolve to
        // the backend default instead of being kept.
        assertEquals("marina", VoiceResolver.resolve(SpeechBackend.YANDEX, "Mila", "marina"))
        assertEquals("marina", VoiceResolver.resolve(SpeechBackend.YANDEX, "mila", "marina"))
    }

    @Test
    fun `yandex maps a blank or unknown voice to the fallback`() {
        assertEquals("marina", VoiceResolver.resolve(SpeechBackend.YANDEX, "", "marina"))
        assertEquals("marina", VoiceResolver.resolve(SpeechBackend.YANDEX, "   ", "marina"))
        assertEquals("marina", VoiceResolver.resolve(SpeechBackend.YANDEX, null, "marina"))
        assertEquals("marina", VoiceResolver.resolve(SpeechBackend.YANDEX, "not-a-voice", "marina"))
    }

    @Test
    fun `the fallback is returned verbatim, not the catalog default`() {
        // The caller owns the fallback so the resolver stays a pure policy.
        assertEquals("custom", VoiceResolver.resolve(SpeechBackend.YANDEX, "Mila", "custom"))
        assertEquals("marina", YandexVoiceSpec.DEFAULT_VOICE)
    }
}
