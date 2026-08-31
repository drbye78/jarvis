package com.jarvis.assistant.audio

import android.content.Context
import com.jarvis.assistant.R
import com.jarvis.assistant.speech.tts.TtsClient
import com.jarvis.assistant.speech.tts.TtsPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * M5 fix: spoken progress during the music cascade. Up to ~20 s of the
 * cold-start cascade used to pass in complete silence; now the user hears
 * «Секунду…» when a long path is predicted and «Открываю плеер…» right
 * before we launch the app's UI.
 *
 * Contract (the orchestrator depends on exactly this):
 *  - methods are NON-SUSPENDING fire-and-forget: the cascade never waits;
 *  - best-effort: every failure is logged and swallowed;
 *  - phrases play through the SAME serialized player as turn sentences, so
 *    a barge-in flush (generation bump) kills them along with everything
 *    else — no stale «Секунду…» after the answer started.
 */
interface SpeechFeedback {
    /** Called when the play cascade starts; [predictedLong] = cold start ahead. */
    fun onCascadeStarted(predictedLong: Boolean)

    /** Called right before an activity-launching strategy (S3/S5/S6 territory). */
    fun onLaunchingPlayer(label: String)

    /** Test/production-default no-op. */
    object None : SpeechFeedback {
        override fun onCascadeStarted(predictedLong: Boolean) = Unit
        override fun onLaunchingPlayer(label: String) = Unit
    }
}

/**
 * TTS-backed implementation. Synthesis + playback run on the supplied scope
 * so a slow gRPC call cannot stall the tool call that triggered it; the
 * focus gate brackets the phrase exactly like a turn sentence.
 */
class TtsSpeechFeedback(
    private val scope: CoroutineScope,
    private val tts: TtsClient,
    private val player: TtsPlayer,
    private val voice: String,
    private val focus: AssistantAudioFocus? = null,
    private val context: Context? = null,
) : SpeechFeedback {

    override fun onCascadeStarted(predictedLong: Boolean) {
        if (!predictedLong) return // live session: the fast path stays silent
        speak(context?.getString(R.string.tts_please_wait) ?: "Секунду.")
    }

    override fun onLaunchingPlayer(label: String) {
        speak(context?.getString(R.string.tts_opening_player, label) ?: "Открываю $label.")
    }

    private fun speak(text: String) {
        scope.launch {
            try {
                val flow = tts.synthesizeStream(text, voice)
                focus?.onTtsSentenceStarted()
                val done = player.play(flow)
                try {
                    done.await()
                } finally {
                    focus?.onTtsSentenceFinished()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Barge-in flush killed the phrase mid-flight — fine.
                focus?.onTtsFlushed()
            } catch (t: Throwable) {
                Timber.w(t, "SpeechFeedback phrase failed (best-effort, ignored)")
                focus?.onTtsFlushed()
            }
        }
    }
}
