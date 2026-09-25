package com.jarvis.assistant.settings.controller

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.materialswitch.MaterialSwitch
import com.jarvis.assistant.R
import com.jarvis.assistant.cognitive.data.MemoryMetaEntity
import com.jarvis.assistant.settings.BaseSettingsController
import com.jarvis.assistant.settings.SettingsCallbacks
import com.jarvis.assistant.settings.SettingsHost
import com.jarvis.assistant.ui.SettingsMapping
import com.jarvis.assistant.util.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Память» / MEMORY detail screen controller (settings redesign, F-E).
 *
 * Ports the old Activity's `setupMemoryCard` / `setupSemanticRecallCard` /
 * `observeSemanticStatus` / `setupSemanticBenchmarkButton` /
 * `setupSemanticVectorsButton` behaviour onto `screen_settings_memory.xml`.
 *
 * LEVELS: `memoryEnabled` is ESSENTIAL; the three remaining memory switches and
 * `memoryEmbedder` are ADVANCED (disclosure count 4). Every switch is consumed
 * reactively by the coordinator (PrefsFlow), so a toggle applies from the next
 * turn — no restart, no [PendingChanges] mark.
 *
 * The semantic-recall status is a COLLECTOR: the frozen seam has no
 * `lifecycleScope`, so the controller owns a [CoroutineScope] and cancels it in
 * [onStop]. A stop→resume round-trip cancels the scope (and with it the
 * collector), so [onResume] revives it — otherwise the live vector-build
 * progress would freeze after the first navigation away.
 *
 * The inspector / benchmark / vector-build / backfill buttons are ACTIONS, not
 * settings: they are not counted in the disclosure label and are not persisted.
 * The inspector opens through [SettingsHost.openMemoryInspector] — the host owns
 * navigation, so this controller never starts an Intent itself.
 */
