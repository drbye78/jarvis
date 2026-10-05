package com.jarvis.assistant

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.jarvis.assistant.di.GraphHolder
import com.jarvis.assistant.home.HomeAlias
import com.jarvis.assistant.home.HomeAliasCodec
import com.jarvis.assistant.home.HomeDevice
import com.jarvis.assistant.home.HomeDeviceKey
import com.jarvis.assistant.home.HomeEntityCodec
import com.jarvis.assistant.home.HomeNoticePolicy
import com.jarvis.assistant.ui.EdgeToEdge
import com.jarvis.assistant.util.AppPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Smart-home device browser (R4/H2): every discovered device, its spoken alias
 * (tap to edit) and a proactive-notice curation switch where the kind can
 * actually produce a notice.
 *
 * The live catalog lives in the running graph
 * ([com.jarvis.assistant.di.AppGraph.homeGraph] → [com.jarvis.assistant.home.HomeRepository]);
 * this Activity never builds a backend. Aliases persist to
 * [AppPrefs.homeAliases] via [HomeAliasCodec] (LIVE — re-read per turn) and the
 * curated notice set to [AppPrefs.homeEntities] via [HomeEntityCodec] (LIVE).
 *
 * Failures are honest and non-destructive: a stopped service shows an
 * unavailability toast and keeps the last persisted alias/curation state, and a
 * corrupt alias/entity blob degrades to empty (the codecs never throw).
 */
class HomeDevicesActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private lateinit var emptyView: TextView
    private lateinit var adapter: HomeDeviceAdapter
    private val devices = mutableListOf<HomeDevice>()
    private var aliases: Map<String, HomeDeviceKey> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeToEdge.enable(this)
        setContentView(R.layout.activity_home_devices)
        EdgeToEdge.pad(findViewById(R.id.homeDevicesRoot))

        prefs = AppPrefs(this)
        emptyView = findViewById(R.id.homeDeviceEmpty)
        adapter = HomeDeviceAdapter(
            aliasOf = { device -> aliasText(device) },
            onEditAlias = { device -> showAliasEditor(device) },
            noticeChecked = { device -> noticeEnabled(device) },
            onToggleNotice = { device, checked -> setNotice(device, checked) },
        )
        findViewById<RecyclerView>(R.id.homeDeviceList).apply {
            layoutManager = LinearLayoutManager(this@HomeDevicesActivity)
            adapter = this@HomeDevicesActivity.adapter
        }
        findViewById<ImageButton>(R.id.homeBackButton).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.homeDevicesRefresh).setOnClickListener { refresh() }

        render()
        refresh()
    }

    /** Bounded re-discovery through the live graph; renders whatever it leaves. */
    private fun refresh() {
        val graph = GraphHolder.graph
        if (graph == null) {
            Toast.makeText(this, R.string.settings_home_devices_unavailable, Toast.LENGTH_SHORT).show()
            render()
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    graph.homeGraph.refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w("Home devices refresh failed: %s", e::class.java.simpleName)
                }
            }
            render()
        }
    }

    /** Re-read the catalog + aliases and repaint. Never throws. */
    private fun render() {
        val graph = GraphHolder.graph
        devices.clear()
        if (graph != null) {
            devices.addAll(
                graph.homeGraph.repository.all()
                    .sortedWith(compareBy({ it.room ?: "" }, { it.name })),
            )
        }
        aliases = HomeAliasCodec.decodeOrEmpty(prefs.homeAliases)
        adapter.submit(devices)
        emptyView.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun aliasText(device: HomeDevice): String =
        HomeAlias.aliasFor(aliases, device.key)
            ?: getString(R.string.settings_home_alias_none)

    // ------------------------------------------------------------------
    // Alias editing (LIVE — the resolver re-reads homeAliases per turn)
    // ------------------------------------------------------------------

    private fun showAliasEditor(device: HomeDevice) {
        val field = TextInputEditText(this).apply {
            hint = getString(R.string.settings_home_alias_dialog_hint)
            setText(HomeAlias.aliasFor(aliases, device.key) ?: "")
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_home_alias_dialog_title)
            .setView(field)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.settings_home_alias_clear, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                persistAlias(device, field.text?.toString().orEmpty())
                Toast.makeText(this, R.string.settings_home_alias_saved, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                persistAlias(device, "")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun persistAlias(device: HomeDevice, alias: String) {
        val updated = HomeAlias.bind(aliases, alias, device.key)
        aliases = updated
        prefs.homeAliases = HomeAliasCodec.encode(updated)
        adapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------------
    // Proactive-notice curation (LIVE — the awareness loop re-subscribes)
    // ------------------------------------------------------------------

    /**
     * An empty curated set means "all notifiable devices"; ticking/unticking one
     * therefore materializes the full notifiable set first, so the user's first
     * action is explicit rather than silently flipping the global default.
     */
    private fun noticeEnabled(device: HomeDevice): Boolean {
        val curated = HomeEntityCodec.decodeOrEmpty(prefs.homeEntities)
        return curated.isEmpty() || device.key in curated
    }

    private fun setNotice(device: HomeDevice, checked: Boolean) {
        val current = HomeEntityCodec.decodeOrEmpty(prefs.homeEntities)
        val materialized = if (current.isEmpty()) allNotifiableKeys() else current
        val updated = if (checked) materialized + device.key else materialized - device.key
        prefs.homeEntities = HomeEntityCodec.encode(updated)
        adapter.notifyDataSetChanged()
    }

    private fun allNotifiableKeys(): Set<HomeDeviceKey> =
        devices.filter { HomeNoticePolicy.interesting(it.kind) }.map { it.key }.toSet()

    // ------------------------------------------------------------------
    // Adapter
    // ------------------------------------------------------------------

    private class HomeDeviceAdapter(
        private val aliasOf: (HomeDevice) -> String,
        private val onEditAlias: (HomeDevice) -> Unit,
        private val noticeChecked: (HomeDevice) -> Boolean,
        private val onToggleNotice: (HomeDevice, Boolean) -> Unit,
    ) : RecyclerView.Adapter<HomeDeviceAdapter.VH>() {

        private val items = mutableListOf<HomeDevice>()

        fun submit(list: List<HomeDevice>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_home_device, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val device = items[position]
            holder.name.text = device.name
            holder.meta.text = metaText(holder.itemView, device)
            holder.alias.text = aliasOf(device)
            holder.aliasRow.setOnClickListener { onEditAlias(device) }

            val notifiable = HomeNoticePolicy.interesting(device.kind)
            holder.notice.visibility = if (notifiable) View.VISIBLE else View.GONE
            holder.notice.setOnCheckedChangeListener(null)
            holder.notice.isChecked = notifiable && noticeChecked(device)
            holder.notice.setOnCheckedChangeListener { _, checked ->
                if (notifiable) onToggleNotice(device, checked)
            }
        }

        private fun metaText(view: View, device: HomeDevice): String {
            val kind = view.context.getString(HomeLabels.kindRes(device.kind))
            val room = device.room
            return if (room.isNullOrBlank()) kind else "$kind · $room"
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.homeDeviceName)
            val meta: TextView = view.findViewById(R.id.homeDeviceMeta)
            val aliasRow: View = view.findViewById(R.id.homeDeviceAliasRow)
            val alias: TextView = view.findViewById(R.id.homeDeviceAlias)
            val notice: MaterialSwitch = view.findViewById(R.id.homeDeviceNotice)
        }
    }
}
