package com.jarvis.assistant

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jarvis.assistant.mcp.McpAccess
import com.jarvis.assistant.mcp.McpServerConfig
import com.jarvis.assistant.mcp.McpServerConfigCodec
import com.jarvis.assistant.mcp.McpServerDecodeResult
import com.jarvis.assistant.mcp.McpServerKind
import com.jarvis.assistant.mcp.McpUrlPolicy
import com.jarvis.assistant.mcp.UrlPolicyResult
import com.jarvis.assistant.mcp.UrlRejection
import com.jarvis.assistant.ui.EdgeToEdge
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.CredentialsStore
import timber.log.Timber

/**
 * MCP server management (multi-server MCP lane, P1-D): list, add, edit, delete,
 * enable/disable. Reached from the MCP settings screen through
 * [com.jarvis.assistant.settings.SettingsHost.openMcpServers].
 *
 * Persistence is the [McpServerConfigCodec] blob in
 * [AppPrefs.mcpServers] (LIVE — the catalog reads it on every use, no restart).
 * The per-server auth secret is kept in the vault through
 * [CredentialsStore.setMcpSecret] and is only ever typed here.
 *
 * Honest failure modes:
 *  - a corrupt stored blob shows the empty list plus a toast, and is left
 *    untouched until the user saves something (it is never silently wiped);
 *  - a URL the kind's policy rejects keeps the dialog open and shows the
 *    reason on the field.
 *
 * There is deliberately NO "test connection" action: no discovery client is
 * wired in P1-D, so a button could only lie. It is omitted rather than faked.
 */
class McpServersActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private lateinit var emptyView: TextView
    private lateinit var adapter: McpServerAdapter
    private val servers = mutableListOf<McpServerConfig>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeToEdge.enable(this)
        setContentView(R.layout.activity_mcp_servers)
        EdgeToEdge.pad(findViewById(R.id.mcpServersRoot))

        prefs = AppPrefs(this)
        emptyView = findViewById(R.id.mcpServerEmpty)
        adapter = McpServerAdapter(
            onToggle = { config, enabled -> upsert(config.copy(enabled = enabled)) },
            onEdit = { config -> showEditor(config) },
            onDelete = { config -> confirmDelete(config) },
        )
        findViewById<RecyclerView>(R.id.mcpServerList).apply {
            layoutManager = LinearLayoutManager(this@McpServersActivity)
            adapter = this@McpServersActivity.adapter
        }
        findViewById<ImageButton>(R.id.mcpBackButton).setOnClickListener { finish() }
        findViewById<View>(R.id.addMcpServerButton).setOnClickListener { showEditor(null) }

        servers.addAll(readServers())
        adapter.submit(servers)
        updateEmpty()
    }

    /** Decode the stored blob; a corrupt payload degrades to empty + a warning. */
    private fun readServers(): List<McpServerConfig> =
        when (val decoded = McpServerConfigCodec.decode(prefs.mcpServers)) {
            is McpServerDecodeResult.Ok -> decoded.servers.sortedBy { it.order }
            McpServerDecodeResult.Empty -> emptyList()
            is McpServerDecodeResult.Invalid -> {
                Timber.w("MCP: stored server list is invalid: %s", decoded.reason)
                Toast.makeText(this, R.string.settings_mcp_list_invalid, Toast.LENGTH_SHORT).show()
                emptyList()
            }
        }

    /** Insert or replace by id and persist immediately. */
    private fun upsert(config: McpServerConfig) {
        val index = servers.indexOfFirst { it.id == config.id }
        if (index >= 0) {
            servers[index] = config
        } else {
            servers.add(config)
        }
        persist()
    }

    /** Delete the server AND clear its vault secret. */
    private fun delete(config: McpServerConfig) {
        servers.removeAll { it.id == config.id }
        CredentialsStore.get().setMcpSecret(config.id, "")
        persist()
        Toast.makeText(this, R.string.settings_mcp_deleted, Toast.LENGTH_SHORT).show()
    }

    private fun persist() {
        prefs.mcpServers = McpServerConfigCodec.encode(servers)
        adapter.submit(servers.sortedBy { it.order })
        updateEmpty()
    }

    private fun updateEmpty() {
        emptyView.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmDelete(config: McpServerConfig) {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_mcp_delete_confirm_title)
            .setMessage(R.string.settings_mcp_delete_confirm_text)
            .setPositiveButton(R.string.settings_mcp_delete) { _, _ -> delete(config) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Add/edit form. `null` adds; a config edits in place (id and order are
     * preserved by [readForm]).
     */
    private fun showEditor(existing: McpServerConfig?) {
        val form = layoutInflater.inflate(R.layout.dialog_mcp_server, null, false)
        form.findViewById<TextInputEditText>(R.id.mcpNameInput)
            .setText(existing?.displayName ?: "")
        val kindGroup = form.findViewById<RadioGroup>(R.id.mcpKindGroup)
        val urlLayout = form.findViewById<TextInputLayout>(R.id.mcpUrlLayout)
        kindGroup.check(kindRadioId(existing?.kind))
        applyUrlHint(kindGroup.checkedRadioButtonId, urlLayout)
        kindGroup.setOnCheckedChangeListener { _, checkedId -> applyUrlHint(checkedId, urlLayout) }
        form.findViewById<TextInputEditText>(R.id.mcpUrlInput)
            .setText(existing?.url ?: "")
        form.findViewById<TextInputEditText>(R.id.mcpAuthHeaderInput)
            .setText(existing?.authHeaderName ?: "")
        form.findViewById<TextInputEditText>(R.id.mcpSecretInput)
            .setText(existing?.let { CredentialsStore.get().mcpSecret(it.id) } ?: "")
        form.findViewById<RadioGroup>(R.id.mcpAccessGroup)
            .check(if (existing?.access == McpAccess.WRITE) R.id.mcpAccessWrite else R.id.mcpAccessRead)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.settings_mcp_add_title else R.string.settings_mcp_edit_title)
            .setView(form)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // Override the positive button so a rejected URL keeps the dialog open.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val config = readForm(form, existing) ?: return@setOnClickListener
                upsert(config)
                Toast.makeText(this, R.string.settings_mcp_saved, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * Validate the form and build the config to store, or return null (with the
     * error shown on the offending field) when it is not acceptable. The URL is
     * checked with the pure [McpUrlPolicy] under the selected kind; the secret
     * is written straight to the vault here, keyed by the config id.
     */
    private fun readForm(form: View, existing: McpServerConfig?): McpServerConfig? {
        val nameLayout = form.findViewById<TextInputLayout>(R.id.mcpNameLayout)
        val urlLayout = form.findViewById<TextInputLayout>(R.id.mcpUrlLayout)
        nameLayout.error = null
        urlLayout.error = null

        val name = form.findViewById<TextInputEditText>(R.id.mcpNameInput).text.toString().trim()
        if (name.isEmpty()) {
            nameLayout.error = getString(R.string.settings_mcp_name_required)
            return null
        }

        val kind = when (form.findViewById<RadioGroup>(R.id.mcpKindGroup).checkedRadioButtonId) {
            R.id.mcpKindLocal -> McpServerKind.LOCAL
            R.id.mcpKindLan -> McpServerKind.LAN
            else -> McpServerKind.REMOTE
        }
        val url = form.findViewById<TextInputEditText>(R.id.mcpUrlInput).text.toString().trim()
        when (val result = McpUrlPolicy.validate(kind, url)) {
            is UrlPolicyResult.Rejected -> {
                urlLayout.error = getString(rejectionRes(result.reason))
                return null
            }
            UrlPolicyResult.Allowed -> Unit
        }

        val access = if (form.findViewById<RadioGroup>(R.id.mcpAccessGroup).checkedRadioButtonId == R.id.mcpAccessWrite) {
            McpAccess.WRITE
        } else {
            McpAccess.READ
        }
        val authHeader = form.findViewById<TextInputEditText>(R.id.mcpAuthHeaderInput).text.toString().trim()
        val secret = form.findViewById<TextInputEditText>(R.id.mcpSecretInput).text.toString().trim()

        val base = existing ?: McpServerConfig.create(
            displayName = name,
            kind = kind,
            url = url,
            access = access,
            authHeaderName = authHeader,
            existing = servers,
        )
        val config = base.copy(
            displayName = name,
            kind = kind,
            url = url,
            access = access,
            authHeaderName = authHeader,
        )
        CredentialsStore.get().setMcpSecret(config.id, secret)
        return config
    }

    /** Checked radio id for a stored kind; a null (new server) defaults to REMOTE. */
    private fun kindRadioId(kind: McpServerKind?): Int = when (kind) {
        McpServerKind.LOCAL -> R.id.mcpKindLocal
        McpServerKind.LAN -> R.id.mcpKindLan
        McpServerKind.REMOTE, null -> R.id.mcpKindRemote
    }

    /** Show the LAN URL hint only while the LAN kind is selected. */
    private fun applyUrlHint(checkedId: Int, urlLayout: TextInputLayout) {
        urlLayout.helperText = if (checkedId == R.id.mcpKindLan) {
            getString(R.string.settings_mcp_url_hint_lan)
        } else {
            null
        }
    }

    /** Exhaustive with no `else`: a new [UrlRejection] is a compile error until mapped. */
    private fun rejectionRes(reason: UrlRejection): Int = when (reason) {
        UrlRejection.MALFORMED -> R.string.settings_mcp_url_reject_malformed
        UrlRejection.UNSUPPORTED_SCHEME -> R.string.settings_mcp_url_reject_unsupported_scheme
        UrlRejection.HTTPS_REQUIRED -> R.string.settings_mcp_url_reject_https_required
        UrlRejection.MISSING_HOST -> R.string.settings_mcp_url_reject_missing_host
        UrlRejection.LOOPBACK_HOST -> R.string.settings_mcp_url_reject_loopback_host
        UrlRejection.PRIVATE_HOST -> R.string.settings_mcp_url_reject_private_host
        UrlRejection.LINK_LOCAL_HOST -> R.string.settings_mcp_url_reject_link_local_host
        UrlRejection.METADATA_HOST -> R.string.settings_mcp_url_reject_metadata_host
        UrlRejection.NON_LOOPBACK_HOST -> R.string.settings_mcp_url_reject_non_loopback_host
        UrlRejection.CROSS_ORIGIN_PRIVATE_HOST -> R.string.settings_mcp_url_reject_cross_origin_private_host
        UrlRejection.PUBLIC_HOST -> R.string.settings_mcp_url_reject_public_host
        UrlRejection.NON_PRIVATE_HOST -> R.string.settings_mcp_url_reject_non_private_host
        UrlRejection.CROSS_ORIGIN_LAN_HOST -> R.string.settings_mcp_url_reject_cross_origin_lan_host
    }

    private class McpServerAdapter(
        private val onToggle: (McpServerConfig, Boolean) -> Unit,
        private val onEdit: (McpServerConfig) -> Unit,
        private val onDelete: (McpServerConfig) -> Unit,
    ) : RecyclerView.Adapter<McpServerAdapter.VH>() {

        private val items = mutableListOf<McpServerConfig>()

        fun submit(list: List<McpServerConfig>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_mcp_server, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val config = items[position]
            holder.name.text = config.displayName
            holder.meta.text = "${config.kind.name} · ${config.access.name}"
            // Detach before setting the checked state: a rebind must not fire
            // the PREVIOUS row's listener against this row's value.
            holder.enabled.setOnCheckedChangeListener(null)
            holder.enabled.isChecked = config.enabled
            holder.enabled.setOnCheckedChangeListener { _, checked ->
                if (checked != config.enabled) onToggle(config, checked)
            }
            holder.edit.setOnClickListener { onEdit(config) }
            holder.delete.setOnClickListener { onDelete(config) }
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.mcpServerName)
            val meta: TextView = view.findViewById(R.id.mcpServerMeta)
            val enabled: MaterialSwitch = view.findViewById(R.id.mcpServerEnabled)
            val edit: ImageButton = view.findViewById(R.id.mcpServerEdit)
            val delete: ImageButton = view.findViewById(R.id.mcpServerDelete)
        }
    }
}
