package com.jarvis.assistant

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.jarvis.assistant.cognitive.CognitiveCoordinator
import com.jarvis.assistant.cognitive.CognitiveCoordinator.Companion.prettyJson
import com.jarvis.assistant.cognitive.data.UserFactEntity
import com.jarvis.assistant.di.GraphHolder
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * COGNITIVE_PLAN 1.8/§4: the Memory Inspector — "transparency is a feature,
 * not a debug screen" (plan principle 7). The user can see every fact with
 * its provenance marks (§12.4-2: sensitive facts are always VISIBLE but
 * MARKED), delete one item, export everything as JSON (SAF) or wipe all
 * cognitive data with a confirmation.
 *
 * «Забыть всё» wipes the cognitive tables only — `messages` are a separate,
 * pre-existing control (plan §9.2). The queue depth is shown so queued
 * offline work is visible, never invisible (plan §6.2: queued, never
 * dropped, never faked).
 */
class MemoryInspectorActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var pendingView: TextView
    private lateinit var wipeButton: MaterialButton
    private lateinit var exportButton: Button
    private lateinit var adapter: FactAdapter

    private val coordinator: CognitiveCoordinator?
        get() = GraphHolder.graph?.cognitiveCoordinator

    private val exportLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument(
                "application/json",
            ),
        ) { uri: android.net.Uri? ->
            if (uri != null) exportTo(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory_inspector)

        list = findViewById(R.id.memoryList)
        emptyView = findViewById(R.id.memoryEmpty)
        pendingView = findViewById(R.id.memoryPending)
        wipeButton = findViewById(R.id.memoryWipeButton)
        exportButton = findViewById(R.id.memoryExportButton)
        findViewById<Button>(R.id.memoryCloseButton).setOnClickListener { finish() }

        adapter = FactAdapter(
            onDelete = { factId ->
                lifecycleScope.launch {
                    coordinator?.forgetById(factId)
                    Toast.makeText(this@MemoryInspectorActivity, R.string.memory_item_deleted, Toast.LENGTH_SHORT).show()
                }
            },
            markFor = ::markFor,
            statusFor = ::statusFor,
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        if (coordinator == null) {
            // Assistant service not running: the inspector is read-only
            // without the graph — show the honest empty state.
            emptyView.visibility = View.VISIBLE
            list.visibility = View.GONE
            wipeButton.isEnabled = false
            exportButton.isEnabled = false
            return
        }

        lifecycleScope.launch {
            coordinator!!.observeFacts().collect { facts ->
                val rows = facts.map { entity ->
                    UserFactRow(
                        factId = entity.factId,
                        title = entity.value,
                        marks = markFor(entity),
                        statusLine = statusFor(entity),
                    )
                }
                adapter.submitList(rows)
                emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                list.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
            }
        }
        lifecycleScope.launch {
            coordinator!!.observePendingCount().collect { pending ->
                pendingView.visibility = if (pending > 0) View.VISIBLE else View.GONE
                pendingView.text = getString(R.string.settings_memory_queue, pending)
            }
        }

        wipeButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_memory_wipe_confirm_title)
                .setMessage(R.string.settings_memory_wipe_confirm_text)
                .setPositiveButton(R.string.settings_memory_wipe) { _, _ ->
                    lifecycleScope.launch {
                        coordinator?.wipeAll()
                        Toast.makeText(this@MemoryInspectorActivity, R.string.settings_memory_wipe_done, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        exportButton.setOnClickListener {
            lifecycleScope.launch {
                val json = coordinator?.exportJson() ?: return@launch
                val count = (json["facts"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0
                if (count == 0) {
                    Toast.makeText(this@MemoryInspectorActivity, R.string.settings_memory_export_none, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                exportLauncher.launch("jarvis-memory.json")
            }
        }
    }

    /** §12.4-2: sensitive facts are rendered MARKED, always. */
    private fun markFor(entity: UserFactEntity): String = buildList {
        if (entity.sensitive) add(getString(R.string.memory_item_sensitive))
        if (entity.contested) add(getString(R.string.memory_item_contested))
    }.joinToString(", ")

    private fun statusFor(entity: UserFactEntity): String = when (entity.status) {
        "SUPERSEDED" -> getString(R.string.memory_item_superseded)
        "FORGOTTEN" -> getString(R.string.memory_item_forgotten)
        "ARCHIVED" -> getString(R.string.memory_item_archived)
        "QUARANTINED" -> getString(R.string.memory_item_quarantined)
        else -> getString(
            R.string.memory_item_confidence,
            when {
                entity.confidence >= 0.8f -> getString(R.string.memory_confidence_high)
                entity.confidence >= 0.5f -> getString(R.string.memory_confidence_medium)
                else -> getString(R.string.memory_confidence_low)
            },
        )
    }

    private fun exportTo(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val json = coordinator?.exportJson() ?: return@launch
                contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(prettyJson(json).toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this@MemoryInspectorActivity, R.string.settings_memory_export_done, Toast.LENGTH_SHORT).show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Memory export failed")
                Toast.makeText(this@MemoryInspectorActivity, R.string.settings_memory_export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** UI model for one inspector row (decoupled from the entity). */
data class UserFactRow(
    val factId: String,
    val title: String,
    val marks: String,
    val statusLine: String,
)

private val DIFF = object : DiffUtil.ItemCallback<UserFactRow>() {
    override fun areItemsTheSame(a: UserFactRow, b: UserFactRow) = a.factId == b.factId
    override fun areContentsTheSame(a: UserFactRow, b: UserFactRow) = a == b
}

/** One inspector row: value + marks + provenance + a Forget action. */
private class FactAdapter(
    private val onDelete: (String) -> Unit,
    private val markFor: (UserFactEntity) -> String,
    private val statusFor: (UserFactEntity) -> String,
) : ListAdapter<UserFactRow, FactViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_memory_fact, parent, false)
        return FactViewHolder(view, onDelete)
    }

    override fun onBindViewHolder(holder: FactViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

private class FactViewHolder(
    view: View,
    private val onDelete: (String) -> Unit,
) : RecyclerView.ViewHolder(view) {

    private val title: TextView = view.findViewById(R.id.factTitle)
    private val marks: TextView = view.findViewById(R.id.factMarks)
    private val status: TextView = view.findViewById(R.id.factStatus)
    private val delete: Button = view.findViewById(R.id.factDelete)

    fun bind(row: UserFactRow) {
        title.text = row.title
        marks.text = row.marks
        marks.visibility = if (row.marks.isEmpty()) View.GONE else View.VISIBLE
        status.text = row.statusLine
        status.visibility = if (row.statusLine.isEmpty()) View.GONE else View.VISIBLE
        delete.setOnClickListener { onDelete(row.factId) }
    }
}
