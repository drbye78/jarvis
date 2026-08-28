package com.jarvis.assistant

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.model.AssistantState
import com.jarvis.assistant.data.AppDatabase
import com.jarvis.assistant.data.ConversationManager
import com.jarvis.assistant.di.GraphHolder
import com.jarvis.assistant.service.JarvisForegroundService
import com.jarvis.assistant.ui.TranscriptAdapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Home screen: live status chip, rolling conversation transcript (from
 * Room), mic mute, start/stop, and navigation to Alarms / Settings. First
 * launch redirects to onboarding.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var micButton: Button
    private lateinit var toggleButton: Button
    private lateinit var adapter: TranscriptAdapter
    private lateinit var partialText: TextView

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
        adapter = TranscriptAdapter()

        findViewById<RecyclerView>(R.id.transcript).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        findViewById<Button>(R.id.alarmsButton).setOnClickListener {
            startActivity(Intent(this, AlarmsActivity::class.java))
        }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
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
                micButton.text = getString(R.string.mic_unmute)
                statusText.text = getString(R.string.state_muted)
            } else {
                graph.sessionManager.setMuted(false)
                micButton.text = getString(R.string.mic_mute)
            }
        }

        observeTranscript()
    }

    override fun onResume() {
        super.onResume()
        refreshServiceState()
    }

    private fun refreshServiceState() {
        toggleButton.text =
            getString(if (GraphHolder.isRunning) R.string.stop else R.string.start)
    }

    private fun observeTranscript() {
        val manager = ConversationManager(AppDatabase.getInstance(this).messageDao())
        lifecycleScope.launch {
            manager.transcriptLive().collectLatest { messages ->
                adapter.submit(messages)
            }
        }
        // Status chip + live partial: poll graph presence, collect while alive.
        lifecycleScope.launch {
            var collectedGraph: com.jarvis.assistant.di.AppGraph? = null
            var stateJob: kotlinx.coroutines.Job? = null
            var partialJob: kotlinx.coroutines.Job? = null
            while (isActive) {
                val graph = GraphHolder.graph
                if (graph != null && graph !== collectedGraph) {
                    stateJob?.cancel()
                    partialJob?.cancel()
                    collectedGraph = graph
                    stateJob = launch {
                        graph.stateMachine.state.collectLatest { state ->
                            if (micMuted) return@collectLatest
                            statusText.text = labelFor(state)
                        }
                    }
                    partialJob = launch {
                        graph.sessionManager.partialTranscript.collectLatest { partial ->
                            updatePartial(partial)
                        }
                    }
                } else if (graph == null) {
                    collectedGraph = null
                }
                delay(500)
            }
        }
    }

    /**
     * Renders the live ASR partial as a muted, in-progress line. When the
     * partial is cleared ("") the indicator hides so only finalized transcript
     * lines remain visible.
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
    }
}
