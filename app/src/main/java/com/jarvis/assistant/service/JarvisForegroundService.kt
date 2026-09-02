package com.jarvis.assistant.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.model.AssistantState
import com.jarvis.assistant.di.AppGraph
import com.jarvis.assistant.di.GraphHolder
import com.jarvis.assistant.util.AppPrefs
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale

/**
 * Foreground service owning the entire voice pipeline.
 *
 * Fixes vs. the original:
 * - **RECORD_AUDIO is checked BEFORE init**; if missing, the service shows an
 *   actionable notification (tap → app requests permission) and retries via
 *   the watchdog instead of burning its one-shot init and running dead.
 * - **Init failure no longer poisons `initialized`** — retry happens on the
 *   next onStartCommand (watchdog / user action).
 * - **User-stop semantics**: an explicit stop cancels the restart alarm, so
 *   the assistant STAYS stopped (the old watchdog resurrected it within 15
 *   minutes). If the SYSTEM kills the process there is no onDestroy, the
 *   alarm survives, and the service revives — exactly the desired split.
 * - **Watchdog cancel is user-stop-only (m7)**: onDestroy cancels the alarm
 *   only when `userStopped` is set, so system-driven teardowns (Apply
 *   restart) can never strand the service dead.
 * - **Ducking always recovers (m8)**: teardown unducks unconditionally.
 * - **Mute is a user intent (m12)**: [setMuted] stops the pipeline AND
 *   cancels the active session; the power receiver never silently unmutes.
 * - **Media-key duck fallback now resumes** playback on unduck.
 */
class JarvisForegroundService : Service() {

    private val config = JarvisConfig()
    private lateinit var prefs: AppPrefs

    @Volatile private var initialized = false
    private var graph: AppGraph? = null
    private var powerReceiver: BroadcastReceiver? = null

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock

    private val errorTts by lazy { TextToSpeech(this) { } }

    private var wasMusicPlaying = false
    private var lastController: MediaController? = null
    private var usedMediaKeyFallback = false

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
        startForegroundCompat(buildStateNotification(getString(R.string.state_idle)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXPLICIT_START -> prefs.userStopped = false
            ACTION_WATCHDOG -> {
                // The 15-minute keep-alive ping. Respect an explicit stop.
                if (prefs.userStopped) {
                    Timber.i("Watchdog fired but user stopped the assistant — shutting down")
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }

        scheduleRestartAlarm()
        ensureInitialized()
        return START_STICKY
    }

    // ------------------------------------------------------------------
    // Initialization (idempotent, retryable)
    // ------------------------------------------------------------------

    private fun ensureInitialized() {
        if (initialized) return

        // Permission gate FIRST — the original crashed AudioRecord init on
        // fresh installs and never retried.
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("RECORD_AUDIO not granted; showing permission notification")
            showPermissionNotification()
            initialized = false // retry on next start command
            return
        }

        try {
            acquireLocks()
            registerPowerReceiver()

            val appGraph = AppGraph(
                this, config,
                com.jarvis.assistant.config.ProviderSettings.DEFAULT.copy(
                    type = prefs.providerType,
                    openAiBaseUrl = prefs.openAiBaseUrl,
                    openAiModel = prefs.openAiModel,
                    wakeSensitivity = prefs.wakeSensitivity,
                ),
                onSessionError = { msg -> speakError(msg) },
            ).also { it.start() }
            graph = appGraph
            GraphHolder.graph = appGraph
            initialized = true

            // Live state -> notification text + ducking.
            appGraph.scope.launch {
                appGraph.stateMachine.state.collect { state ->
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, buildStateNotification(stateLabel(state)))
                }
            }
            appGraph.scope.launch {
                var wasActive = false
                appGraph.stateMachine.state.collect { state ->
                    val isActive = state != AssistantState.IDLE
                    when {
                        isActive && !wasActive -> duck()
                        !isActive && wasActive -> unduck()
                    }
                    wasActive = isActive
                }
            }
            Timber.i("Jarvis pipeline initialized")
        } catch (e: Exception) {
            Timber.e(e, "AppGraph init failed — will retry on next watchdog tick")
            // Do NOT mark initialized: the 15-minute watchdog (or an
            // app revisit) retries automatically.
            graph?.shutdown()
            graph = null
            GraphHolder.graph = null
            speakError(getString(R.string.tts_init_failed))
        }
    }

