package com.jarvis.assistant.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import timber.log.Timber

/**
 * Android bridge of the [AssistantAudioFocus] state machine: a transient-
 * may-duck focus request per TTS generation. Listener losses are logged
 * only — if another app permanently steals focus mid-sentence we keep
 * speaking rather than cutting the confirmation short.
 */
class AndroidAudioFocusAdapter(context: Context) : AudioFocusAdapter {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            ->
                Timber.i("Assistant focus lost (%d) — continuing TTS", change)

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                Unit // expected: another ducker; our stream is short anyway
        }
    }

    private var request: AudioFocusRequest? = null

    override fun requestDuckFocus(): Boolean = runCatching {
        val focus = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        val result: Int = if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(focus)
                .setOnAudioFocusChangeListener(listener)
                .build()
            request = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, focus)
        }
        result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }.getOrDefault(false)

    override fun abandonFocus() {
        runCatching {
            val req = request
            if (req != null) {
                audioManager.abandonAudioFocusRequest(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(listener)
            }
        }
        request = null
    }
}