class MemorySettingsController(
    callbacks: SettingsCallbacks,
    prefs: AppPrefs,
    host: SettingsHost,
) : BaseSettingsController(callbacks, prefs, host) {

    private lateinit var context: Context

    private lateinit var memoryEnabledSwitch: MaterialSwitch
    private lateinit var memoryAutoExtractSwitch: MaterialSwitch
    private lateinit var memoryCloudSwitch: MaterialSwitch
    private lateinit var memorySensitiveSwitch: MaterialSwitch
    private lateinit var embedderSelectorButton: Button
    private lateinit var embedderStatusText: TextView
    private lateinit var semanticBenchmarkButton: Button
    private lateinit var semanticBenchmarkResult: TextView
    private lateinit var semanticVectorsButton: Button
    private lateinit var semanticVectorsStatus: TextView
    private lateinit var memoryInspectorButton: Button
    private lateinit var memoryBackfillButton: Button
    private lateinit var memoryBackfillStatus: TextView

    private lateinit var embedderLabels: Map<String, String>
    private lateinit var engineLabels: Map<String, String>

    /** Owned scope (the seam has no `lifecycleScope`); cancelled in [onStop]. */
    private var scope = newScope()

    /** The live semantic-status collector; cancelled/replaced across revive. */
    private var statusJob: Job? = null

    override fun bind(root: View) {
        context = root.context
        memoryEnabledSwitch = root.findViewById(R.id.memoryEnabledSwitch)
        memoryAutoExtractSwitch = root.findViewById(R.id.memoryAutoExtractSwitch)
        memoryCloudSwitch = root.findViewById(R.id.memoryCloudSwitch)
        memorySensitiveSwitch = root.findViewById(R.id.memorySensitiveSwitch)
        embedderSelectorButton = root.findViewById(R.id.embedderSelectorButton)
        embedderStatusText = root.findViewById(R.id.embedderStatusText)
        semanticBenchmarkButton = root.findViewById(R.id.semanticBenchmarkButton)
        semanticBenchmarkResult = root.findViewById(R.id.semanticBenchmarkResult)
        semanticVectorsButton = root.findViewById(R.id.semanticVectorsButton)
        semanticVectorsStatus = root.findViewById(R.id.semanticVectorsStatus)
        memoryInspectorButton = root.findViewById(R.id.memoryInspectorButton)
        memoryBackfillButton = root.findViewById(R.id.memoryBackfillButton)
        memoryBackfillStatus = root.findViewById(R.id.memoryBackfillStatus)

        embedderLabels = mapOf(
            "AUTO" to context.getString(R.string.settings_semantic_embedder_auto),
            "CLOUD" to context.getString(R.string.settings_semantic_embedder_cloud),
            "LOCAL" to context.getString(R.string.settings_semantic_embedder_local),
            "OFF" to context.getString(R.string.settings_semantic_embedder_off),
        )
        engineLabels = mapOf(
            "local-lexical-v1" to context.getString(R.string.settings_semantic_embedder_local),
            "gigachat-embeddings" to context.getString(R.string.settings_semantic_embedder_cloud),
        )

        bindSwitches()
        bindDisclosure(root)
        bindEmbedderSelector()
        bindActions()

        startStatusCollection()
    }

    override fun onResume() {
        // Guard against a host that resumes before binding; the frozen seam has
        // no ordering guarantee beyond "bind, then lifecycle".
        if (!::memoryEnabledSwitch.isInitialized) return
        if (!scope.isActive) {
            scope = newScope()
            startStatusCollection()
        }
    }

    override fun onStop() {
        scope.cancel()
    }

    /** The four memory switches: one essential (above the disclosure), three advanced. */
    private fun bindSwitches() {
        memoryEnabledSwitch.isChecked = prefs.memoryEnabled
        memoryEnabledSwitch.setOnCheckedChangeListener { _, checked -> prefs.memoryEnabled = checked }

        memoryAutoExtractSwitch.isChecked = prefs.memoryAutoExtract
        memoryAutoExtractSwitch.setOnCheckedChangeListener { _, checked -> prefs.memoryAutoExtract = checked }

        memoryCloudSwitch.isChecked = prefs.memoryCloudEnabled
        memoryCloudSwitch.setOnCheckedChangeListener { _, checked -> prefs.memoryCloudEnabled = checked }

        memorySensitiveSwitch.isChecked = prefs.memorySensitiveVisible
        memorySensitiveSwitch.setOnCheckedChangeListener { _, checked -> prefs.memorySensitiveVisible = checked }
    }

    /** The disclosure exists because the screen has both essential and advanced content. */
    private fun bindDisclosure(root: View) {
        val toggle = root.findViewById<View>(R.id.settingsAdvancedToggle)
        val label = root.findViewById<TextView>(R.id.settingsAdvancedLabel)
        val chevron = root.findViewById<View>(R.id.settingsAdvancedChevron)
        val advanced = root.findViewById<View>(R.id.settingsMemoryAdvanced)

        toggle.visibility = View.VISIBLE
        label.text = context.getString(R.string.settings_advanced_show, ADVANCED_COUNT)
        toggle.setOnClickListener {
            val expanded = advanced.visibility != View.VISIBLE
            advanced.visibility = if (expanded) View.VISIBLE else View.GONE
            chevron.rotation = if (expanded) 180f else 0f
        }
    }

    /**
     * The embedder selector cycles AUTO → CLOUD → LOCAL → OFF and persists each
     * step immediately. The coordinator reads `memoryEmbedder` reactively, so
     * no restart is required.
     */
    private fun bindEmbedderSelector() {
        renderEmbedderSelector()
        embedderSelectorButton.setOnClickListener {
            prefs.memoryEmbedder = SettingsMapping.nextEmbedder(prefs.memoryEmbedder)
            renderEmbedderSelector()
        }
    }

    private fun renderEmbedderSelector() {
        embedderSelectorButton.text =
            embedderLabels[prefs.memoryEmbedder] ?: embedderLabels.getValue(DEFAULT_EMBEDDER)
    }

    private fun bindActions() {
        memoryInspectorButton.setOnClickListener { host.openMemoryInspector() }
        memoryBackfillButton.setOnClickListener { confirmBackfill() }
        semanticBenchmarkButton.setOnClickListener { runBenchmark() }
        semanticVectorsButton.setOnClickListener { confirmVectorBuild() }
    }

    /**
     * Mirrors the stored embedder verdict, the vector count and the live
     * vector-build progress into the status rows. Ported from the Activity's
     * `observeSemanticStatus`; the initial backfill-done check moves here so it
     * is refreshed on every revive as well.
     */
    private fun startStatusCollection() {
        statusJob?.cancel()
        statusJob = scope.launch {
            val graph = host.awaitAssistantGraph() ?: return@launch
            val metaDao = graph.database.memoryMetaDao()

            // Extraction backfill was already completed in a previous run.
            val backfillDone = runCatching {
                metaDao.get(MemoryMetaEntity.KEY_EXTRACTION_BACKFILL_DONE)
            }.getOrNull() != null
            if (backfillDone) {
                memoryBackfillButton.isEnabled = false
                memoryBackfillStatus.setText(R.string.settings_memory_backfill_done)
            }

            // Provenance line: benchmark verdict + stored vector count.
            val winner = runCatching { metaDao.get(MemoryMetaEntity.KEY_EMBEDDER_WINNER) }.getOrNull()
            val vectorEngine = runCatching { metaDao.get(MemoryMetaEntity.KEY_VECTORS_ENGINE) }.getOrNull()
            val vectorCount = runCatching {
                vectorEngine?.let { graph.database.factVectorDao().countForEngine(it) } ?: 0
            }.getOrElse { 0 }
            embedderStatusText.text = if (winner == null) {
                context.getString(R.string.settings_semantic_status_no_winner, vectorCount)
            } else {
                context.getString(
                    R.string.settings_semantic_status,
                    engineLabels[winner] ?: winner,
                    vectorCount,
                )
            }

            // Backfill progress lives on the cognitive scope — mirror it here.
            graph.cognitiveCoordinator.vectorBackfill.progress.collect { p ->
                semanticVectorsStatus.text = when {
                    p == null -> ""
                    p.running -> context.getString(R.string.settings_semantic_vectors_progress, p.done, p.total)
                    p.error != null -> context.getString(
                        R.string.settings_semantic_vectors_failed,
                        p.error.take(60),
                    )
                    else -> context.getString(R.string.settings_semantic_vectors_done, p.done)
                }
            }
        }
    }

    /** On-device retrieval benchmark (static probes only — no user-data egress). */
    private fun runBenchmark() {
        semanticBenchmarkResult.setText(R.string.settings_semantic_benchmark_running)
        scope.launch {
            val graph = host.awaitAssistantGraph() ?: run {
                semanticBenchmarkResult.setText(R.string.voice_service_not_running)
                return@launch
            }
            val outcome = withContext(Dispatchers.IO) {
                runCatching { graph.cognitiveCoordinator.runRetrievalBenchmark() }.getOrNull()
            }
            semanticBenchmarkResult.text = if (outcome == null) {
                context.getString(R.string.settings_semantic_benchmark_failed)
            } else {
                context.getString(
                    R.string.settings_semantic_benchmark_result,
                    outcome.winner?.let { engineLabels[it] ?: it }
                        ?: context.getString(R.string.settings_semantic_no_winner_short),
                    outcome.localReport.take(160),
                )
            }
        }
    }

    /** The opt-in vector build, disclosing the §9.2 egress of the chosen engine. */
    private fun confirmVectorBuild() {
        scope.launch {
            val graph = host.awaitAssistantGraph() ?: run {
                semanticVectorsStatus.setText(R.string.voice_service_not_running)
                return@launch
            }
            val engineId = runCatching {
                graph.cognitiveCoordinator.resolvedEngineId()
            }.getOrNull()
            if (engineId == null) {
                semanticVectorsStatus.setText(R.string.settings_semantic_vectors_no_engine)
                return@launch
            }
            // §12.4-4/§9.2: explicit opt-in; the cloud branch discloses the
            // fact-value egress, the local branch confirms the on-device-only
            // guarantee.
            val message = if (engineId == CLOUD_ENGINE_ID) {
                R.string.settings_semantic_vectors_confirm_cloud
            } else {
                R.string.settings_semantic_vectors_confirm_local
            }
            AlertDialog.Builder(context)
                .setTitle(R.string.settings_semantic_vectors_confirm_title)
                .setMessage(message)
                .setPositiveButton(R.string.settings_memory_backfill) { _, _ ->
                    val started = graph.cognitiveCoordinator.startVectorBuild(engineId)
                    if (!started) {
                        semanticVectorsStatus.setText(R.string.settings_semantic_vectors_running)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** Enqueue the extraction backfill after an explicit confirmation. */
    private fun confirmBackfill() {
        scope.launch {
            val graph = host.awaitAssistantGraph() ?: return@launch
            AlertDialog.Builder(context)
                .setTitle(R.string.settings_memory_backfill_confirm_title)
                .setMessage(R.string.settings_memory_backfill_confirm_text)
                .setPositiveButton(R.string.settings_memory_backfill) { _, _ ->
                    scope.launch {
                        val enqueued = runCatching {
                            graph.cognitiveCoordinator.backfillRecent()
                        }.getOrDefault(-1)
                        when {
                            enqueued == -1 -> {
                                memoryBackfillButton.isEnabled = false
                                memoryBackfillStatus.setText(R.string.settings_memory_backfill_done)
                            }
                            enqueued == 0 -> memoryBackfillStatus.setText(R.string.settings_memory_backfill_none)
                            else -> {
                                memoryBackfillButton.isEnabled = false
                                memoryBackfillStatus.text = context.getString(
                                    R.string.settings_memory_backfill_started,
                                    enqueued,
                                )
                            }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private companion object {
        /** Advanced contract-table entries: 3 switches + the embedder selector. */
        const val ADVANCED_COUNT = 4

        /** The default/stored fallback the selector's label resolves against. */
        const val DEFAULT_EMBEDDER = "AUTO"

        /** Engine id whose vector build egresses fact values to the cloud. */
        const val CLOUD_ENGINE_ID = "gigachat-embeddings"
    }
}
