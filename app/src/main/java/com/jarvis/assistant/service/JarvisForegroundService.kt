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
import com.jarvis.assistant.di.AppGraph
import com.jarvis.assistant.di.GraphHolder
import com.jarvis.assistant.model.AssistantState
import com.jarvis.assistant.util.AppPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
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
 * - **Android 10 microphone policy (minSdk 29)**: mic access is granted only
 *   to foreground services started while the user is present (Android 10
 *   while-in-use rule; Android 12+ additionally restricts background FGS
 *   starts). Background-originated starts (watchdog / maintenance alarms,
 *   START_STICKY recreation, boot) no longer become foreground services —
 *   they post the "tap to activate" notification ([postActivationPrompt]);
 *   the tap opens the activity and starts the pipeline with working mic.
 * - **Decision logic externalized (P1.4)**: the watchdog revive gate, action
 *   routing, mute gating, init gate, FGS-type / activation-prompt decisions
 *   and maintenance timing live in [ServicePolicy] (pure JVM, unit-tested
 *   without Android); this class only reads state and executes decisions —
 *   all Android I/O stays here.
 */
class JarvisForegroundService : Service() {

    private val config = JarvisConfig()
    private lateinit var prefs: AppPrefs

    @Volatile private var initialized = false

    @Volatile private var bootstrapping = false

    @Volatile private var graph: AppGraph? = null
    private var initJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var powerReceiver: BroadcastReceiver? = null

    /**
     * Completes when the [AppGraph] is fully constructed and started.
     * Other components (UI, power receiver) can [await][CompletableDeferred.await]
     * this before accessing the graph to avoid null-pointer races during the
     * bootstrapping phase.
     */
    val graphReady = CompletableDeferred<AppGraph>()

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock

    /**
     * Error voice. Nullable + created on first SPEAK (F10): the old
     * `by lazy` field was CONSTRUCTED by onDestroy's shutdown() when no
     * error was ever spoken — spinning up a whole TTS engine just to tear
     * it down on the main thread during service destruction.
     */
    @Volatile private var errorTts: TextToSpeech? = null

    private var wasMusicPlaying = false

    /**
     * Holds the MediaController that was paused during ducking so it can be
     * resumed in [unduck].  MediaController has no lifecycle callback for
     * remote session death; if the remote app is killed, [lastController] may
     * become stale.  The surrounding [runCatching] in [unduck] handles this
     * gracefully (dead controller → swallowed exception → fallback to idle).
     */
    private var lastController: MediaController? = null
    private var usedMediaKeyFallback = false

    // Android 10 mic policy: true once this instance passed through the
    // EXPLICIT_START path (startForeground with a user-present guarantee).
    // Instances created by background starts (alarm-delivered service
    // intents, START_STICKY recreation) never set it and are torn down
    // instead of becoming microphone-silenced foreground services.
    @Volatile private var everForegrounded = false

    // ------------------------------------------------------------------
    // P3.3: watchdog revive budget — Android-side bookkeeping ONLY.
    // The decisions (cap/backoff/day-roll) live in [ServicePolicy]; these
    // plain fields record what happened so the policy can evaluate it.
    // All watchdog/maintenance intents arrive on the service's main-thread
    // looper, so no locking is needed. The counter counts every ATTEMPT
    // (success or `start()` failure — a failing attempt was still an
    // audio-restart event the backoff must gate). lastReviveAtMs is
    // @Volatile because graph/UI code could read it via a future probe.
    // ------------------------------------------------------------------
    private var reviveCountToday = 0
    private var reviveDayKey = Int.MIN_VALUE

    @Volatile private var lastReviveAtMs: Long? = null

