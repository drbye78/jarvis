package com.jarvis.assistant

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
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
import com.jarvis.assistant.home.providers.tuya.TuyaRegion
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
 * (SERVICE_RESTART — the process-scoped backends read it at graph construction).
 *
 * PROVIDER-AWARE. Home Assistant uses an https URL + a long-lived token; Tuya
 * uses a data-center region + linked-account UID + Access ID/Secret. Secrets are
 * write-only through [CredentialsStore.setHomeSecret]; the region and UID are
 * non-secret and persist in [HomeProviderConfig.metadata]. Yandex is not offered
 * (no backend exists to honour it).
 *
 * Honest failure modes:
 *  - a corrupt stored blob shows the empty list plus a toast, and is left
 *    untouched until the user saves something (it is never silently wiped);
 *  - an invalid HA URL / missing Tuya field keeps the dialog open and shows the
 *    reason on the offending field (the backend additionally rejects it).
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

    /** Delete the connection AND clear its vault secrets. */
    private fun delete(config: HomeProviderConfig) {
        providers.removeAll { it.id == config.id }
        clearSecrets(config)
        persist()
        Toast.makeText(this, R.string.settings_home_provider_deleted, Toast.LENGTH_SHORT).show()
    }

    private fun clearSecrets(config: HomeProviderConfig) {
        val store = CredentialsStore.get()
        when (HomeProviderId.fromId(config.provider)) {
            HomeProviderId.HOME_ASSISTANT -> store.setHomeSecret(config.id, HA_TOKEN_FIELD, "")
            HomeProviderId.TUYA -> {
                store.setHomeSecret(config.id, TUYA_ACCESS_ID_FIELD, "")
                store.setHomeSecret(config.id, TUYA_ACCESS_SECRET_FIELD, "")
            }

            null, HomeProviderId.YANDEX -> Unit
        }
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

    // ------------------------------------------------------------------
    // Add/edit form
    // ------------------------------------------------------------------

    /** Add/edit form. `null` adds; a config edits in place (id/order preserved). */
    private fun showEditor(existing: HomeProviderConfig?) {
        val form = layoutInflater.inflate(R.layout.dialog_home_provider, null, false)
        var selected = existing?.let { HomeProviderId.fromId(it.provider) } ?: HomeProviderId.HOME_ASSISTANT

        val kindLayout = form.findViewById<TextInputLayout>(R.id.homeProviderKindLayout)
        val kindInput = form.findViewById<AutoCompleteTextView>(R.id.homeProviderKindInput)
        bindProviderPicker(kindInput, kindLayout, existing, selected) { chosen ->
            selected = chosen
            renderBlocks(form, chosen)
        }

        bindHaFields(form, existing)
        bindTuyaFields(form, existing)
        renderBlocks(form, selected)

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
        // Override the positive button so a rejected form keeps the dialog open.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val config = readForm(form, existing, selected) ?: return@setOnClickListener
                upsert(config)
                Toast.makeText(this, R.string.settings_home_provider_saved, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun bindProviderPicker(
        input: AutoCompleteTextView,
        layout: TextInputLayout,
        existing: HomeProviderConfig?,
        initial: HomeProviderId,
        onChosen: (HomeProviderId) -> Unit,
    ) {
        val labels = ADDABLE_PROVIDERS.map { providerKindLabel(this, it) }
        input.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        input.setText(providerKindLabel(this, initial), false)
        // The provider is a creation-time choice; changing it on an existing
        // connection would strand the vault keys bound to its id.
        val editable = existing == null
        layout.isEnabled = editable
        input.isEnabled = editable
        if (editable) {
            input.setOnItemClickListener { _, _, position, _ -> onChosen(ADDABLE_PROVIDERS[position]) }
        }
    }

    private fun bindHaFields(form: View, existing: HomeProviderConfig?) {
        val store = CredentialsStore.get()
        form.findViewById<TextInputEditText>(R.id.homeUrlInput).setText(existing?.baseUrl ?: "")
        val status = form.findViewById<TextView>(R.id.homeTokenStatus)
        val hasToken = existing != null && store.homeSecret(existing.id, HA_TOKEN_FIELD).isNotBlank()
        status.text = getString(
            if (hasToken) R.string.settings_home_provider_token_set else R.string.settings_home_provider_token_unset,
        )
        form.findViewById<View>(R.id.homeTokenClearButton).setOnClickListener {
            if (existing != null) store.setHomeSecret(existing.id, HA_TOKEN_FIELD, "")
            form.findViewById<TextInputEditText>(R.id.homeTokenInput).setText("")
            status.text = getString(R.string.settings_home_provider_token_unset)
        }
    }

    private fun bindTuyaFields(form: View, existing: HomeProviderConfig?) {
        val store = CredentialsStore.get()
        val regionInput = form.findViewById<AutoCompleteTextView>(R.id.homeTuyaRegionInput)
        val regionLabels = TuyaRegion.entries.map { regionLabel(this, it) }
        regionInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, regionLabels))
        val region = TuyaRegion.fromId(existing?.metadata?.get(TUYA_REGION_KEY))
        regionInput.setText(regionLabel(this, region), false)
        form.setTag(R.id.homeTuyaRegionInput, region)

        form.findViewById<TextInputEditText>(R.id.homeTuyaUidInput)
            .setText(existing?.metadata?.get(TUYA_UID_KEY) ?: "")
        // Access ID is not a secret (it is the client_id), so it is prefilled;
        // the Access Secret stays write-only like the HA token.
        form.findViewById<TextInputEditText>(R.id.homeTuyaAccessIdInput)
            .setText(existing?.let { store.homeSecret(it.id, TUYA_ACCESS_ID_FIELD) } ?: "")
    }

    /** Show only the selected provider's access block. */
    private fun renderBlocks(form: View, provider: HomeProviderId) {
        form.findViewById<View>(R.id.homeHaBlock).visibility =
            if (provider == HomeProviderId.HOME_ASSISTANT) View.VISIBLE else View.GONE
        form.findViewById<View>(R.id.homeTuyaBlock).visibility =
            if (provider == HomeProviderId.TUYA) View.VISIBLE else View.GONE
    }

    /**
     * Validate the form and build the config to store, or return null (with the
     * error shown on the offending field). Secrets are written straight to the
     * vault keyed by the config id; a blank secret leaves any stored value.
     */
    private fun readForm(form: View, existing: HomeProviderConfig?, provider: HomeProviderId): HomeProviderConfig? =
        when (provider) {
            HomeProviderId.HOME_ASSISTANT -> readHaForm(form, existing)
            HomeProviderId.TUYA -> readTuyaForm(form, existing)
            HomeProviderId.YANDEX -> null // not offered
        }

    private fun readHaForm(form: View, existing: HomeProviderConfig?): HomeProviderConfig? {
        val urlLayout = form.findViewById<TextInputLayout>(R.id.homeUrlLayout)
        urlLayout.error = null
        val url = form.findViewById<TextInputEditText>(R.id.homeUrlInput).text.toString().trim()
        validateHttpsUrl(url)?.let {
            urlLayout.error = getString(it)
            return null
        }
        val config = existing?.copy(baseUrl = url)
            ?: HomeProviderConfig.create(HomeProviderId.HOME_ASSISTANT, url, existing = providers)
        val token = form.findViewById<TextInputEditText>(R.id.homeTokenInput).text.toString().trim()
        if (token.isNotEmpty()) CredentialsStore.get().setHomeSecret(config.id, HA_TOKEN_FIELD, token)
        return config
    }

    private fun readTuyaForm(form: View, existing: HomeProviderConfig?): HomeProviderConfig? {
        val uidLayout = form.findViewById<TextInputLayout>(R.id.homeTuyaUidLayout)
        val idLayout = form.findViewById<TextInputLayout>(R.id.homeTuyaAccessIdLayout)
        val secretLayout = form.findViewById<TextInputLayout>(R.id.homeTuyaSecretLayout)
        uidLayout.error = null
        idLayout.error = null
        secretLayout.error = null

        val uid = form.findViewById<TextInputEditText>(R.id.homeTuyaUidInput).text.toString().trim()
        if (uid.isEmpty()) {
            uidLayout.error = getString(R.string.settings_home_provider_tuya_uid_required)
            return null
        }
        val accessId = form.findViewById<TextInputEditText>(R.id.homeTuyaAccessIdInput).text.toString().trim()
        if (accessId.isEmpty()) {
            idLayout.error = getString(R.string.settings_home_provider_tuya_access_id_required)
            return null
        }
        val store = CredentialsStore.get()
        val secret = form.findViewById<TextInputEditText>(R.id.homeTuyaSecretInput).text.toString().trim()
        val hasStoredSecret = existing != null && store.homeSecret(existing.id, TUYA_ACCESS_SECRET_FIELD).isNotBlank()
        if (secret.isEmpty() && !hasStoredSecret) {
            secretLayout.error = getString(R.string.settings_home_provider_tuya_secret_required)
            return null
        }

        val region = form.getTag(R.id.homeTuyaRegionInput) as? TuyaRegion ?: TuyaRegion.CENTRAL_EUROPE
        val metadata = mapOf(TUYA_REGION_KEY to region.id, TUYA_UID_KEY to uid)
        val config = existing?.copy(metadata = metadata)
            ?: HomeProviderConfig.create(HomeProviderId.TUYA, baseUrl = "", existing = providers)
                .copy(metadata = metadata)
        store.setHomeSecret(config.id, TUYA_ACCESS_ID_FIELD, accessId)
        if (secret.isNotEmpty()) store.setHomeSecret(config.id, TUYA_ACCESS_SECRET_FIELD, secret)
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

    // ------------------------------------------------------------------
    // Adapter
    // ------------------------------------------------------------------

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
            holder.name.text = providerKindLabel(holder.itemView.context, HomeProviderId.fromId(config.provider))
            holder.meta.text = metaText(holder.itemView.context, config)
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

        private fun metaText(context: Context, config: HomeProviderConfig): String =
            when (HomeProviderId.fromId(config.provider)) {
                HomeProviderId.TUYA -> regionLabel(
                    context,
                    TuyaRegion.fromId(config.metadata[TUYA_REGION_KEY]),
                )

                else -> config.baseUrl
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
        const val HA_TOKEN_FIELD = "token"
        const val TUYA_ACCESS_ID_FIELD = "access_id"
        const val TUYA_ACCESS_SECRET_FIELD = "access_secret"
        const val TUYA_REGION_KEY = "region"
        const val TUYA_UID_KEY = "uid"

        /** Providers a user may add; Yandex has no backend yet. */
        val ADDABLE_PROVIDERS = listOf(HomeProviderId.HOME_ASSISTANT, HomeProviderId.TUYA)
    }
}

/** Localized provider name; a null/unknown id echoes the raw stored value. */
private fun providerKindLabel(context: Context, provider: HomeProviderId?): String = when (provider) {
    HomeProviderId.HOME_ASSISTANT -> context.getString(R.string.settings_home_provider_kind_ha)
    HomeProviderId.TUYA -> context.getString(R.string.settings_home_provider_kind_tuya)
    HomeProviderId.YANDEX -> context.getString(R.string.settings_home_provider_kind_yandex)
    null -> ""
}

/** Localized Tuya data-center name. */
private fun regionLabel(context: Context, region: TuyaRegion): String = context.getString(
    when (region) {
        TuyaRegion.CHINA -> R.string.settings_home_region_china
        TuyaRegion.WESTERN_AMERICA -> R.string.settings_home_region_us
        TuyaRegion.EASTERN_AMERICA_AZURE -> R.string.settings_home_region_us_az
        TuyaRegion.CENTRAL_EUROPE -> R.string.settings_home_region_eu
        TuyaRegion.WESTERN_EUROPE_AZURE -> R.string.settings_home_region_eu_az
        TuyaRegion.INDIA -> R.string.settings_home_region_in
        TuyaRegion.SINGAPORE -> R.string.settings_home_region_sg
    },
)
