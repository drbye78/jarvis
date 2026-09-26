package com.jarvis.assistant

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.button.MaterialButton
import com.jarvis.assistant.audio.AudioPipeline
import com.jarvis.assistant.contracts.Detection
import com.jarvis.assistant.contracts.DetectorState
import com.jarvis.assistant.data.AppDatabase
import com.jarvis.assistant.data.ConversationManager
import com.jarvis.assistant.di.GraphHolder
import com.jarvis.assistant.model.AssistantState
import com.jarvis.assistant.service.JarvisForegroundService
import com.jarvis.assistant.session.TurnActivity
import com.jarvis.assistant.tools.AlarmSchedulerProvider
import com.jarvis.assistant.tools.AlertPermissionReconciler
import com.jarvis.assistant.tools.canScheduleExactAlarms
import com.jarvis.assistant.ui.AudioLevel
import com.jarvis.assistant.ui.AudioLevelMeter
import com.jarvis.assistant.ui.EdgeToEdge
import com.jarvis.assistant.ui.Motion
import com.jarvis.assistant.ui.StateLabel
import com.jarvis.assistant.ui.TranscriptAdapter
import com.jarvis.assistant.ui.VoiceOrbView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Home screen: the voice orb (live assistant state), a status pill, a
 * chat-style transcript (Room-backed, auto-scrolling), the live ASR partial
 * as an "in progress" bubble, and the mic / start-stop control bar.
 * Navigation: alarms + settings in the header.
 *
 * Layout note: the transcript RecyclerView owns its scroll (the old layout
 * nested it inside a ScrollView, which made layout_weight meaningless and
 * the whole page scroll). On wide screens the whole column is capped to
 * 840dp and centered for comfortable reading.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var micButton: MaterialButton
    private lateinit var toggleButton: MaterialButton
    private lateinit var adapter: TranscriptAdapter
    private lateinit var partialText: TextView
    private lateinit var voiceOrb: VoiceOrbView
    private lateinit var transcript: RecyclerView

    /** Captured so reduced motion can drop insert animations and restore them. */
    private var defaultItemAnimator: RecyclerView.ItemAnimator? = null

    /** Re-applies the motion policy when the system setting flips live. */
    private val motionListener: () -> Unit = { applyMotionPolicy() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = com.jarvis.assistant.util.AppPrefs(this)
        if (!prefs.onboarded) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        EdgeToEdge.enable(this)
        setContentView(R.layout.activity_main)
        EdgeToEdge.pad(findViewById(R.id.mainRoot))
        capColumnWidthOnWideScreens()
        statusText = findViewById(R.id.statusText)
        micButton = findViewById(R.id.micButton)
        toggleButton = findViewById(R.id.toggleButton)
        partialText = findViewById(R.id.partialText)
        voiceOrb = findViewById(R.id.voiceOrb)
        transcript = findViewById(R.id.transcript)
        adapter = TranscriptAdapter()

        transcript.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        // Auto-scroll: keep the newest exchange in view as rows are inserted.
        // Reduced motion takes the instant jump: a long smooth scroll is
        // exactly the kind of movement the setting asks us not to perform.
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                val target = adapter.itemCount - 1
                when {
                    target < 0 -> Unit
                    Motion.animationsEnabled() -> transcript.smoothScrollToPosition(target)
                    else -> transcript.scrollToPosition(target)
                }
            }
        })
        applyMotionPolicy()

        findViewById<ImageButton>(R.id.alarmsButton).setOnClickListener {
            startActivity(Intent(this, AlarmsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        toggleButton.setOnClickListener {
            when {
                GraphHolder.isRunning -> {
                    JarvisForegroundService.explicitStop(this)
                    // The explicit stop clears the bound state; render through
                    // the single StateLabel path (null -> "Assistant stopped")
                    // instead of writing the pill text directly.
                    currentState = null
                    currentActivity = null
                    renderStatus()
                }
                // Dead service (e.g. after a failed init): NEVER route through
                // explicitStop — it writes prefs.userStopped=true, which would
                // silently suppress the watchdog revive for a service that is
                // not even running the pipeline (stop-on-dead fix).
                GraphHolder.service == null -> JarvisForegroundService.explicitStart(this)
                // Bootstrapping: no-op — the disabled toggle label already
                // says the assistant is starting up.
            }
            refreshServiceState()
        }

        micButton.setOnClickListener {
            // Mute-drift fix: the SOURCE OF TRUTH is the live graph mute
            // state, collected below — the click only flips it. The old
            // local shadow var desynced on activity recreation (mute +
            // rotate left the orb claiming LISTENING while the pipeline was
            // stopped and the first press a no-op). With no graph there is
            // no pipeline to mute; the button is disabled (see the poll
            // loop) and this branch is a race-guard no-op.
            val graph = GraphHolder.graph ?: return@setOnClickListener
            graph.sessionManager.setMuted(!graph.muteState.value)
        }

        // Honest default: no graph is bound yet (stopped or bootstrapping);
        // the poll loop enables the button as soon as one binds.
        micButton.isEnabled = false

        observeTranscript()

        // Android 10 activation flow: tapping the post-boot / post-update
        // "tap to activate" notification lands here with the extra; the
        // activity is visible, so the start is user-present and the mic
        // is granted (the while-in-use rule).
        maybeActivateFromIntent(intent)
    }

    /**
     * Caps the reading column at [HOME_COLUMN_MAX_WIDTH_DP] only when the
     * window is genuinely wider than that, so it stays centered and
     * comfortable instead of spanning a large tablet. The layout keeps
     * `layout_width="match_parent"`: a fixed dp width is NOT clamped by a
     * FrameLayout, which centers an oversized child and lets it spill off
     * both edges (the regression this restores). Re-applied on every
     * recreation, which covers rotation and window resizes.
     */
    private fun capColumnWidthOnWideScreens() {
        val column = findViewById<View>(R.id.homeColumn)
        val dm = resources.displayMetrics
        val maxWidthPx = (HOME_COLUMN_MAX_WIDTH_DP * dm.density).toInt()
        if (dm.widthPixels > maxWidthPx) {
            column.layoutParams = column.layoutParams.apply { width = maxWidthPx }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        maybeActivateFromIntent(intent)
    }

    /**
     * Hook B: exact-alarm reconciliation on every foreground. The
     * revoke/grant broadcast is not reliably delivered, so opening the app
     * re-arms everything when SCHEDULE_EXACT_ALARM is available. Runs on a
     * background dispatcher — [AlertPermissionReconciler.reconcile] hits the
     * alert store; the main thread is never blocked.
     */
    override fun onStart() {
        super.onStart()
        // Reduced motion must track the system setting live, not only at
        // process start: Motion owns the ANIMATOR_DURATION_SCALE observer and
        // this activity owns its lifecycle.
        Motion.start(this)
        Motion.addListener(motionListener)
        windowStarted = true
        applyMotionPolicy()
        val scheduler = AlarmSchedulerProvider.get(this)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                AlertPermissionReconciler(
                    scheduler,
                    canScheduleExact = { canScheduleExactAlarms(this@MainActivity) },
                ).reconcile()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Exact-alarm reconciliation on foreground failed")
            }
        }
    }

    override fun onStop() {
        // Stop paying for the orb's loudness response while the window is not
        // visible; the metering loop reads this on every frame.
        windowStarted = false
        Motion.removeListener(motionListener)
        Motion.stop()
        super.onStop()
    }

    /**
     * Android 10 background-start policy: the post-boot / post-update
     * activation notification opens this activity with
     * [JarvisForegroundService.EXTRA_ACTIVATE_ASSISTANT]; while the activity
     * is visible the explicit start is user-present — the only start context
     * that grants microphone access on Android 10+.
     */
    private fun maybeActivateFromIntent(intent: Intent?) {
        val activate = intent
            ?.getBooleanExtra(JarvisForegroundService.EXTRA_ACTIVATE_ASSISTANT, false) == true
        if (activate && !GraphHolder.isRunning) {
            JarvisForegroundService.explicitStart(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshServiceState()
    }

    private fun refreshServiceState() {
        when {
            GraphHolder.isRunning -> {
                toggleButton.isEnabled = true
                toggleButton.setText(R.string.stop)
                toggleButton.setIconResource(R.drawable.ic_power)
            }
            // Service attached but no graph yet: the ~1-min bootstrap is in
            // progress (or the last attempt failed and the watchdog is
            // retrying) — neither «Запустить» nor «Остановить» is truthful
            // there; show the bootstrapping label, disabled (graph-ready fix).
            GraphHolder.service != null -> {
                toggleButton.isEnabled = false
                toggleButton.setText(R.string.state_bootstrapping)
                toggleButton.setIconResource(R.drawable.ic_power)
            }
            else -> {
                toggleButton.isEnabled = true
                toggleButton.setText(R.string.start)
                toggleButton.setIconResource(R.drawable.ic_power)
            }
        }
    }

    private fun observeTranscript() {
        val manager = ConversationManager(AppDatabase.getInstance(this).messageDao())
        lifecycleScope.launch {
            manager.transcriptLive().collectLatest { messages ->
                adapter.submit(messages)
            }
        }
        // Status + orb + live partial: poll graph presence, collect while alive
        // (the graph is rebuilt per service start; the poll re-binds collectors).
        lifecycleScope.launch {
            var collectedGraph: com.jarvis.assistant.di.AppGraph? = null
            var stateJob: kotlinx.coroutines.Job? = null
            var partialJob: kotlinx.coroutines.Job? = null
            var progressJob: kotlinx.coroutines.Job? = null
            var activityJob: kotlinx.coroutines.Job? = null
            var muteJob: kotlinx.coroutines.Job? = null
            var deafJob: kotlinx.coroutines.Job? = null
            var levelJob: kotlinx.coroutines.Job? = null
            var wakeJob: kotlinx.coroutines.Job? = null
            while (isActive) {
                // Stop-on-dead fix: the toggle must track the REAL service
                // state every poll tick, not only onResume/click — after an
                // init failure it used to keep reading «Остановить» and a tap
                // wrote userStopped for a dead service.
                refreshServiceState()
                val graph = GraphHolder.graph
                if (graph != null && graph !== collectedGraph) {
                    stateJob?.cancel()
                    partialJob?.cancel()
                    activityJob?.cancel()
                    muteJob?.cancel()
                    deafJob?.cancel()
                    levelJob?.cancel()
                    wakeJob?.cancel()
                    levelJob = null
                    wakeJob = null
                    collectedGraph = graph
                    micButton.isEnabled = true
                    // Deaf-engine fix: seed from the detector's synchronous
                    // state so a failure that happened before this bind is
                    // visible immediately, then keep collecting (a reconfigure
                    // can recover the engine later — the flag must clear too).
                    deaf = graph.wakeWordDetector.state.value is DetectorState.Failed
                    stateJob = launch {
                        graph.stateMachine.state.collectLatest { state ->
                            currentState = state
                            voiceOrb.setState(state, micMuted, deaf)
                            // Loudness metering is subscribed ONLY while the mic
                            // actually captures (LISTENING / follow-up); in every
                            // other state the frame flow is untouched, so an
                            // idle-or-speaking assistant costs nothing per frame.
                            if (AudioLevel.capturesAudio(state)) {
                                if (levelJob?.isActive != true) {
                                    levelJob = launch { meterLevels(graph) }
                                }
                            } else {
                                levelJob?.cancel()
                                levelJob = null
                                voiceOrb.setLevel(0f)
                            }
                            renderStatus()
                        }
                    }
                    partialJob = launch {
                        graph.sessionManager.partialTranscript.collectLatest { partial ->
                            updatePartial(partial)
                        }
                    }
                    // G3: what the engine is doing while THINKING —
                    // «Ставлю будильник…» instead of a flat «Думаю…».
                    activityJob = launch {
                        graph.sessionManager.turnActivity.collectLatest { activity ->
                            currentActivity = activity
                            renderStatus()
                        }
                    }
                    // Follow-up window: countdown arc on the orb + label.
                    progressJob?.cancel()
                    progressJob = launch {
                        graph.sessionManager.followUpProgress.collect { fraction ->
                            voiceOrb.setFollowUpProgress(fraction)
                        }
                    }
                    // Mute-drift fix: the graph's mute state is the single
                    // source of truth — collected, never shadowed locally.
                    muteJob = launch {
                        graph.muteState.collect { muted ->
                            micMuted = muted
                            renderMicButton(muted)
                            voiceOrb.setState(currentState, muted, deaf)
                            renderStatus()
                        }
                    }
                    deafJob = launch {
                        graph.wakeWordDetector.state.collect { st ->
                            val failed = st is DetectorState.Failed
                            if (failed != deaf) {
                                deaf = failed
                                voiceOrb.setState(currentState, micMuted, deaf)
                                renderStatus()
                            }
                        }
                    }
                    // Wake-word cue: the detector's OWN event, not a state edge.
                    // LISTENING is entered by the follow-up window and by VAD too,
                    // so keying the acknowledgement off the state transition would
                    // fire it when no wake word was spoken. Detections are cheap
                    // and event-driven, so this collector is effectively free while
                    // the assistant sits idle.
                    wakeJob = launch {
                        graph.wakeWordDetector.detections().collect { detection ->
                            if (detection is Detection.WakeWord) voiceOrb.playWakeCue()
                        }
                    }
                } else if (graph == null) {
                    if (collectedGraph != null) {
                        // Service stopped: reset the orb to idle-gray so the
                        // screen stops claiming a live assistant.
                        collectedGraph = null
                        currentState = null
                        currentActivity = null
                        deaf = false
                        micMuted = false
                        micButton.isEnabled = false
                        renderMicButton(muted = false)
                        voiceOrb.setState(null, muted = false, deaf = false)
                        // Single render path: a null state resolves to the
                        // honest "Assistant stopped" label instead of leaving
                        // the last live state on the pill.
                        renderStatus()
                    }
                }
                delay(SERVICE_STATE_POLL_MS)
            }
        }
    }

    /**
     * Mute-drift fix: mirrored ONLY from the [AppGraph.muteState] collector —
     * the click handler flips the graph state; this field just caches the
     * collected value for rendering. There is no second writer, so activity
     * recreation can no longer desync the button from the pipeline.
     */
    private var micMuted = false

    /**
     * True between [onStart] and [onStop]. The collectors below live in
     * `lifecycleScope`, which only cancels at DESTROY — so a backgrounded window
     * would otherwise keep metering mic frames for a screen nobody can see. For
     * an always-on assistant that is the common case (LISTENING while the app is
     * in the background), so the metering loop checks this and skips its
     * per-frame work while the window is not started.
     */
    private var windowStarted = false

    /**
     * Loudness meter behind the orb's pulse. One instance reused across capture
     * sessions ([AudioLevelMeter.reset] clears it at the start of each), so the
     * metering path allocates nothing per frame.
     */
    private val audioMeter = AudioLevelMeter()

    /**
     * Pump mic frames into the orb's loudness response while the assistant is
     * capturing. Subscribed ONLY for the states where the mic is actually open
     * (the `AudioLevel.capturesAudio` gate at the state collector) and cancelled
     * the moment capture stops, so an idle or speaking assistant does no
     * per-frame work at all — this runs on an always-on, low-end device.
     *
     * The maths (RMS -> perceptual dB mapping -> attack/release envelope) lives
     * in [AudioLevel] and is pinned by `AudioLevelTest`; this loop only decides
     * the RATE. Every frame still folds into the envelope, but the UI is posted
     * at [LEVEL_UPDATE_MS] rather than at the 50 fps capture rate, because the
     * frames in between cannot be seen and would only add invalidates.
     *
     * The per-frame work stays on the collecting (main) dispatcher deliberately:
     * a 320-sample pass is microseconds, and moving it to a background
     * dispatcher would just add a hop back to the main thread to reach the orb —
     * more cost than the arithmetic it would save.
     *
     * `windowStarted` short-circuits the whole pass while the window is hidden:
     * `lifecycleScope` cancels only at DESTROY, so without this check a
     * backgrounded LISTENING assistant would keep running the meter at 50 fps for
     * a view that cannot be drawn.
     */
    private suspend fun meterLevels(graph: com.jarvis.assistant.di.AppGraph) {
        audioMeter.reset()
        var lastPost = 0L
        graph.audioPipeline.frames.collect { frame ->
            if (!windowStarted) {
                // Back to calm while hidden: the level must not be stale when
                // the window returns mid-utterance.
                if (audioMeter.level != 0f) {
                    audioMeter.reset()
                    voiceOrb.setLevel(0f)
                }
                return@collect
            }
            audioMeter.onFrame(frame, AudioPipeline.FRAME_MS.toFloat())
            val now = SystemClock.elapsedRealtime()
            if (now - lastPost >= LEVEL_UPDATE_MS) {
                lastPost = now
                voiceOrb.setLevel(audioMeter.level)
            }
        }
    }

    /**
     * Deaf-engine fix: true while the wake-word detector reports
     * [DetectorState.Failed] — no wake word can fire, so the UI must never
     * claim «Джарвис слушает…».
     */
    private var deaf = false

    private fun renderMicButton(muted: Boolean) {
        if (muted) {
            micButton.setIconResource(R.drawable.ic_mic_off)
            micButton.setText(R.string.mic_unmute)
        } else {
            micButton.setIconResource(R.drawable.ic_mic)
            micButton.setText(R.string.mic_mute)
        }
    }

    /** Last observed state — kept so the mute toggle can redraw the orb. */
    private var currentState: AssistantState? = null

    /** G3: last observed turn activity (null = generic THINKING label). */
    private var currentActivity: TurnActivity? = null

    /**
     * Transcript insert motion policy. With motion allowed the list keeps its
     * item animator, paced with the plan's single insert token; with reduced
     * motion the animator is dropped entirely so a new exchange appears as a
     * static end frame. Called at setup and on every live setting flip.
     */
    private fun applyMotionPolicy() {
        transcript.itemAnimator?.let { defaultItemAnimator = it }
        if (Motion.animationsEnabled()) {
            transcript.itemAnimator = defaultItemAnimator
            (transcript.itemAnimator as? SimpleItemAnimator)?.addDuration =
                Motion.TRANSCRIPT_INSERT_MS
        } else {
            transcript.itemAnimator = null
        }
    }

    /**
     * Truthful pill (N2): the pure [StateLabel] mapping owns what is shown
     * (deaf / muted / activity / per-state), so every collector that calls
     * this re-renders the same way. There are deliberately no early `return`s
     * that skip writing the label — a stale pill was the original bug.
     *
     * With motion allowed the label swap crossfades; with reduced motion the
     * new label is written immediately (static end frame). The `label` is
     * written in BOTH paths, so the pill can never be left showing an old
     * state.
     */
    private fun renderStatus() {
        val label = getString(
            StateLabel.labelRes(currentState, micMuted, deaf, currentActivity),
        )
        statusText.animate().cancel()
        if (!Motion.animationsEnabled() || statusText.text?.toString() == label) {
            statusText.alpha = 1f
            statusText.text = label
            return
        }
        statusText.animate()
            .alpha(0f)
            .setDuration(Motion.PILL_CROSSFADE_MS / 2)
            .withEndAction {
                statusText.text = label
                statusText.animate()
                    .alpha(1f)
                    .setDuration(Motion.PILL_CROSSFADE_MS / 2)
                    .start()
            }
            .start()
    }

    /**
     * Renders the live ASR partial as a muted, in-progress bubble above the
     * control bar. When the partial is cleared ("") the bubble hides so only
     * finalized transcript lines remain visible.
     */
    private fun updatePartial(partial: String) {
        if (partial.isBlank()) {
            partialText.visibility = View.GONE
        } else {
            partialText.text = partial
            partialText.visibility = View.VISIBLE
        }
    }

    private companion object {
        /** UI poll for service/graph state (cheap StateFlow read). */
        const val SERVICE_STATE_POLL_MS = 500L

        /**
         * Orb loudness post interval. Capture runs at 50 fps ([AudioPipeline.FRAME_MS]
         * = 20 ms); the orb is repainted at ~30 fps instead, the rate above which
         * the extra frames stop being visible and only cost invalidates.
         */
        const val LEVEL_UPDATE_MS = 33L

        /** Reading-column cap on wide windows; narrower windows fill the width. */
        const val HOME_COLUMN_MAX_WIDTH_DP = 840
    }
}
