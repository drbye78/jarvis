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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jarvis.assistant.home.HomeConfigCodec
import com.jarvis.assistant.home.HomeConfigDecodeResult
import com.jarvis.assistant.home.HomeProviderConfig
import com.jarvis.assistant.home.HomeProviderId
import com.jarvis.assistant.ui.EdgeToEdge
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.CredentialsStore
import timber.log.Timber
import java.net.URI

/**
 * Smart-home connection management (R4): list, add, edit, delete, enable/disable.
 * Reached from the HOME settings screen through
 * [com.jarvis.assistant.settings.SettingsHost.openHomeProviders].
 *
 * Persistence is the [HomeConfigCodec] blob in [AppPrefs.homeProviders]
 * (SERVICE_RESTART — the process-scoped backend reads it at graph construction).
 * The access token is kept in the vault through
 * [CredentialsStore.setHomeSecret] and is only ever typed here; it is NEVER
 * read back into the field or displayed.
 *
 * Only Home Assistant is offered in this phase; Yandex/Tuya are shown as
 * «скоро» and cannot be added (no backend exists to honour them).
 *
 * Honest failure modes:
 *  - a corrupt stored blob shows the empty list plus a toast, and is left
 *    untouched until the user saves something (it is never silently wiped);
 *  - a non-https / malformed URL keeps the dialog open and shows the reason on
 *    the field (the backend additionally rejects it at runtime).
 */
class HomeProvidersActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private lateinit var emptyView: TextView
    private lateinit var adapter: HomeProviderAdapter
    private val providers = mutableListOf<HomeProviderConfig>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeToEdge.enable(this)
        setContentView(R.layout.activity_home_providers)
        EdgeToEdge.pad(findViewById(R.id.homeProvidersRoot))

        prefs = AppPrefs(this)
        emptyView = findViewById(R.id.homeProviderEmpty)
        adapter = HomeProviderAdapter(
            onToggle = { config, enabled -> upsert(config.copy(enabled = enabled)) },
            onEdit = { config -> showEditor(config) },
            onDelete = { config -> confirmDelete(config) },
        )
        findViewById<RecyclerView>(R.id.homeProviderList).apply {
            layoutManager = LinearLayoutManager(this@HomeProvidersActivity)
            adapter = this@HomeProvidersActivity.adapter
        }
        findViewById<ImageButton>(R.id.homeBackButton).setOnClickListener { finish() }
        findViewById<View>(R.id.addHomeProviderButton).setOnClickListener { showEditor(null) }

        providers.addAll(readProviders())
        adapter.submit(providers)
        updateEmpty()
    }

    /** Decode the stored blob; a corrupt payload degrades to empty + a warning. */
    private fun readProviders(): List<HomeProviderConfig> =
        when (val decoded = HomeConfigCodec.decode(prefs.homeProviders)) {
            is HomeConfigDecodeResult.Ok -> decoded.configs.sortedBy { it.order }
            HomeConfigDecodeResult.Empty -> emptyList()
            is HomeConfigDecodeResult.Invalid -> {
                Timber.w("Home: stored provider list is invalid: %s", decoded.reason)
                Toast.makeText(this, R.string.settings_home_provider_list_invalid, Toast.LENGTH_SHORT).show()
                emptyList()
            }
        }

    /** Insert or replace by id and persist immediately. */
    private fun upsert(config: HomeProviderConfig) {
        val index = providers.indexOfFirst { it.id == config.id }
        if (index >= 0) {
            providers[index] = config
        } else {
            providers.add(config)
        }
        persist()
    }

    /** Delete the connection AND clear its vault token. */
    private fun delete(config: HomeProviderConfig) {
        providers.removeAll { it.id == config.id }
        CredentialsStore.get().setHomeSecret(config.id, TOKEN_FIELD, "")
        persist()
        Toast.makeText(this, R.string.settings_home_provider_deleted, Toast.LENGTH_SHORT).show()
    }

    private fun persist() {
        prefs.homeProviders = HomeConfigCodec.encode(providers)
        adapter.submit(providers.sortedBy { it.order })
        updateEmpty()
    }

    private fun updateEmpty() {
        emptyView.visibility = if (providers.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmDelete(config: HomeProviderConfig) {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_home_provider_delete_confirm_title)
            .setMessage(R.string.settings_home_provider_delete_confirm_text)
            .setPositiveButton(R.string.delete) { _, _ -> delete(config) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Add/edit form. `null` adds; a config edits in place (id/order preserved). */
    private fun showEditor(existing: HomeProviderConfig?) {
        val form = layoutInflater.inflate(R.layout.dialog_home_provider, null, false)
        val tokenStatus = form.findViewById<TextView>(R.id.homeTokenStatus)
        val credentials = CredentialsStore.get()

        form.findViewById<TextInputEditText>(R.id.homeUrlInput)
            .setText(existing?.baseUrl ?: "")

        val hasToken = existing != null && credentials.homeSecret(existing.id, TOKEN_FIELD).isNotBlank()
        tokenStatus.text = getString(
            if (hasToken) R.string.settings_home_provider_token_set else R.string.settings_home_provider_token_unset,
        )
        form.findViewById<View>(R.id.homeTokenClearButton).setOnClickListener {
            if (existing != null) {
                credentials.setHomeSecret(existing.id, TOKEN_FIELD, "")
            }
            form.findViewById<TextInputEditText>(R.id.homeTokenInput).setText("")
            tokenStatus.text = getString(R.string.settings_home_provider_token_unset)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(
                if (existing == null) {
                    R.string.settings_home_provider_add_title
                } else {
                    R.string.settings_home_provider_edit_title
                },
            )
            .setView(form)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // Override the positive button so a rejected URL keeps the dialog open.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val config = readForm(form, existing) ?: return@setOnClickListener
                upsert(config)
                Toast.makeText(this, R.string.settings_home_provider_saved, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * Validate the form and build the config to store, or return null (with the
     * error shown on the offending field) when it is not acceptable. The token
     * is written straight to the vault here, keyed by the config id; a blank
     * token field leaves any stored token unchanged.
     */
    private fun readForm(form: View, existing: HomeProviderConfig?): HomeProviderConfig? {
        val urlLayout = form.findViewById<TextInputLayout>(R.id.homeUrlLayout)
        urlLayout.error = null

        val url = form.findViewById<TextInputEditText>(R.id.homeUrlInput).text.toString().trim()
        validateHttpsUrl(url)?.let {
            urlLayout.error = getString(it)
            return null
        }

        // Only Home Assistant is implemented; adding anything else is refused.
        val base = existing ?: HomeProviderConfig.create(HomeProviderId.HOME_ASSISTANT, url, existing = providers)
        val config = base.copy(baseUrl = url)

        val token = form.findViewById<TextInputEditText>(R.id.homeTokenInput).text.toString().trim()
        if (token.isNotEmpty()) {
            CredentialsStore.get().setHomeSecret(config.id, TOKEN_FIELD, token)
        }
        return config
    }

    /**
     * Require an absolute `https://…` URL with a host. Returns an error resource
     * or null. Mirrors the backend's cleartext rejection so the failure is
     * caught in the UI, not only at runtime.
     */
    private fun validateHttpsUrl(raw: String): Int? {
        if (raw.isBlank()) return R.string.settings_home_provider_url_invalid
        val uri = try {
            URI(raw)
        } catch (e: Exception) {
            Timber.d("Home: malformed URL (%s)", e.javaClass.simpleName)
            return R.string.settings_home_provider_url_invalid
        }
        if (uri.scheme?.lowercase() != "https") return R.string.settings_home_provider_url_https_required
        if (uri.host.isNullOrBlank()) return R.string.settings_home_provider_url_invalid
        return null
    }

    private class HomeProviderAdapter(
        private val onToggle: (HomeProviderConfig, Boolean) -> Unit,
        private val onEdit: (HomeProviderConfig) -> Unit,
        private val onDelete: (HomeProviderConfig) -> Unit,
    ) : RecyclerView.Adapter<HomeProviderAdapter.VH>() {

        private val items = mutableListOf<HomeProviderConfig>()

        fun submit(list: List<HomeProviderConfig>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_home_provider, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val config = items[position]
            holder.name.text = providerName(holder.itemView, config)
            holder.meta.text = config.baseUrl
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

        private fun providerName(view: View, config: HomeProviderConfig): String {
            val provider = HomeProviderId.fromId(config.provider)
            return when (provider) {
                HomeProviderId.HOME_ASSISTANT -> view.context.getString(R.string.settings_home_provider_kind_ha)
                HomeProviderId.YANDEX -> view.context.getString(R.string.settings_home_provider_kind_yandex)
                HomeProviderId.TUYA -> view.context.getString(R.string.settings_home_provider_kind_tuya)
                null -> config.provider
            }
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.homeProviderName)
            val meta: TextView = view.findViewById(R.id.homeProviderMeta)
            val enabled: MaterialSwitch = view.findViewById(R.id.homeProviderEnabled)
            val edit: ImageButton = view.findViewById(R.id.homeProviderEdit)
            val delete: ImageButton = view.findViewById(R.id.homeProviderDelete)
        }
    }

    private companion object {
        const val TOKEN_FIELD = "token"
    }
}
