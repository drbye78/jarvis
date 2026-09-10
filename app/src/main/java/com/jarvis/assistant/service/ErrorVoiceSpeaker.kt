package com.jarvis.assistant.service

import android.content.Context
import android.speech.tts.TextToSpeech
import timber.log.Timber
import java.util.Locale

/**
 * Error-voice speaker: a dedicated system [TextToSpeech] engine used to
 * announce failures (it survives `player.flush()` barge-ins by design —
 * the streaming TTS player cannot kill it, so the error phrase is always
 * audible).
 *
 * speakError fix: TextToSpeech queues NOTHING before its async
 * onInit(SUCCESS) fires — the old code constructed the engine and
 * called speak() synchronously, so the FIRST error voice was always
 * dropped (exactly the one the user most needed to hear).
 */
class ErrorVoiceSpeaker(private val context: Context) {

    @Volatile private var errorTts: TextToSpeech? = null

    @Volatile private var errorTtsReady = false

    /**
     * Latest error message received while init was still pending; spoken
     * (once) from onInit on success. Only the newest message is kept — an
     * error voice delayed past a newer failure would speak stale text.
     */
    @Volatile private var pendingErrorMessage: String? = null

    fun speak(message: String) {
        Timber.e("Voice error: %s", message)
        runCatching {
            val existing = errorTts
            when {
                existing != null && errorTtsReady -> speakNow(existing, message)
                existing == null -> {
                    pendingErrorMessage = message
                    errorTts = TextToSpeech(context) { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            errorTtsReady = true
                            val pending = pendingErrorMessage
                            pendingErrorMessage = null
                            val tts = errorTts
                            if (tts != null && pending != null) speakNow(tts, pending)
                        } else {
                            // Init failed: shut the engine down honestly; a
                            // later error retry constructs a fresh one.
                            runCatching { errorTts?.shutdown() }
                            errorTts = null
                            errorTtsReady = false
                            pendingErrorMessage = null
                            Timber.w("Error-voice TTS init failed — message not spoken")
                        }
                    }
                }
                // Engine exists but init has not completed yet: park the
                // newest message; onInit speaks it on success.
                else -> pendingErrorMessage = message
            }
        }
    }

    /** Idempotent; safe to call from onDestroy whether or not anything was spoken. */
    fun release() {
        runCatching { errorTts?.shutdown() }
        errorTts = null
        errorTtsReady = false
        pendingErrorMessage = null
    }

    private fun speakNow(tts: TextToSpeech, message: String) {
        tts.language = Locale.getDefault()
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, null)
    }
}
