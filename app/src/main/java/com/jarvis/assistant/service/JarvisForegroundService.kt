package com.jarvis.assistant.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.IntentFilter
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
import com.jarvis.assistant.AppGraph
import com.jarvis.assistant.R
import com.jarvis.assistant.contracts.AssistantState
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale

/**
 * Foreground service that owns the entire voice pipeline via [AppGraph].
 *
 * Idempotent (R9): [onStartCommand] only initializes once; subsequent calls are
 * no-ops. A 15-minute [AlarmManager] restart keeps the sticky service alive
 * across Doze/stopped states.
 *
 * Observes the [SessionStateMachine] StateFlow for live notification updates
 * and media duck/unduck. Guards against power disconnect by pausing/resuming
 * the audio pipeline.
 */
class JarvisForegroundService : Service() {

    private var initialized = false
    private var graph: AppGraph? = null

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock

    private var powerReceiver: BroadcastReceiver? = null

    // Single shared TTS for error prompts (no per-error allocation -> no leak).
    private val errorTts by lazy {
        TextToSpeech(this) { }
    }

    // Ducking state.
    private var wasMusicPlaying = false
    private var lastController: MediaController? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "Jarvis", NotificationManager.IMPORTANCE_LOW)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)

        val initialNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis")
            .setContentText("Ожидание")
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, initialNotification)
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

        // Power disconnect guard
        powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        Timber.d("Power disconnected — pausing capture")
                        graph?.audioPipeline?.stop()
                        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
                    }
                    Intent.ACTION_POWER_CONNECTED -> {
                        Timber.d("Power reconnected — resuming capture")
                        if (::wakeLock.isInitialized && !wakeLock.isHeld) wakeLock.acquire()
                        graph?.audioPipeline?.start()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_POWER_CONNECTED)
        }
        registerReceiver(powerReceiver, filter)

        val appGraph = AppGraph(this)
        appGraph.start()
        graph = appGraph

        scheduleRestartAlarm()

        // Observe state for live notification updates
        appGraph.scope.launch {
            appGraph.stateMachine.state.collect { state ->
                val notification = NotificationCompat.Builder(this@JarvisForegroundService, CHANNEL_ID)
                    .setContentTitle("Jarvis")
                    .setContentText(when (state) {
                        AssistantState.IDLE -> "Ожидание"
                        AssistantState.LISTENING -> "Слушаю…"
                        AssistantState.THINKING -> "Думаю…"
                        AssistantState.SPEAKING -> "Говорю…"
                    })
                    .setSmallIcon(R.drawable.ic_mic)
                    .setOngoing(true)
                    .build()
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, notification)
            }
        }

        // Observe state for duck/unduck
        appGraph.scope.launch {
            var wasActive = false
            appGraph.stateMachine.state.collect { state ->
                val isActive = state == AssistantState.LISTENING ||
                        state == AssistantState.SPEAKING ||
                        state == AssistantState.THINKING
                when {
                    isActive && !wasActive -> duck()
                    !isActive && wasActive -> unduck()
                }
                wasActive = isActive
            }
        }
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
    // Teardown
    // ------------------------------------------------------------------

    override fun onDestroy() {
        runCatching { powerReceiver?.let { unregisterReceiver(it) } }
        runCatching { graph?.shutdown() }
        graph = null
        if (::wakeLock.isInitialized && wakeLock.isHeld) runCatching { wakeLock.release() }
        if (::wifiLock.isInitialized && wifiLock.isHeld) runCatching { wifiLock.release() }
        runCatching { errorTts.shutdown() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val NOTIFICATION_ID = 1
        const val RESTART_REQUEST_CODE = 1001
        const val CHANNEL_ID = "jarvis_foreground"
        const val RESTART_INTERVAL_MS = 15 * 60 * 1000L
    }
}