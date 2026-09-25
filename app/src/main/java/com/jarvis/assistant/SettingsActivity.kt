package com.jarvis.assistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.settings.ApplyPolicy
import com.jarvis.assistant.settings.PendingChanges
import com.jarvis.assistant.settings.SettingsCategory
import com.jarvis.assistant.settings.SettingsLinks
import com.jarvis.assistant.ui.EdgeToEdge
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Settings ENTRY screen: the CATEGORY LIST host (settings redesign, FLIP lane).
 *
 * This is what `MainActivity`/`OnboardingActivity` already launch. It is now a
 * thin list — a header, the pending-restart banner, one row per
 * [SettingsCategory] (bound by [SettingsCategoryAdapter]) and an About row —
 * where the old single-scroll Activity held every card inline. Each category
 * opens [SettingsDetailActivity], which owns the controller for that screen.
 *
 * The old 1955-line class is gone; its behaviour lives in the 8 controllers
 * under `settings/controller/` and its host-owned parts (permission launchers,
 * `.ppn`/capture results, graph await, banner) in [SettingsDetailActivity].
 *
 * EDGE-TO-EDGE + IME: the manifest pins `windowSoftInputMode="adjustResize"`,
 * but `adjustResize` is INERT once the window draws behind the system bars, so
 * the Activity reserves the keyboard itself from the `ime()` insets
 * ([EdgeToEdge.pad] with `includeIme = true`). Both settings hosts keep the IME
 * reservation so the list and its detail children behave identically — the
 * detail screens are where the text fields actually are.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeToEdge.enable(this)
        setContentView(R.layout.activity_settings)
        // Reserve the keyboard from the ime() insets (adjustResize is inert
        // edge-to-edge). See the class KDoc.
        EdgeToEdge.pad(findViewById(R.id.settingsRoot), includeIme = true)
        capColumnWidthOnWideScreens()

        // Header close affordance (the theme is NoActionBar).
        findViewById<View>(R.id.settingsCloseButton).setOnClickListener { finish() }
        bindCategoryList()
        bindAboutRow()
        observePendingBanner()
    }

    /** One row per [SettingsCategory.entries]; tapping opens the detail host. */
    private fun bindCategoryList() {
        val list = findViewById<RecyclerView>(R.id.settingsCategoryList)
        list.adapter = SettingsCategoryAdapter { category ->
            startActivity(SettingsDetailActivity.intent(this, category))
        }
    }

    /**
     * The About row lives outside the RecyclerView (it is not a category). There
     * is no dedicated About screen in v1, so the tap opens a compact dialog with
     * the version and the attribution/legal links.
     *
     * The Maps attribution is a LICENCE OBLIGATION (the MapKit terms require the
     * notice + a link wherever map data is used, and this app never renders a
     * map surface), so it is surfaced here as well as inside «Погода и карты».
     * The links come from [SettingsLinks] so the two places cannot drift.
     */
    private fun bindAboutRow() {
        findViewById<View>(R.id.settingsAboutRow).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_about_title)
                .setMessage(
                    getString(R.string.settings_about_version, BuildConfig.VERSION_NAME) +
                        "\n\n" +
                        getString(R.string.maps_attribution),
                )
                .setPositiveButton(R.string.maps_terms_link) { _, _ ->
                    openUrl(SettingsLinks.YANDEX_MAPS_TERMS_URL)
                }
                .setNeutralButton(R.string.maps_open_maps) { _, _ ->
                    openUrl(SettingsLinks.YANDEX_MAPS_URL)
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }
    }

    /**
     * Open an external link. Degrades honestly when no browser can handle it
     * (`getSystemService`-style OEM ROMs vary) instead of crashing the screen.
     */
    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Timber.w(it, "Settings: no activity to open %s", url) }
    }

    /** Subscribe the restart banner to [PendingChanges.changes]. */
    private fun observePendingBanner() {
        val banner = findViewById<View>(R.id.settingsApplyBanner)
        val text = findViewById<TextView>(R.id.settingsApplyBannerText)
        // lifecycleScope keeps this collector alive exactly as long as the
        // screen; no manual cancel needed.
        lifecycleScope.launch {
            PendingChanges.changes.collect { pending -> renderPendingBanner(banner, text, pending) }
        }
    }

    /**
     * The strongest pending policy wins: [ApplyPolicy.APP_RESTART] subsumes a
     * service restart. V1 is instruction-only — the banner never relaunches.
     */
    private fun renderPendingBanner(banner: View, text: TextView, pending: Set<ApplyPolicy>) {
        val messageRes = when {
            ApplyPolicy.APP_RESTART in pending -> R.string.settings_apply_app_restart
            ApplyPolicy.SERVICE_RESTART in pending -> R.string.settings_apply_service_restart
            else -> null
        }
        if (messageRes == null) {
            banner.visibility = View.GONE
        } else {
            banner.visibility = View.VISIBLE
            text.setText(messageRes)
        }
    }

    /**
     * Caps the reading column at [SETTINGS_COLUMN_MAX_WIDTH_DP] only when the
     * window is genuinely wider than that, so it stays centered on a large
     * tablet. The layout keeps `layout_width="match_parent"`: a fixed dp width
     * is NOT clamped by a parent, which centers an oversized child and lets it
     * spill off both edges. Re-applied on every recreation, which covers
     * rotation and window resizes.
     */
    private fun capColumnWidthOnWideScreens() {
        val column = findViewById<View>(R.id.settingsColumn)
        val dm = resources.displayMetrics
        val maxWidthPx = (SETTINGS_COLUMN_MAX_WIDTH_DP * dm.density).toInt()
        if (dm.widthPixels > maxWidthPx) {
            column.layoutParams = column.layoutParams.apply { width = maxWidthPx }
        }
    }

    /**
     * Binds `item_settings_category.xml` to one [SettingsCategory]. The adapter
     * is a pure projection of the enum registry, so adding a screen never
     * touches this class.
     */
    private class SettingsCategoryAdapter(
        private val onOpen: (SettingsCategory) -> Unit,
    ) : RecyclerView.Adapter<SettingsCategoryAdapter.CategoryViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
            val row = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_settings_category, parent, false)
            return CategoryViewHolder(row, onOpen)
        }

        override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
            holder.bind(SettingsCategory.entries[position])
        }

        override fun getItemCount(): Int = SettingsCategory.entries.size

        class CategoryViewHolder(
            row: View,
            private val onOpen: (SettingsCategory) -> Unit,
        ) : RecyclerView.ViewHolder(row) {

            private val title: TextView = row.findViewById(R.id.settingsCategoryTitle)
            private val summary: TextView = row.findViewById(R.id.settingsCategorySummary)

            fun bind(category: SettingsCategory) {
                title.setText(category.titleRes)
                summary.setText(category.subtitleRes)
                itemView.setOnClickListener { onOpen(category) }
            }
        }
    }

    private companion object {
        /** Reading-column cap, mirrored by the detail host and LayoutBoundsTest. */
        const val SETTINGS_COLUMN_MAX_WIDTH_DP = 760
    }
}
