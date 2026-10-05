package com.jarvis.assistant

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.jarvis.assistant.di.GraphHolder
import com.jarvis.assistant.home.Grantable
import com.jarvis.assistant.home.HomeDevice
import com.jarvis.assistant.home.HomeGrant
import com.jarvis.assistant.home.HomeGrantCodec
import com.jarvis.assistant.home.HomeGrantables
import com.jarvis.assistant.ui.EdgeToEdge
import com.jarvis.assistant.util.AppPrefs

/**
 * Smart-home quick-action grants (R4/H3): per device, tick the recoverable (T1)
 * actions that may run on the fast path without a spoken confirmation.
 *
 * The offered actions come from [HomeGrantables], which runs the SAME
 * [com.jarvis.assistant.home.HomeRiskClassifier] used at execution — locks,
 * doors, covers and appliances are excluded by the classifier, so this screen
 * can never mint a grant for a critical action. Persistence is
 * [HomeGrantCodec] in [AppPrefs.homeGrants] (LIVE — re-read per turn).
 *
 * Devices are read from the live graph's catalog; a stopped service shows an
 * empty state and the screen is read-only (no catalog, nothing to grant).
 */
class HomeGrantsActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private lateinit var emptyView: TextView
    private lateinit var adapter: HomeGrantAdapter
    private val devices = mutableListOf<HomeDevice>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeToEdge.enable(this)
        setContentView(R.layout.activity_home_grants)
        EdgeToEdge.pad(findViewById(R.id.homeGrantsRoot))

        prefs = AppPrefs(this)
        emptyView = findViewById(R.id.homeGrantEmpty)
        adapter = HomeGrantAdapter(
            grantCount = { device -> grantCount(device) },
            onClick = { device -> showGrantsEditor(device) },
        )
        findViewById<RecyclerView>(R.id.homeGrantList).apply {
            layoutManager = LinearLayoutManager(this@HomeGrantsActivity)
            adapter = this@HomeGrantsActivity.adapter
        }
        findViewById<ImageButton>(R.id.homeBackButton).setOnClickListener { finish() }
        render()
    }

    /** Devices that actually offer a T1 action, sorted for a stable list. */
    private fun render() {
        devices.clear()
        GraphHolder.graph?.homeGraph?.repository?.all()
            ?.filter { HomeGrantables.forDevice(it).isNotEmpty() }
            ?.sortedWith(compareBy({ it.room ?: "" }, { it.name }))
            ?.let { devices.addAll(it) }
        adapter.submit(devices)
        emptyView.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun grantCount(device: HomeDevice): Int {
        val all = HomeGrantCodec.decodeOrEmpty(prefs.homeGrants)
        return HomeGrantables.forDevice(device).count { grantable ->
            all.any {
                it.provider == device.key.provider.id && it.nativeId == device.key.nativeId &&
                    it.capability == grantable.capability.name && it.verb == grantable.verb.name
            }
        }
    }

    // ------------------------------------------------------------------
    // Per-action editor
    // ------------------------------------------------------------------

    private fun showGrantsEditor(device: HomeDevice) {
        val grantables = HomeGrantables.forDevice(device)
        if (grantables.isEmpty()) {
            render()
            return
        }
        val existing = HomeGrantCodec.decodeOrEmpty(prefs.homeGrants)
        val actionAdapter = GrantActionAdapter(grantables) { grantable, checked ->
            applyToggle(device, grantable, checked)
        }
        val form = layoutInflater.inflate(R.layout.dialog_home_grants, null, false)
        form.findViewById<TextView>(R.id.homeGrantDeviceName).text = device.name
        form.findViewById<RecyclerView>(R.id.homeGrantActionList).apply {
            layoutManager = LinearLayoutManager(this@HomeGrantsActivity)
            adapter = actionAdapter
        }
        // Seed the visible checked state from what is already persisted.
        actionAdapter.submit(grantables.map { it to isGranted(existing, device, it) })
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_home_grants_title)
            .setView(form)
            .setPositiveButton(android.R.string.ok) { _, _ -> render() }
            .show()
    }

    private fun isGranted(existing: List<HomeGrant>, device: HomeDevice, grantable: Grantable): Boolean =
        existing.any {
            it.provider == device.key.provider.id && it.nativeId == device.key.nativeId &&
                it.capability == grantable.capability.name && it.verb == grantable.verb.name
        }

    /** Add/remove one grant and persist immediately (LIVE). */
    private fun applyToggle(device: HomeDevice, grantable: Grantable, checked: Boolean) {
        val current = HomeGrantCodec.decodeOrEmpty(prefs.homeGrants).toMutableList()
        current.removeAll {
            it.provider == device.key.provider.id && it.nativeId == device.key.nativeId &&
                it.capability == grantable.capability.name && it.verb == grantable.verb.name
        }
        if (checked) {
            current.add(
                HomeGrant(
                    provider = device.key.provider.id,
                    nativeId = device.key.nativeId,
                    capability = grantable.capability.name,
                    verb = grantable.verb.name,
                ),
            )
        }
        prefs.homeGrants = HomeGrantCodec.encode(current)
    }

    // ------------------------------------------------------------------
    // Adapters
    // ------------------------------------------------------------------

    private class HomeGrantAdapter(
        private val grantCount: (HomeDevice) -> Int,
        private val onClick: (HomeDevice) -> Unit,
    ) : RecyclerView.Adapter<HomeGrantAdapter.VH>() {

        private val items = mutableListOf<HomeDevice>()

        fun submit(list: List<HomeDevice>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_home_grant, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val device = items[position]
            holder.name.text = device.name
            val total = HomeGrantables.forDevice(device).size
            holder.summary.text = holder.itemView.context.getString(
                R.string.settings_home_grants_summary,
                grantCount(device),
                total,
            )
            holder.row.setOnClickListener { onClick(device) }
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val row: View = view.findViewById(R.id.homeGrantRow)
            val name: TextView = view.findViewById(R.id.homeGrantName)
            val summary: TextView = view.findViewById(R.id.homeGrantSummary)
        }
    }

    private class GrantActionAdapter(
        private val items: List<Grantable>,
        private val onToggle: (Grantable, Boolean) -> Unit,
    ) : RecyclerView.Adapter<GrantActionAdapter.VH>() {

        private val checked = BooleanArray(items.size)

        fun submit(seeded: List<Pair<Grantable, Boolean>>) {
            seeded.forEachIndexed { index, (_, isChecked) -> if (index < checked.size) checked[index] = isChecked }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_home_grant_action, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val grantable = items[position]
            holder.label.text = holder.itemView.context.getString(HomeLabels.actionRes(grantable.verb))
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = checked[position]
            holder.check.setOnCheckedChangeListener { _, isChecked -> onToggle(grantable, isChecked) }
            holder.itemView.setOnClickListener { holder.check.toggle() }
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val check: MaterialCheckBox = view.findViewById(R.id.homeGrantActionCheck)
            val label: TextView = view.findViewById(R.id.homeGrantActionLabel)
        }
    }
}