    // Last notification posted on the main channel — reused when the FGS
    // type is promoted for media projection so the text does not regress.
    @Volatile private var lastStateNotification: Notification? = null

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        // Audit #12: `as` on getSystemService throws on non-standard OEM ROMs
        // where the service lookup can be null — degrade honestly instead.
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm != null) {
            // Main channel: ongoing foreground notification (low importance = no sound).
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
            // Bootstrapping channel: distinct so the user can tell the assistant is
            // still starting (visible in Settings → Notifications if needed).
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_BOOTSTRAP,
                    getString(R.string.channel_bootstrap_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        } else {
            Timber.e("NotificationManager unavailable — notification channels not created")
        }
        // NOTE: no startForeground() here. Whether this instance may become a
        // foreground service is decided in onStartCommand: only the
        // EXPLICIT_START flavor (user-present) promotes; background-created
        // instances post the activation prompt and tear down (the Android 10
        // while-in-use rule would silence their microphone).
        GraphHolder.service = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_EXPLICIT_START) {
            // The ONLY start flavor that carries a user-present guarantee:
            // every caller (MainActivity toggle, onboarding finish, the
            // activation notification tap) invokes it while an activity is
            // visible. On Android 10+ microphone access is granted
            // exclusively to foreground services started while the app is in
            // use, so this is the sole path allowed to promote to an FGS and
            // build the capture pipeline.
            prefs.userStopped = false
            if (!everForegrounded) {
                everForegrounded = true
                startForegroundCompat(buildStateNotification(getString(R.string.state_idle)))
            }
        } else if (!everForegrounded) {
            // Fresh instance created by a BACKGROUND start: the watchdog or
            // maintenance alarm (PendingIntent.getService) or a START_STICKY
            // recreation (null intent). On minSdk 29 (Android 10) an FGS
            // started here would run with a SILENCED microphone (while-in-use
            // rule) — the assistant would look alive but never hear the wake
            // word — and on Android 12+ the start itself is restricted. Never
            // become a deaf FGS: keep the maintenance chain booked, ask the
            // user to tap-activate, and tear this instance down. The decision
            // table is [ServicePolicy.backgroundStartRoute] (P1.4).
            val route = ServicePolicy.backgroundStartRoute(
                action = ServicePolicy.actionFor(action),
                userStopped = prefs.userStopped,
            )
            if (route.rescheduleMaintenance) {
                scheduleCognitiveMaintenanceAlarm()
            }
            if (route.postActivationPrompt) {
                JarvisForegroundService.postActivationPrompt(this)
            } else {
                Timber.i("Background start ignored — user stopped the assistant")
            }
            stopSelf()
            return START_NOT_STICKY
        } else {
            if (handleRunningInstanceCommand(action)) {
                return START_NOT_STICKY
            }
        }

        scheduleRestartAlarm()
        // COGNITIVE_PLAN 2.2: nightly cognitive maintenance — schedule the
        // ~03:30 inexact alarm (idempotent; each firing reschedules), and
        // opportunistically run maintenance now if the last one is > 20 h
        // old (EMUI defers inexact alarms; the wall device is nearly always
        // on, so the opportunistic path keeps the latency tolerable without
        // a WorkManager dependency).
        scheduleCognitiveMaintenanceAlarm()
        maybeRunMaintenanceOpportunistically()
        ensureInitialized()
        return START_STICKY
    }

    /**
     * Alarm-delivered commands that target an ALREADY user-activated
     * instance ([everForegrounded] == true). Fresh background-created
     * instances never reach this — they take the activation-prompt branch
     * in [onStartCommand].
     *
     * The decision table lives in [ServicePolicy.runningInstanceCommand];
     * this method only executes it — all Android I/O stays here.
     *
     * @return true when the caller must return immediately (the command
     *   stopped the service); false to fall through to the common tail
     *   (restart alarm, maintenance scheduling, [ensureInitialized]).
     */
    private fun handleRunningInstanceCommand(action: String?): Boolean {
        val g = graph
        // (revive logic below runs only for user-activated instances;
        // a watchdog that had to CREATE this instance took the
        // background-start branch in onStartCommand instead)
        // P3.3: day-key roll resets the revive counter — the budget is per
        // local day. Every alarm-delivered command lands here on the main
        // looper, so the roll cannot interleave with an attempt.
        val now = System.currentTimeMillis()
        val dayKey = ServicePolicy.dayKey(now)
        if (dayKey != reviveDayKey) {
            reviveDayKey = dayKey
            reviveCountToday = 0
        }
        val decision = ServicePolicy.runningInstanceCommand(
            action = ServicePolicy.actionFor(action),
            inputs = ServicePolicy.RunningInstanceInputs(
                graphReady = g != null && initialized,
                userStopped = prefs.userStopped,
                pipelineGivenUp = g?.audioPipeline?.hasGivenUp() ?: false,
                muted = g?.sessionManager?.muted?.value ?: false,
                reviveCountToday = reviveCountToday,
                dailyReviveCap = ServicePolicy.DEFAULT_DAILY_REVIVE_CAP,
                lastReviveMs = lastReviveAtMs,
                nowMs = now,
            ),
        )
        return when (decision) {
            is ServicePolicy.RunningCommandDecision.StopForUserStop -> {
                // The 15-minute keep-alive ping. Respect an explicit stop.
                Timber.i("Watchdog fired but user stopped the assistant — shutting down")
                stopSelf()
                true
            }
            is ServicePolicy.RunningCommandDecision.RunTail -> {
                if (decision.rescheduleMaintenance) {
                    // COGNITIVE_PLAN 2.2: the nightly ~03:30 tick. Reschedule
                    // first so the next night is always booked even if the run
                    // itself throws; the coordinator guards every step.
                    scheduleCognitiveMaintenanceAlarm()
                }
                when (decision.runMaintenanceNow) {
                    true -> {
                        // runMaintenanceNow == true implies a ready graph.
                        val readyGraph = requireNotNull(g) { "maintenance run-now implies a ready graph" }
                        serviceScope.launch {
                            try {
                                readyGraph.cognitiveCoordinator.onMaintenance()
                                Timber.i("Cognitive: nightly maintenance complete")
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.w(e, "Cognitive: nightly maintenance failed")
                            }
                        }
                    }
                    false -> Timber.i(
                        "Cognitive: maintenance tick arrived before init — opportunistic path will run it"
                    )
                    null -> Unit
                }
                if (decision.revivePipeline) {
                    // Audit #25: self-heal a capture pipeline that gave up after
                    // 50 consecutive read failures. hasGivenUp() is true ONLY for
                    // that case — never for a user stop/mute or the power-receiver
                    // stop — so the ping cannot silently undo a user intent.
                    // revivePipeline == true implies a ready graph AND an
                    // authorized budget ServicePolicy.REVIVE_PIPELINE.
                    // The backend engine leak on a genuine native wedge stays
                    // BY DESIGN (HybridWakeWordDetector deliberately leaks the
                    // engine instead of use-after-free); this guard only bounds
                    // how often we attempt audio restarts, never the leak.
                    val readyGraph = requireNotNull(g) { "pipeline revive implies a ready graph" }
                    Timber.w(
                        "Watchdog: audio pipeline gave up — reviving capture (attempt %d/%d today)",
                        reviveCountToday + 1,
                        ServicePolicy.DEFAULT_DAILY_REVIVE_CAP,
                    )
                    runCatching { readyGraph.audioPipeline.start() }
                        .onFailure { Timber.w(it, "Pipeline revive failed — retrying next tick") }
                    // Count every ATTEMPT (success or failure) — an attempt that
                    // just restarted capture hardware must be gated by the
                    // backoff regardless of its outcome (honest bookkeeping).
                    reviveCountToday++
                    lastReviveAtMs = System.currentTimeMillis()
                    Timber.tag("ReviveDiag")
                        .i(
                            "pipeline revive #%d/%d today (bookkeeping: attempts=%d, lastAt=%s)",
                            reviveCountToday,
                            ServicePolicy.DEFAULT_DAILY_REVIVE_CAP,
                            reviveCountToday,
                            lastReviveAtMs
                        )
                } else if (decision.reviveSuppressed) {
                    // P3.3: budget refused the revive. Log honestly, do NOT
                    // revive; the watchdog keeps ticking (common tail below
                    // re-arms the alarm), so a later manual restart via
                    // EXPLICIT_START or the next-day counter reset recovers.
                    Timber.tag("ReviveDiag").w(
                        "Watchdog: pipeline revive SKIPPED — budget spent (%d/%d today, %s ms ago); " +
                            "watchdog keeps ticking, day roll or a manual restart recovers",
                        reviveCountToday,
                        ServicePolicy.DEFAULT_DAILY_REVIVE_CAP,
                        lastReviveAtMs?.let { now - it } ?: "n/a",
                    )
                }
                false
            }
        }
    }

    // ------------------------------------------------------------------
    // COGNITIVE_PLAN 2.2: cognitive maintenance scheduling (§9.1)
    // ------------------------------------------------------------------

    /**
     * Inexact ~03:30 alarm (setAndAllowWhileIdle per plan §9.1). Delivered
     * to THIS service as a start intent — the same PendingIntent.getService
     * pattern as the watchdog, which already holds a foreground-service
     * scheduling exemption while the assistant runs.
     */
    private fun scheduleCognitiveMaintenanceAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager == null) {
            Timber.e("AlarmManager unavailable — cognitive maintenance NOT scheduled")
            return
        }
        val intent = Intent(this, JarvisForegroundService::class.java)
            .setAction(ACTION_RUN_COGNITIVE_MAINTENANCE)
        val pending = android.app.PendingIntent.getService(
            this,
            MAINTENANCE_REQUEST_CODE,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = ServicePolicy.nextMaintenanceAt(System.currentTimeMillis())
        // Inexact BY DESIGN (plan §9.1): maintenance is patient background
        // work; doze batching is acceptable, the opportunistic path covers
        // the deferral.
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        Timber.d("Cognitive maintenance scheduled for %d", triggerAt)
    }

    private fun maybeRunMaintenanceOpportunistically() {
        val g = graph
        if (g == null || !initialized) {
            // Graph still building — the next watchdog tick or maintenance
            // alarm will run the due maintenance; nothing to do here.
            return
        }
        runCatching { runMaintenanceIfDue() }
            .onFailure { Timber.w(it, "Opportunistic maintenance check failed") }
    }

    /** Runs [AppGraph]'s cognitive maintenance when it is older than 20 h. */
    private fun runMaintenanceIfDue() {
        val g = graph ?: return
        serviceScope.launch {
            try {
                val meta = g.database.memoryMetaDao()
                    .get(com.jarvis.assistant.cognitive.data.MemoryMetaEntity.KEY_LAST_MAINTENANCE_AT)
                    ?.toLongOrNull()
                val now = System.currentTimeMillis()
                // P1.4: null (never recorded) counts as epoch 0 — stale — in
                // [ServicePolicy.isMaintenanceStale], like the original `?: 0L`.
                if (ServicePolicy.isMaintenanceStale(meta, now)) {
                    Timber.i("Cognitive: opportunistic maintenance (last %d ms ago)", now - (meta ?: 0L))
                    g.cognitiveCoordinator.onMaintenance()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Cognitive: opportunistic maintenance failed")
            }
        }
    }

    // P1.4: nextMaintenanceAt moved to [ServicePolicy.nextMaintenanceAt].

    // ------------------------------------------------------------------
    // Initialization (idempotent, retryable)
    // ------------------------------------------------------------------

    private fun ensureInitialized() {
        // P1.4: the RECORD_AUDIO-first gate is [ServicePolicy.initializationStep].
        // Permission gate FIRST — the original crashed AudioRecord init on
        // fresh installs and never retried. The permission lookup is a pure
        // read the original performed only after the initialized/bootstrapping
        // guard — evaluated unconditionally here (no observable difference);
        // the SKIP step preserves the early return.
        when (
            ServicePolicy.initializationStep(
                initialized = initialized,
                bootstrapping = bootstrapping,
                micPermissionGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED,
            )
        ) {
            ServicePolicy.InitStep.SKIP -> return
            ServicePolicy.InitStep.REQUEST_MIC_PERMISSION -> {
                Timber.w("RECORD_AUDIO not granted; showing permission notification")
                showPermissionNotification()
                initialized = false // retry on next start command
                return
            }
            ServicePolicy.InitStep.BEGIN_BOOTSTRAP -> Unit
        }

        // Show bootstrapping notification immediately (main thread, fast).
        bootstrapping = true
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, buildBootstrapNotification())

        // Build AppGraph on a background thread to avoid ANR on low-end
        // devices (Kirin 710A class). The foreground service must remain
        // responsive while the heavy object graph is constructed.
        initJob = serviceScope.launch(Dispatchers.Default) {
            var built: AppGraph? = null
            try {
                acquireLocks()
                registerPowerReceiver()

                built = AppGraph(
                    this@JarvisForegroundService, config,
                    com.jarvis.assistant.config.ProviderSettings.DEFAULT.copy(
                        type = prefs.providerType,
                        openAiBaseUrl = prefs.openAiBaseUrl,
                        openAiModel = prefs.openAiModel,
                    ),
                    onSessionError = { msg -> speakError(msg) },
                ).also { it.start() }

                // F1 (zombie-graph race): graph construction is long and
                // NON-SUSPENDING, so Job.cancel() from onDestroy cannot
                // interrupt it. If the user stopped the service while we
                // were building, onDestroy saw graph == null and will never
                // release this instance — release it HERE, then bail.
                if (!isActive) {
                    Timber.w("Service destroyed during graph init — releasing the built graph")
                    runCatching { built.shutdown() }
                    return@launch
                }

                graph = built
                GraphHolder.graph = built
                initialized = true
                bootstrapping = false

                // Signal graph readiness before wiring collectors so that any
                // code awaiting graphReady sees the graph immediately.
                graphReady.complete(built)

                // Switch back to the main notification channel now that the
                // pipeline is live.
                postStateNotification(getString(R.string.state_idle))

                // Live state -> notification text + ducking.
                built.scope.launch {
                    built.stateMachine.state.collect { state ->
                        postStateNotification(stateLabel(state))
                    }
                }
                built.scope.launch {
                    var wasActive = false
                    built.stateMachine.state.collect { state ->
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
                bootstrapping = false
                // C1: the ctor/start may have thrown AFTER acquireLocks() and
                // registerPowerReceiver() ran — graph is still null here, so
                // the old cleanup (graph?.shutdown()) released NOTHING while
                // onDestroy can only release the LATEST lock/receiver
                // instances. Release them explicitly so each 15-minute retry
                // does not stack another held wake lock + system receiver
                // (battery drain on an always-on appliance).
                releaseLocks()
                unregisterPowerReceiver()
                built?.let { runCatching { it.shutdown() } }
                graph = null
                GraphHolder.graph = null
                // Complete exceptionally so any awaiter gets the failure.
                graphReady.completeExceptionally(e)
                // Return notification to idle so the user sees a recoverable state.
                nm?.notify(NOTIFICATION_ID, buildStateNotification(getString(R.string.state_idle)))
                speakError(getString(R.string.tts_init_failed))
            }
        }
    }

    private fun acquireLocks() {
        // F3: idempotent — a watchdog retry must not stack a SECOND held
        // wake lock on top of a leaked one; release any held instance first.
        releaseLocks()
        // Audit #12: null-safe service lookups — a missing manager skips that
        // lock with a log line instead of crashing init (the lateinit guards
        // in onDestroy/release handle the never-assigned case).
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::WakeLock",
            ).apply { setReferenceCounted(false) }
            wakeLock.acquire()
        } else {
            Timber.e("PowerManager unavailable — running without the CPU wake lock")
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager != null) {
            @Suppress("MissingPermission")
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Jarvis::WifiLock",
            ).apply { setReferenceCounted(false) }
            wifiLock.acquire()
        } else {
            Timber.e("WifiManager unavailable — running without the wifi lock")
        }
    }

    private fun releaseLocks() {
        if (::wakeLock.isInitialized && wakeLock.isHeld) runCatching { wakeLock.release() }
        if (::wifiLock.isInitialized && wifiLock.isHeld) runCatching { wifiLock.release() }
    }

    private fun unregisterPowerReceiver() {
        powerReceiver?.let { runCatching { unregisterReceiver(it) } }
        powerReceiver = null
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
            this,
            0,
            Intent(this, MainActivity::class.java),
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

    /**
     * Bootstrapping notification uses a distinct channel so the user can
     * visually distinguish "still starting" from the normal idle state.
     */
    private fun buildBootstrapNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_BOOTSTRAP)
            .setContentTitle("Jarvis")
            .setContentText(getString(R.string.state_bootstrapping))
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun showPermissionNotification() {
        val intent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ERROR)
            .setContentTitle("Jarvis")
            .setContentText(getString(R.string.perm_notification_text))
            .setSmallIcon(R.drawable.ic_mic)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: run {
                Timber.e("NotificationManager unavailable — permission notification not posted")
                return
            }
        val channel = NotificationChannel(
            CHANNEL_ERROR,
            getString(R.string.channel_errors),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        nm.createNotificationChannel(channel)
        nm.notify(NOTIFICATION_PERMISSION, notification)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (ServicePolicy.requiresTypedForegroundStart(Build.VERSION.SDK_INT)) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Posts the main-channel state notification and remembers it so the FGS
     *  type promotion ([promoteForMediaProjection]) can re-use the current
     *  text instead of regressing to a stale label. */
    private fun postStateNotification(text: String) {
        val notification = buildStateNotification(text)
        lastStateNotification = notification
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.notify(NOTIFICATION_ID, notification)
    }

    // ------------------------------------------------------------------
    // Ducking (pause/resume) with working media-key fallback
    // ------------------------------------------------------------------

    private fun duck() {
        if (wasMusicPlaying) return
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        if (msm == null) {
            Timber.e("MediaSessionManager unavailable — falling back to the media key")
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            wasMusicPlaying = true
            usedMediaKeyFallback = true
            return
        }
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
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: run {
                Timber.e("AudioManager unavailable — media key %d not dispatched", keyCode)
                return
            }
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    // ------------------------------------------------------------------
    // Error voice
    // ------------------------------------------------------------------

    private fun speakError(message: String) {
        Timber.e("Voice error: %s", message)
        runCatching {
            val tts = errorTts ?: TextToSpeech(this) { }.also { errorTts = it }
            tts.language = Locale.getDefault()
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    // ------------------------------------------------------------------
    // Watchdog
    // ------------------------------------------------------------------

    private fun scheduleRestartAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: run {
                Timber.e("AlarmManager unavailable — watchdog NOT scheduled")
                return
            }
        val intent = Intent(this, JarvisForegroundService::class.java)
            .setAction(ACTION_WATCHDOG)
        val pending = PendingIntent.getService(
            this,
            RESTART_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = ServicePolicy.watchdogTriggerAt(System.currentTimeMillis(), config.restartIntervalMs)
        if (ServicePolicy.useExactAllowWhileIdle(Build.VERSION.SDK_INT)) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    private fun cancelRestartAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: run {
                Timber.e("AlarmManager unavailable — watchdog cancel skipped")
                return
            }
        // Must match scheduleRestartAlarm()'s Intent (filterEquals compares
        // the action) or alarmManager.cancel is a silent no-op.
        val intent = Intent(this, JarvisForegroundService::class.java)
            .setAction(ACTION_WATCHDOG)
        alarmManager.cancel(
            PendingIntent.getService(
                this,
                RESTART_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
    }

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    override fun onDestroy() {
        // Cancel any in-flight background initialization.
        initJob?.cancel()
        serviceScope.cancel()
        GraphHolder.service = null
        // If bootstrapping was in progress, complete exceptionally so any
        // code awaiting graphReady doesn't hang forever.
        if (bootstrapping && !graphReady.isCompleted) {
            graphReady.completeExceptionally(IllegalStateException("Service destroyed during bootstrap"))
        }
        // m8: recover ducking no matter which state edge wedged — a teardown
        // must never leave paused media paused forever.
        runCatching { unduck() }
        // m7: only an EXPLICIT user stop may cancel the watchdog. Any other
        // teardown (system service-stop, Apply-restart handoff) leaves the
        // restart alarm armed so the assistant revives. (P1.4: decision is
        // [ServicePolicy.cancelWatchdogOnDestroy].)
        if (ServicePolicy.cancelWatchdogOnDestroy(userStopped = prefs.userStopped)) {
            cancelRestartAlarm()
        }
        runCatching { powerReceiver?.let { unregisterReceiver(it) } }
        powerReceiver = null
        // B1 (main-thread ANR): AppGraph.shutdown() contains BLOCKING
        // teardown — HybridWakeWordDetector.release() runs runBlocking with
        // bounded waits up to ~2.5 s and the gRPC channel drains for up to
        // 2 s more. Service.onDestroy runs on the MAIN thread (input-dispatch
        // ANR threshold is 5 s), and the detector's own contract demands a
        // background caller. Run the teardown on a dedicated thread; the
        // fields are nulled synchronously so a restart builds a fresh graph
        // while the old one drains.
        val graphToShutdown = graph
        graph = null
        GraphHolder.graph = null
        if (graphToShutdown != null) {
            Thread {
                runCatching { graphToShutdown.shutdown() }
                    .onFailure { Timber.w(it, "Graph shutdown failed") }
            }.apply {
                name = "jarvis-graph-shutdown"
                isDaemon = true
            }.start()
        }
        initialized = false
        bootstrapping = false
        releaseLocks()
        runCatching { errorTts?.shutdown() }
        errorTts = null
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
        // Android 10+ requires the capturing app to run a foreground service
        // with the mediaProjection type; without it the capture AudioRecord
        // build throws SecurityException (on API 34+ even getMediaProjection
        // does). The manifest declares microphone|mediaProjection and API
        // 29–33 instances already carry both types via the manifest-inherited
        // two-arg startForeground; API 34+ needs the explicit promotion.
        promoteForMediaProjection()
        g.playbackCapture.start(resultCode, data)
    }

    /**
     * Adds the mediaProjection FGS type at runtime where the platform
     * requires it to be passed to startForeground explicitly. On API 29–33
     * the two-arg startForeground inherits ALL manifest-declared types, so
     * the manifest `microphone|mediaProjection` declaration suffices; only
     * API 34+ needs this call. No-op before the instance became a foreground
     * service (nothing to promote).
     */
    private fun promoteForMediaProjection() {
        if (!ServicePolicy.mayPromoteForMediaProjection(everForegrounded, Build.VERSION.SDK_INT)) return
        startForeground(
            NOTIFICATION_ID,
            lastStateNotification ?: buildStateNotification(getString(R.string.state_idle)),
            ServiceInfo_MICROPHONE or ServiceInfo_MEDIA_PROJECTION,
        )
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
        const val ACTION_RUN_COGNITIVE_MAINTENANCE = "com.jarvis.assistant.RUN_COGNITIVE_MAINTENANCE"
        const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_PERMISSION = 2

        /** "Tap to activate" prompt (Android 10 background-start policy). */
        private const val ACTIVATION_NOTIFICATION_ID = 3
        private const val ACTIVATION_REQUEST_CODE = 1003
        private const val CHANNEL_ACTIVATION = "jarvis_activation"

        /** MainActivity extra: start the pipeline from a user tap. */
        const val EXTRA_ACTIVATE_ASSISTANT = "com.jarvis.assistant.ACTIVATE_ASSISTANT"
        private const val RESTART_REQUEST_CODE = 1001
        private const val MAINTENANCE_REQUEST_CODE = 1002

        // P1.4: MAINTENANCE_HOUR / MAINTENANCE_STALE_MS moved to ServicePolicy.
        private const val CHANNEL_ID = "jarvis_foreground"
        private const val CHANNEL_BOOTSTRAP = "jarvis_bootstrap"
        private const val CHANNEL_ERROR = "jarvis_errors"
        private const val ServiceInfo_MICROPHONE =
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

        @Suppress("InlinedApi") // constant is inlined at compile time; only used on API 34+
        private const val ServiceInfo_MEDIA_PROJECTION =
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

        fun explicitStart(context: Context) {
            val intent = Intent(context, JarvisForegroundService::class.java)
                .setAction(ACTION_EXPLICIT_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun explicitStop(context: Context) {
            AppPrefs(context).userStopped = true
            context.stopService(Intent(context, JarvisForegroundService::class.java))
        }

        /**
         * Android 10+ activation prompt (see the class KDoc mic-policy
         * bullet). Posts a high-priority notification whose tap opens
         * [MainActivity] with [EXTRA_ACTIVATE_ASSISTANT]; the activity then
         * starts the pipeline from a user-present context — the only start
         * flavor the Android 10 while-in-use rule rewards with a working
         * microphone. Idempotent: re-posting updates the same notification
         * (setOnlyAlertOnce). Safe to call from a BroadcastReceiver — no
         * service start is involved, so neither the Android 10 microphone
         * rule nor the Android 12+ FGS-start restriction applies.
         */
        fun postActivationPrompt(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            // P1.4: the API-33 POST_NOTIFICATIONS gate is
            // [ServicePolicy.activationPromptAllowed]. The permission lookup
            // is a pure read; on API < 33 its result is irrelevant (the
            // policy allows the prompt unconditionally there), so evaluating
            // it unconditionally is observationally identical to the
            // original short-circuit.
            if (!ServicePolicy.activationPromptAllowed(
                    sdkInt = Build.VERSION.SDK_INT,
                    notificationsPermissionGranted = ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED,
                )
            ) {
                Timber.tag("Activation").w("POST_NOTIFICATIONS denied — activation prompt not shown")
                return
            }
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ACTIVATION,
                    context.getString(R.string.channel_activation),
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
            val tap = PendingIntent.getActivity(
                context,
                ACTIVATION_REQUEST_CODE,
                Intent(context, MainActivity::class.java)
                    .putExtra(EXTRA_ACTIVATE_ASSISTANT, true)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ACTIVATION)
                .setContentTitle(context.getString(R.string.activation_notif_title))
                .setContentText(context.getString(R.string.activation_notif_text))
                .setSmallIcon(R.drawable.ic_mic)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SYSTEM)
                .build()
            nm.notify(ACTIVATION_NOTIFICATION_ID, notification)
        }
    }
}
