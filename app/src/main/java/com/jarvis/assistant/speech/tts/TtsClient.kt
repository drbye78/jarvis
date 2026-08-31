package com.jarvis.assistant.speech.tts

import kotlinx.coroutines.flow.Flow

/**
 * Text-to-speech streaming client contract. Declared in its own pure file
 * (extracted from SaluteSpeechTts.kt) so JVM-only lanes — the audio
 * etiquette feedback, tests — can depend on the contract without the gRPC
 * implementation on the classpath.
 */
interface TtsClient {
    fun synthesizeStream(text: String, voice: String): Flow<ByteArray>
}
