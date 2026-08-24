package com.jarvis.assistant.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.audio.AudioPipeline
import com.jarvis.assistant.audio.AudioRecordSource
import com.jarvis.assistant.audio.PorcupineDetector
import com.jarvis.assistant.audio.StreamingAudioTrackPlayer
import com.jarvis.assistant.audio.VadAnalyzer
import com.jarvis.assistant.api.FunctionRouter
import com.jarvis.assistant.api.GigaChatClient
import com.jarvis.assistant.api.SaluteSpeechASR
import com.jarvis.assistant.api.SaluteSpeechTTS
import com.jarvis.assistant.api.TokenManager
import com.jarvis.assistant.contracts.AssistantState
import com.jarvis.assistant.contracts.AudioSpec
import com.jarvis.assistant.contracts.TtsPlayer
import com.jarvis.assistant.data.AppDatabase
import com.jarvis.assistant.data.ConversationManager
import com.jarvis.assistant.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.Locale

/**
 * Foreground service that owns the entire voice pipeline and wires it to a
 * [SessionManager].
 *
 * Idempotent (R9): [onStartCommand] only initializes once; subsequent calls are
 * no-ops. A 15-minute [AlarmManager] restart keeps the sticky service alive
 * across Doze/stopped states.
 */
class JarvisForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var initialized = false

    private lateinit var audioPipeline: AudioPipeline
    private lateinit var porcupine: PorcupineDetector
    private lateinit var vad: VadAnalyzer
    private lateinit var tokenManager: TokenManager
    private lateinit var asr: SaluteSpeechASR
    private lateinit var ttsClient: SaluteSpeechTTS
    private lateinit var llm: GigaChatClient
    private lateinit var player: TtsPlayer
    private lateinit var functionRouter: FunctionRouter
    private lateinit var conversationManager: ConversationManager
    private lateinit var sessionManager: SessionManager

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock

    // Single shared TTS for error prompts (no per-error allocation -> no leak).
    private val errorTts by lazy {
        TextToSpeech(this) { }
    }

    // Ducking state.
    private var wasMusicPlaying = false
    private var lastController: MediaController? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scheduleRestartAlarm()   // re-arm on EVERY start command (Issue 8)
        ensureInitialized()
        return START_STICKY
    }

    /** Idempotent initialization (R9). */
    private fun ensureInitialized() {
        if (initialized) return
        initialized = true

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::WakeLock"
        ).apply { setReferenceCounted(false) }
        wakeLock.acquire()

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("MissingPermission")
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Jarvis::WifiLock"
        ).apply { setReferenceCounted(false) }
        wifiLock.acquire()

        audioPipeline = AudioPipeline(serviceScope, AudioRecordSource())
        porcupine = PorcupineDetector(audioPipeline.frames, applicationContext, BuildConfig.PICOVOICE_KEY)
        vad = VadAnalyzer(applicationContext)
        tokenManager = TokenManager(applicationContext)
        asr = SaluteSpeechASR(tokenManager)
        ttsClient = SaluteSpeechTTS(tokenManager)
        llm = GigaChatClient(tokenManager)
        player = StreamingAudioTrackPlayer(serviceScope, AudioSpec.TTS)
        functionRouter = FunctionRouter(applicationContext) {
            conversationManager.getHistoryForLLM()
        }
        conversationManager = ConversationManager(
            AppDatabase.getInstance(applicationContext).messageDao()
        )

        sessionManager = SessionManager(
            audioPipeline = audioPipeline,
            porcupine = porcupine,
            vad = vad,
            asr = asr,
            llm = llm,
            ttsClient = ttsClient,
            player = player,
            functionRouter = functionRouter,
            conversationManager = conversationManager,
            scope = serviceScope,
            onStateChange = { /* hook for notification updates if desired */ },
            onError = { e -> speakError(e) },
            duck = { duck() },
            unduck = { unduck() }
        )

        // Start the mic producer (Porcupine actor + SessionManager detection
        // collector are launched in their own constructors/init).
        audioPipeline.start()
        sessionManager.startSession()
    }

    // ------------------------------------------------------------------
    // Ducking
    // ------------------------------------------------------------------

    private fun duck() {
        if (wasMusicPlaying) return // already ducked
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, JarvisNotificationListener::class.java)
        val controllers = try {
            msm.getActiveSessions(component)
        } catch (_: SecurityException) {
            // Listener not granted -> fall back to a media key event.
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            wasMusicPlaying = true
            return
        }
        for (c in controllers) {
            val st = c.playbackState
            if (st != null && st.state == PlaybackState.STATE_PLAYING) {
                wasMusicPlaying = true
                lastController = c
                runCatching { c.transportControls.pause() }
                break
            }
        }
    }

    private fun unduck() {
        if (!wasMusicPlaying) return
        runCatching { lastController?.transportControls?.play() }
        wasMusicPlaying = false
        lastController = null
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    // ------------------------------------------------------------------
    // Error prompt (single shared TTS)
    // ------------------------------------------------------------------

    private fun speakError(e: Exception) {
        runCatching {
            errorTts.language = Locale("ru")
            errorTts.speak(
                "Произошла ошибка",
                TextToSpeech.QUEUE_FLUSH,
                null,
                null
            )
        }
    }

    // ------------------------------------------------------------------
    // Restart alarm
    // ------------------------------------------------------------------

    private fun scheduleRestartAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, JarvisForegroundService::class.java)
        val pending = PendingIntent.getService(
            this,
            RESTART_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + RESTART_INTERVAL_MS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAt, pending
            )
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private fun createNotification(): Notification {
        val channelId = "jarvis_foreground"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                channelId,
                "Jarvis",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val contentIntent = PendingIntent.getService(
            this,
            CONTENT_REQUEST_CODE,
            Intent(this, JarvisForegroundService::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Jarvis")
            .setContentText("Голосовой помощник активен")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    override fun onDestroy() {
        runCatching { sessionManager.cancelAll() }
        runCatching { audioPipeline.release() }
        runCatching { porcupine.release() }
        runCatching { player.release() }
        if (::wakeLock.isInitialized && wakeLock.isHeld) runCatching { wakeLock.release() }
        if (::wifiLock.isInitialized && wifiLock.isHeld) runCatching { wifiLock.release() }
        runCatching { errorTts.shutdown() }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val NOTIFICATION_ID = 1
        const val RESTART_REQUEST_CODE = 1001
        const val CONTENT_REQUEST_CODE = 1002
        const val RESTART_INTERVAL_MS = 15 * 60 * 1000L
    }
}
