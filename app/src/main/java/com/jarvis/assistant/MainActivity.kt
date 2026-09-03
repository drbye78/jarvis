package com.jarvis.assistant

import android.content.Intent
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.jarvis.assistant.model.AssistantState
import com.jarvis.assistant.data.AppDatabase
import com.jarvis.assistant.data.ConversationManager
import com.jarvis.assistant.di.GraphHolder
import com.jarvis.assistant.service.JarvisForegroundService
import com.jarvis.assistant.ui.TranscriptAdapter
import com.jarvis.assistant.ui.VoiceOrbView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

    private var micMuted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = com.jarvis.assistant.util.AppPrefs(this)
        if (!prefs.onboarded) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        micButton = findViewById(R.id.micButton)
        toggleButton = findViewById(R.id.toggleButton)
        partialText = findViewById(R.id.partialText)
        voiceOrb = findViewById(R.id.voiceOrb)
        transcript = findViewById(R.id.transcript)
        adapter = TranscriptAdapter()

        capColumnWidthOnTablets()

        transcript.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        // Auto-scroll: keep the newest exchange in view as rows are inserted.
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                val target = adapter.itemCount - 1
                if (target >= 0) transcript.smoothScrollToPosition(target)
            }
        })

        findViewById<ImageButton>(R.id.alarmsButton).setOnClickListener {
            startActivity(Intent(this, AlarmsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        toggleButton.setOnClickListener {
            if (GraphHolder.isRunning) {
                JarvisForegroundService.explicitStop(this)
                statusText.text = getString(R.string.state_stopped)
            } else {
                JarvisForegroundService.explicitStart(this)
            }
            refreshServiceState()
        }

        micButton.setOnClickListener {
            val graph = GraphHolder.graph ?: return@setOnClickListener
            micMuted = !micMuted
            if (micMuted) {
                graph.sessionManager.setMuted(true)
                micButton.setIconResource(R.drawable.ic_mic_off)
                micButton.setText(R.string.mic_unmute)
                statusText.text = getString(R.string.state_muted)
            } else {
                graph.sessionManager.setMuted(false)
                micButton.setIconResource(R.drawable.ic_mic)
                micButton.setText(R.string.mic_mute)
                // F7: setMuted(false) restarts listening without a state
                // transition (StateFlow does not re-emit IDLE), so the label
                // would stay "Микрофон выключен" until the next wake word.
                statusText.text = currentState?.let { labelFor(it) } ?: getString(R.string.state_idle_full)
            }
            voiceOrb.setState(currentState, micMuted)
        }

        observeTranscript()
    }

    override fun onResume() {
        super.onResume()
        refreshServiceState()
    }

    /** Comfortable reading column on wide/tablet screens. */
    private fun capColumnWidthOnTablets() {
        val column = findViewById<View>(R.id.homeColumn)
        val dm: DisplayMetrics = resources.displayMetrics
        val dp = { v: Int -> (v * dm.density).toInt() }
        if (dm.widthPixels > dp(TABLET_TWO_COLUMN_MIN_WIDTH_DP)) {
            column.layoutParams = column.layoutParams.apply { width = dp(TABLET_COLUMN_WIDTH_DP) }
        }
    }

    private fun refreshServiceState() {
        if (GraphHolder.isRunning) {
            toggleButton.setText(R.string.stop)
            toggleButton.setIconResource(R.drawable.ic_power)
        } else {
            toggleButton.setText(R.string.start)
            toggleButton.setIconResource(R.drawable.ic_power)
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
            while (isActive) {
                val graph = GraphHolder.graph
                if (graph != null && graph !== collectedGraph) {
                    stateJob?.cancel()
                    partialJob?.cancel()
                    collectedGraph = graph
                    stateJob = launch {
                        graph.stateMachine.state.collectLatest { state ->
                            currentState = state
                            voiceOrb.setState(state, micMuted)
                            if (!micMuted) statusText.text = labelFor(state)
                        }
                    }
                    partialJob = launch {
                        graph.sessionManager.partialTranscript.collectLatest { partial ->
                            updatePartial(partial)
                        }
                    }
                    // Follow-up window: countdown arc on the orb + label.
                    progressJob?.cancel()
                    progressJob = launch {
                        graph.sessionManager.followUpProgress.collect { fraction ->
                            voiceOrb.setFollowUpProgress(fraction)
                        }
                    }
                } else if (graph == null) {
                    if (collectedGraph != null) {
                        // Service stopped: reset the orb to idle-gray so the
                        // screen stops claiming a live assistant.
                        collectedGraph = null
                        currentState = null
                        voiceOrb.setState(null, micMuted)
                    }
                }
                delay(SERVICE_STATE_POLL_MS)
            }
        }
    }

    /** Last observed state — kept so the mute toggle can redraw the orb. */
    private var currentState: AssistantState? = null

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

    private fun labelFor(state: AssistantState): String = when (state) {
        AssistantState.IDLE -> getString(R.string.state_idle_full)
        AssistantState.LISTENING -> getString(R.string.state_listening_full)
        AssistantState.THINKING -> getString(R.string.state_thinking_full)
        AssistantState.SPEAKING -> getString(R.string.state_speaking_full)
        AssistantState.FOLLOW_UP_WINDOW -> getString(R.string.state_follow_up_full)
    }

    private companion object {
        /** PROJECT-AUDIT: named layout logic — two-column comfort zone on tablets. */
        const val TABLET_TWO_COLUMN_MIN_WIDTH_DP = 900
        const val TABLET_COLUMN_WIDTH_DP = 840

        /** UI poll for service/graph state (cheap StateFlow read). */
        const val SERVICE_STATE_POLL_MS = 500L
    }
}