    private fun acquireLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::WakeLock",
        ).apply { setReferenceCounted(false) }
        wakeLock.acquire()

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("MissingPermission")
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Jarvis::WifiLock",
        ).apply { setReferenceCounted(false) }
        wifiLock.acquire()
    }

    private fun registerPowerReceiver() {
        powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        graph?.audioPipeline?.stop()
                        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
                    }

                    Intent.ACTION_POWER_CONNECTED -> {
                        if (::wakeLock.isInitialized && !wakeLock.isHeld) wakeLock.acquire()
                        // m12: restart respects mute — the receiver must never
                        // silently undo a user's mute.
                        graph?.sessionManager?.onPowerConnected()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_POWER_CONNECTED)
        }
        registerReceiver(powerReceiver, filter)
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    private fun stateLabel(state: AssistantState): String = when (state) {
        AssistantState.IDLE -> getString(R.string.state_idle)
        AssistantState.LISTENING -> getString(R.string.state_listening)
        AssistantState.THINKING -> getString(R.string.state_thinking)
        AssistantState.SPEAKING -> getString(R.string.state_speaking)
        AssistantState.FOLLOW_UP_WINDOW -> getString(R.string.state_follow_up)
    }

    private fun buildStateNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun showPermissionNotification() {
        val intent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ERROR)
            .setContentTitle("Jarvis")
            .setContentText(getString(R.string.perm_notification_text))
            .setSmallIcon(R.drawable.ic_mic)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ERROR, getString(R.string.channel_errors),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        nm.createNotificationChannel(channel)
        nm.notify(NOTIFICATION_PERMISSION, notification)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ------------------------------------------------------------------
    // Ducking (pause/resume) with working media-key fallback
    // ------------------------------------------------------------------

    private fun duck() {
        if (wasMusicPlaying) return
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, JarvisNotificationListener::class.java)
        val controllers = try {
            msm.getActiveSessions(component)
        } catch (_: SecurityException) {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            wasMusicPlaying = true
            usedMediaKeyFallback = true
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
        if (usedMediaKeyFallback) {
            // FIX: the old fallback paused music via a media key but never
            // resumed it. Symmetric PLAY key now restores playback.
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        } else {
            runCatching { lastController?.transportControls?.play() }
        }
        wasMusicPlaying = false
        usedMediaKeyFallback = false
        lastController = null
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    // ------------------------------------------------------------------
    // Error voice
    // ------------------------------------------------------------------

    private fun speakError(message: String) {
        Timber.e("Voice error: %s", message)
        runCatching {
            errorTts.language = Locale.getDefault()
            errorTts.speak(message, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    // ------------------------------------------------------------------
    // Watchdog
    // ------------------------------------------------------------------

    private fun scheduleRestartAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, JarvisForegroundService::class.java)
            .setAction(ACTION_WATCHDOG)
        val pending = PendingIntent.getService(
            this, RESTART_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = System.currentTimeMillis() + config.restartIntervalMs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    private fun cancelRestartAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Must match scheduleRestartAlarm()'s Intent (filterEquals compares
        // the action) or alarmManager.cancel is a silent no-op.
        val intent = Intent(this, JarvisForegroundService::class.java)
            .setAction(ACTION_WATCHDOG)
        alarmManager.cancel(
            PendingIntent.getService(
                this, RESTART_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
    }

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    override fun onDestroy() {
        // m8: recover ducking no matter which state edge wedged — a teardown
        // must never leave paused media paused forever.
        runCatching { unduck() }
        // m7: only an EXPLICIT user stop may cancel the watchdog. Any other
        // teardown (system service-stop, Apply-restart handoff) leaves the
        // restart alarm armed so the assistant revives.
        if (prefs.userStopped) {
            cancelRestartAlarm()
        }
        runCatching { powerReceiver?.let { unregisterReceiver(it) } }
        graph?.shutdown()
        graph = null
        GraphHolder.graph = null
        initialized = false
        if (::wakeLock.isInitialized && wakeLock.isHeld) runCatching { wakeLock.release() }
        if (::wifiLock.isInitialized && wifiLock.isHeld) runCatching { wifiLock.release() }
        runCatching { errorTts.shutdown() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * m12: user-facing mic mute. Delegates to the session manager so muting
     * also CANCELS the active session and survives power-receiver restarts;
     * UI can call this via the binder in a later phase.
     */
    fun setMuted(muted: Boolean) {
        graph?.sessionManager?.setMuted(muted)
    }

    /**
     * Follow-up window live control (Settings «Продолжение диалога» card):
     * applies immediately — no service restart needed.
     */
    fun setFollowUpWindow(enabled: Boolean, windowMs: Long) {
        graph?.sessionManager?.setFollowUpWindow(enabled, windowMs)
    }

    /**
     * AEC Phase B: start the playback-capture far-end lane with a consented
     * MediaProjection result. Only acts in SOFTWARE AEC mode (the lane feeds
     * the built-in canceller; other modes have no consumer).
     */
    fun startPlaybackCapture(resultCode: Int, data: Intent) {
        val g = graph ?: return
        if (g.aecMode != com.jarvis.assistant.audio.aec.AecMode.SOFTWARE) {
            Timber.tag("AecDiag").w("playback capture requested outside SOFTWARE aec mode — ignored")
            return
        }
        g.playbackCapture.start(resultCode, data)
    }

    fun stopPlaybackCapture() {
        graph?.playbackCapture?.stop()
    }

    /** AEC probe row for the Settings card (static part; service may be down). */
    fun aecProbeLine(): String =
        graph?.let { com.jarvis.assistant.audio.aec.AecProbe.diagLine() } ?: "service not running"

    private val binder = object : android.os.Binder() {
        fun getService(): JarvisForegroundService = this@JarvisForegroundService
    }

    companion object {
        const val ACTION_EXPLICIT_START = "com.jarvis.assistant.EXPLICIT_START"
        const val ACTION_WATCHDOG = "com.jarvis.assistant.WATCHDOG"
        const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_PERMISSION = 2
        private const val RESTART_REQUEST_CODE = 1001
        private const val CHANNEL_ID = "jarvis_foreground"
        private const val CHANNEL_ERROR = "jarvis_errors"
        private const val ServiceInfo_MICROPHONE =
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

        fun explicitStart(context: Context) {
            val intent = Intent(context, JarvisForegroundService::class.java)
                .setAction(ACTION_EXPLICIT_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun explicitStop(context: Context) {
            AppPrefs(context).userStopped = true
            context.stopService(Intent(context, JarvisForegroundService::class.java))
        }
    }
}
