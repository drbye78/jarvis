package com.jarvis.assistant

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
import com.jarvis.assistant.tools.ReconcileTrigger
import com.jarvis.assistant.tools.canScheduleExactAlarms
import com.jarvis.assistant.ui.AudioLevel
import com.jarvis.assistant.ui.AudioLevelMeter
import com.jarvis.assistant.ui.EdgeToEdge
import com.jarvis.assistant.ui.Motion
import com.jarvis.assistant.ui.PrimaryControl
import com.jarvis.assistant.ui.SettingsMapping
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
    private lateinit var transcriptEmpty: TextView
    private lateinit var wakeHintText: TextView
    private lateinit var appTitle: TextView
    private lateinit var clearChatButton: ImageButton

    /** Plain prefs built once in [onCreate]; [AppPrefs.userStopped] is read
     *  live by the primary control so an explicit stop can be resumed. */
    private lateinit var appPrefs: com.jarvis.assistant.util.AppPrefs

    /**
     * The custom Sherpa wake word, when one is active (null = the bundled
     * «Джарвис»). Set by [applyWakeHint] on create/resume so every name-bearing
     * surface (header, idle pill, empty hint, wake hint, spoken sample) derives
     * from the same value.
     */
    private var customWakeName: String? = null

    /** Captured so reduced motion can drop insert animations and restore them. */
    private var defaultItemAnimator: RecyclerView.ItemAnimator? = null

    /** Re-applies the motion policy when the system setting flips live. */
    private val motionListener: () -> Unit = { applyMotionPolicy() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appPrefs = com.jarvis.assistant.util.AppPrefs(this)
        if (!appPrefs.onboarded) {
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
        transcriptEmpty = findViewById(R.id.transcriptEmpty)
        wakeHintText = findViewById(R.id.wakeHintText)
        appTitle = findViewById(R.id.appTitle)
        clearChatButton = findViewById(R.id.clearChatButton)
        applyWakeHint()
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
                updateEmptyState()
            }

            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = updateEmptyState()

            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = updateEmptyState()

            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
                updateEmptyState()
            // No onChanged() override: ListAdapter/AsyncListDiffer dispatches
            // only per-range DiffUtil callbacks and never notifyDataSetChanged,
            // so that override was unreachable dead code. The empty state is
            // driven from the submit site in observeTranscript() instead.
        })
        applyMotionPolicy()

        findViewById<ImageButton>(R.id.alarmsButton).setOnClickListener {
            startActivity(Intent(this, AlarmsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        clearChatButton.setOnClickListener { confirmClearChat() }

        // Tap == barge-in: the orb tap routes to the SAME primitive as a spoken
        // stop / wake-word barge-in — it bumps the seq, cancels the active turn
        // (including a proactive mini-session), flushes the player and closes an
        // open follow-up window; a no-op when nothing is active. It only flips
        // graph state, so the state/level collectors below are untouched.
        voiceOrb.contentDescription = getString(R.string.stop)
        voiceOrb.setOnClickListener {
            GraphHolder.graph?.sessionManager?.stopActiveTurn()
        }

        toggleButton.setOnClickListener {
            when (primaryControlState()) {
                // A user stop writes `userStopped=true`, which suppresses the
                // watchdog revive — so the ONLY way back to listening is an
                // explicit start (it clears the flag and re-promotes the FGS).
                // RESUME is resolved FIRST (see PrimaryControl), because a
                // running-but-deaf assistant would otherwise route to
                // explicitStop and the user could never resume.
                PrimaryControl.State.RESUMED -> JarvisForegroundService.explicitStart(this)
                PrimaryControl.State.STOPPABLE -> {
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
                PrimaryControl.State.STARTABLE -> JarvisForegroundService.explicitStart(this)
                // Bootstrapping: no-op — the disabled toggle label already
                // says the assistant is starting up.
                PrimaryControl.State.BOOTSTRAPPING -> Unit
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
                ).reconcile(ReconcileTrigger.FOREGROUND)
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
        // The wake word can change in Settings while this screen is stopped;
        // re-derive the prompt so it never advertises the wrong phrase.
        applyWakeHint()
    }

    /**
     * The under-orb prompt must name the ACTUAL wake word, and that same custom
     * keyword is the assistant's NAME. A custom Sherpa keyword replaces the
     * bundled «Джарвис» in the header title, idle pill and empty-transcript
     * hint too, so a static label would claim an identity the engine no longer
     * answers to. Porcupine (a `.ppn` phrase we cannot read back) keeps the
     * bundled default naming.
     *
     * Called on create and every [onResume], so a Settings change applies the
     * moment the user returns.
     */
    private fun applyWakeHint() {
        val prefs = com.jarvis.assistant.util.AppPrefs(this)
        val keyword = SettingsMapping.customWakeHintKeyword(
            prefs.sherpaCustomKeyword,
            prefs.wakeWordEngine,
        )
        customWakeName = keyword
        val name = keyword ?: SettingsMapping.DEFAULT_WAKE_NAME
        appTitle.text = name
        wakeHintText.text = if (keyword != null) {
            getString(R.string.wake_hint_custom, keyword)
        } else {
            getString(R.string.wake_hint)
        }
        transcriptEmpty.text = if (keyword != null) {
            getString(R.string.transcript_empty_hint_named, keyword)
        } else {
            getString(R.string.transcript_empty_hint)
        }
        // Re-renders the idle pill through the single StateLabel path so the
        // «<name> слушает…» line picks up the new identity.
        renderStatus()
    }

    /**
     * «Новый чат»: confirm, then delete ONLY the dialogue history. Stored
     * memory / facts / summaries are a separate action and are never touched
     * here. The confirmation keeps an irreversible wipe one deliberate tap away
     * from the header.
     */
    private fun confirmClearChat() {
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_clear_confirm_title)
            .setMessage(R.string.chat_clear_confirm_text)
            .setPositiveButton(R.string.chat_clear_confirm) { _, _ -> clearChatHistory() }
            .setNegativeButton(R.string.chat_clear_cancel, null)
            .show()
    }

    /**
     * Delete the dialogue history through the graph-owned
     * [com.jarvis.assistant.data.ConversationManager] when the service is
     * running, so an active turn is cancelled BEFORE the wipe and cannot race it
     * with a late insert. When the service is stopped there is no turn to race,
     * so the Room singleton is a safe direct path to the SAME `messages` table
     * (both the UI transcript Flow and the LLM history read it, so one delete
     * keeps them consistent and the UI auto-empties).
     *
     * Failure is silent: a thrown delete logs (content-free) and the UI simply
     * keeps the rows — the wipe is obvious from the empty transcript, so no
     * toast is needed either way.
     */
    private fun clearChatHistory() {
        val graph = GraphHolder.graph
        lifecycleScope.launch {
            try {
                if (graph != null) {
                    graph.clearConversation()
                } else {
                    // Stopped service: no turn can race us. Same table, same
                    // singleton the graph uses — the one existing DELETE path.
                    AppDatabase.getInstance(this@MainActivity).messageDao().clear()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Content-free: message rows may hold user speech.
                Timber.w(e, "Chat clear failed")
            }
        }
    }

    /** The single source of truth for the primary control's presentation/action. */
    private fun primaryControlState(): PrimaryControl.State = PrimaryControl.state(
        userStopped = appPrefs.userStopped,
        running = GraphHolder.isRunning,
        serviceAttached = GraphHolder.service != null,
    )

    private fun refreshServiceState() {
        when (primaryControlState()) {
            // Explicitly stopped: the assistant is suppressed from auto-revive,
            // so the only honest label is "resume listening" (an explicit start
            // clears the flag). This MUST precede the running branch: a
            // stopped-but-still-bound graph would otherwise read «Остановить»
            // and leave no way back to listening.
            PrimaryControl.State.RESUMED -> {
                toggleButton.isEnabled = true
                toggleButton.setText(R.string.resume_listening)
                toggleButton.setIconResource(R.drawable.ic_power)
            }
            PrimaryControl.State.STOPPABLE -> {
                toggleButton.isEnabled = true
                toggleButton.setText(R.string.stop)
                toggleButton.setIconResource(R.drawable.ic_power)
            }
            // Service attached but no graph yet: the ~1-min bootstrap is in
            // progress (or the last attempt failed and the watchdog is
            // retrying) — neither «Запустить» nor «Остановить» is truthful
            // there; show the bootstrapping label, disabled (graph-ready fix).
            PrimaryControl.State.BOOTSTRAPPING -> {
                toggleButton.isEnabled = false
                toggleButton.setText(R.string.state_bootstrapping)
                toggleButton.setIconResource(R.drawable.ic_power)
            }
            PrimaryControl.State.STARTABLE -> {
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
                // Single submit site for the transcript, so the empty state is
                // driven from here as well as from the adapter observer below.
                // ListAdapter/AsyncListDiffer only dispatches per-range
                // DiffUtil callbacks (never notifyDataSetChanged) and a
                // zero-row or empty→empty commit can produce no usable range
                // callback, which left the resting prompt hidden on a fresh
                // install. The observer's range callbacks still cover the
                // incremental (diffed) mutations, where the new count is only
                // latched after the background diff.
                updateEmptyState()
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
                    // What the engine is doing while THINKING —
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

    /** Last observed turn activity (null = generic THINKING label). */
    private var currentActivity: TurnActivity? = null

    /**
     * Transcript insert motion policy. With motion allowed the list keeps its
     * item animator, paced with the single insert token; with reduced
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
     * Truthful pill: the pure [StateLabel] mapping owns what is shown
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
        val res = StateLabel.labelRes(currentState, micMuted, deaf, currentActivity)
        // A custom wake word is the assistant's name, so the IDLE pill uses the
        // `%1$s` variant; every other state keeps its existing label.
        val label = if (res == R.string.state_idle_full && customWakeName != null) {
            getString(R.string.state_idle_full_named, customWakeName)
        } else {
            getString(res)
        }
        statusText.animate().cancel()
        if (!Motion.animationsEnabled() || statusText.text?.toString() == label) {
            statusText.alpha = 1f
            statusText.text = label
        } else {
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
        renderIdlePresence()
    }

    /**
     * State-driven dressing around the pill, rendered on the SAME single path
     * as the label so neither can drift from the other:
     *
     *  - Wake hint: the 13sp close-range line under the orb is hidden while the
     *    screen is at rest (stopped or IDLE) — the tinted, breathing orb and the
     *    status pill carry idle; prime glance space stays clean. Every other
     *    state shows it, since a first-time voice user may need the prompt
     *    exactly when the assistant wakes up.
     *
     *  - Thinking border: THINKING is the state where the household waits
     *    without knowing whether the request landed — the pill gains a 2dp amber
     *    border (jarvis_accent_thinking_border, day/night twins) as a far-field
     *    "working on it" cue. All other states get the plain pill back.
     */
    private fun renderIdlePresence() {
        val idleLike = currentState == null || currentState == AssistantState.IDLE
        wakeHintText.visibility = if (idleLike) View.GONE else View.VISIBLE
        val thinking = currentState == AssistantState.THINKING
        statusText.setBackgroundResource(
            if (thinking) R.drawable.bg_status_pill_thinking else R.drawable.bg_status_pill,
        )
    }

    /**
     * Shows the resting prompt while the transcript has no rows. Driven from
     * the single submit site in [observeTranscript] (initial render and every
     * submitted list) plus the adapter observer's per-range callbacks
     * (incremental diffed mutations). `ListAdapter`/`AsyncListDiffer` never
     * emits `notifyDataSetChanged`, and an empty commit can carry no usable
     * range callback, so neither source alone is sufficient.
     */
    private fun updateEmptyState() {
        transcriptEmpty.visibility =
            if (adapter.itemCount == 0) View.VISIBLE else View.GONE
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
