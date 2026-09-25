package com.jarvis.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import com.jarvis.assistant.audio.WakeWordImport
import com.jarvis.assistant.audio.aec.AecMode
import com.jarvis.assistant.di.AppGraph
import com.jarvis.assistant.di.GraphHolder
import com.jarvis.assistant.di.GraphReadyOutcome
import com.jarvis.assistant.di.awaitGraphReady
import com.jarvis.assistant.settings.ApplyPolicy
import com.jarvis.assistant.settings.PendingChanges
import com.jarvis.assistant.settings.SettingsCallbacks
import com.jarvis.assistant.settings.SettingsCallbacksReal
import com.jarvis.assistant.settings.SettingsCallbacksStub
import com.jarvis.assistant.settings.SettingsCategory
import com.jarvis.assistant.settings.SettingsController
import com.jarvis.assistant.settings.SettingsControllerFactory
import com.jarvis.assistant.settings.SettingsHost
import com.jarvis.assistant.ui.EdgeToEdge
import com.jarvis.assistant.ui.FieldErrorRenderer
import com.jarvis.assistant.ui.FieldValidation
import com.jarvis.assistant.util.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The ONE reusable Settings DETAIL host (settings redesign, FLIP lane).
 *
 * It shows one [SettingsCategory] at a time: the category is selected by the
 * [EXTRA_CATEGORY] extra, its `category.layoutRes` screen is inflated into
 * `settingsDetailContent`, and its controller (built by
 * [SettingsControllerFactory]) is bound to that screen root. Adding a screen
 * therefore never touches this class — only the registry + the factory change.
 *
 * This is the ONLY [SettingsHost] implementor. That is deliberate: navigation,
 * permission launchers, Activity results and lifecycle stay in ONE place, and a
 * controller reaches the outside world only through the narrow host interface.
 *
 * HOST-OWNED (not a controller), per the FLIP plan's behavioural checklist:
 *  - [awaitAssistantGraph]: the 45 s bounded bootstrap wait
 *    ([GRAPH_READY_TIMEOUT_MS]) preserved verbatim from the old Activity, so a
 *    mid-bootstrap service reads as «запускается», never as «не запущен»;
 *  - the weather-location permission launcher, the custom `.ppn` picker
 *    ([PPN_REQUEST]) and the playback-capture consent ([CAPTURE_REQUEST]);
 *  - the pending-restart banner (driven by [PendingChanges.changes]);
 *  - the 760 dp column cap and the edge-to-edge IME padding.
 *
 * `.ppn` and capture results: the host writes the wake-word prefs / starts the
 * capture lane and then calls the controller's [SettingsController.onResume],
 * which re-reads the prefs and the live capture state. The frozen seam has no
 * Activity-result hook, so re-reading in `onResume` IS the contract.
 *
 * Edge-to-edge: `adjustResize` is inert once the window draws behind the bars,
 * so the host reserves the keyboard from `ime()` insets ([EdgeToEdge.pad] with
 * `includeIme = true`). The manifest pins `adjustResize` so the system cannot
 * choose `adjustPan` instead.
 */
class SettingsDetailActivity : AppCompatActivity(), SettingsHost {

    private lateinit var appPrefs: AppPrefs

    /**
     * Real callbacks once [appPrefs] is ready; the stub is the safe default so a
     * pre-init tap is logged instead of crashing.
     */
    private var callbacks: SettingsCallbacks = SettingsCallbacksStub

    private var controller: SettingsController? = null

    /**
     * Location-permission prompt. Registered as a field initializer (before
     * STARTED, the documented safe point). On return the weather controller
     * re-reads the grant through `onResume`.
     */
    private val weatherPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            controller?.onResume()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeToEdge.enable(this)
        setContentView(R.layout.activity_settings_detail)
        // Text fields live in the category screens: reserve the keyboard too.
        EdgeToEdge.pad(findViewById(R.id.settingsDetailRoot), includeIme = true)
        capColumnWidthOnWideScreens()

        val category = categoryFromIntent()
        if (category == null) {
            // No/invalid category extra: nothing to show. Finish rather than
            // render a blank host (defensive — the list host always passes one).
            finish()
            return
        }

        appPrefs = AppPrefs(this)
        callbacks = SettingsCallbacksReal(appPrefs, lifecycleScope)

        findViewById<TextView>(R.id.settingsDetailTitle).setText(category.titleRes)
        findViewById<View>(R.id.settingsDetailBack).setOnClickListener { finishScreen() }

        // Inflate the category screen into the content slot, then bind its
        // controller to that screen root (id scoping stays local to the screen).
        val content = findViewById<ViewGroup>(R.id.settingsDetailContent)
        val screen = layoutInflater.inflate(category.layoutRes, content, false)
        content.addView(screen)
        controller = SettingsControllerFactory.create(category, callbacks, appPrefs, this)
            .also { it.bind(screen) }

        observePendingBanner()
    }

    override fun onResume() {
        super.onResume()
        // Re-read the screen's prefs on every resume (the seam's contract): the
        // location grant may have changed in system settings, and the `.ppn` /
        // capture results are consumed here rather than by an Activity hook.
        controller?.onResume()
    }

    override fun onStop() {
        controller?.onStop()
        super.onStop()
    }

    // ------------------------------------------------------------------
    // SettingsHost — the ONLY surface a controller may use
    // ------------------------------------------------------------------

    /**
     * Awaits the live graph (bounded); toasts the honest state and returns null
     * when the service is stopped or still starting. Ported verbatim from the
     * old Activity, including the post-await liveness re-validation.
     */
    override suspend fun awaitAssistantGraph(): AppGraph? {
        val service = GraphHolder.service
        return when (
            val outcome = awaitGraphReady(
                graphNow = GraphHolder.graph,
                ready = service?.graphReady,
                serviceAlive = service != null,
                timeoutMs = GRAPH_READY_TIMEOUT_MS,
            )
        ) {
            is GraphReadyOutcome.Ready -> {
                // The service can die while we were awaiting; re-validate so a
                // completed-but-shutdown deferred is never mistaken for a live graph.
                val stillAlive = GraphHolder.service != null && GraphHolder.graph === outcome.graph
                if (stillAlive) {
                    outcome.graph
                } else {
                    toast(R.string.voice_service_not_running)
                    null
                }
            }
            GraphReadyOutcome.Bootstrapping -> {
                toast(R.string.service_bootstrapping_toast)
                null
            }
            GraphReadyOutcome.Stopped -> {
                toast(R.string.voice_service_not_running)
                null
            }
        }
    }

    override fun requestWeatherLocationPermission() {
        weatherPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
        )
    }

    override fun importCustomPpn() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, PPN_REQUEST)
    }

    override fun requestPlaybackCapture() {
        // MediaProjection consent → the graph's capture lane (SOFTWARE mode
        // only; the service guards it too).
        val consent = GraphHolder.graph?.playbackCapture?.createConsentIntent()
        if (consent == null) {
            toast(R.string.aec_hw_probe_unavailable)
        } else {
            @Suppress("DEPRECATION")
            startActivityForResult(consent, CAPTURE_REQUEST)
        }
    }

    override fun openUrl(url: String) {
        // startActivity is wrapped (the AndroidMediaGateway precedent) rather
        // than pre-checked with resolveActivity: on API 30+ package visibility
        // can hide a browser from resolveActivity while the start itself would
        // still succeed, so a pre-check could show a false "no app" message.
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { error ->
                Timber.w(error, "Settings: no activity for %s", url)
                toast(R.string.maps_link_unavailable)
            }
    }

    override fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    }

    override fun renderFieldErrors(errors: List<FieldValidation.FieldError>) {
        FieldErrorRenderer.render(errors, fieldLayouts(), this)
    }

    override fun markPending(policy: ApplyPolicy) {
        PendingChanges.mark(policy)
    }

    override fun openCategory(category: SettingsCategory) {
        startActivity(intent(this, category))
    }

    override fun openMemoryInspector() {
        startActivity(Intent(this, MemoryInspectorActivity::class.java))
    }

    override fun finishScreen() {
        finish()
    }

    // ------------------------------------------------------------------
    // .ppn / playback-capture results (host-owned)
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAPTURE_REQUEST && resultCode == RESULT_OK && data != null) {
            // AEC Phase B: feed the consented projection to the running graph's
            // playback-capture lane (SOFTWARE mode only).
            val graph = GraphHolder.graph
            if (graph == null) {
                // F8: the consent returned after the service was stopped — the
                // old toast claimed "credentials saved", which was wrong.
                toast(R.string.aec_service_not_running)
            } else if (graph.aecMode != AecMode.SOFTWARE) {
                toast(R.string.aec_hw_probe_unavailable)
            } else {
                // Routed through the service so the mediaProjection FGS type is
                // promoted before the capture AudioRecord is built — Android 10+
                // throws SecurityException without the type.
                GraphHolder.service?.startPlaybackCapture(resultCode, data)
            }
            // Mirror the live capture state onto the switch (onResume re-reads it).
            controller?.onResume()
            return
        }
        if (requestCode == PPN_REQUEST && resultCode == RESULT_OK && data != null) {
            val uri = data.data ?: return
            // L2: only a .ppn file is valid.
            if (!WakeWordImport.isPpnFileName(uri.lastPathSegment)) {
                toast(R.string.error_ppn_file)
                return
            }
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            // A provider may hand out a non-persistable grant; that is not fatal
            // for a one-shot copy, so it is reported and the copy proceeds.
            // (L4: the URI is only read once, below, so the grant is released
            // again immediately after.)
            runCatching { contentResolver.takePersistableUriPermission(uri, takeFlags) }
                .onFailure { Timber.w("wake-word import: the URI grant is not persistable") }
            // L3: copy into a private app file; the copy lands in a temp file and
            // replaces the model only once verified, and the outcome is reported
            // instead of silently skipped.
            val destination = getFileStreamPath(WakeWordImport.PPN_FILE_NAME)
            val outcome = WakeWordImport.install(
                source = runCatching { contentResolver.openInputStream(uri) }.getOrNull(),
                destination = destination,
                openOutput = { file -> openFileOutput(file.name, Context.MODE_PRIVATE) },
            )
            when (outcome) {
                is WakeWordImport.Outcome.Copied -> {
                    appPrefs.customWakeWordPath = destination.absolutePath
                    appPrefs.wakeWordModel = "custom_user"
                    // Reconfigure off the UI thread.
                    lifecycleScope.launch(Dispatchers.Default) {
                        GraphHolder.graph?.reconfigureWakeWord()
                    }
                    // The LISTENING controller re-reads the wake-word prefs in
                    // onResume; the frozen seam has no Activity-result hook.
                    controller?.onResume()
                }

                WakeWordImport.Outcome.NoSource,
                is WakeWordImport.Outcome.Failed,
                -> {
                    Timber.w("wake-word import %s", outcome.describe())
                    toast(R.string.error_ppn_import)
                }
            }
            runCatching { contentResolver.releasePersistableUriPermission(uri, takeFlags) }
                .onFailure { Timber.w("wake-word import: releasing the URI grant failed") }
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** The category named by the [EXTRA_CATEGORY] extra, or null when absent/invalid. */
    private fun categoryFromIntent(): SettingsCategory? {
        val name = intent.getStringExtra(EXTRA_CATEGORY) ?: return null
        return SettingsCategory.entries.firstOrNull { it.name == name }
    }

    /**
     * The TextInputLayouts this host can attach field errors to. Only the
     * screens that own a given field contribute it; a missing layout is simply
     * absent from the map (FieldErrorRenderer skips unknown fields), so a
     * BRAIN-side [OI] key error lands on ACCOUNTS' field when that screen is
     * open and is harmlessly dropped otherwise.
     */
    private fun fieldLayouts(): Map<FieldValidation.Field, TextInputLayout> {
        val root = findViewById<View>(R.id.settingsDetailContent)
        return buildMap {
            root.findViewById<TextInputLayout>(R.id.openAiBaseUrlLayout)
                ?.let { put(FieldValidation.Field.OPENAI_BASE_URL, it) }
            root.findViewById<TextInputLayout>(R.id.openAiApiKeyLayout)
                ?.let { put(FieldValidation.Field.OPENAI_API_KEY, it) }
            root.findViewById<TextInputLayout>(R.id.saluteIdLayout)
                ?.let { put(FieldValidation.Field.SALUTE_ID, it) }
            root.findViewById<TextInputLayout>(R.id.saluteSecretLayout)
                ?.let { put(FieldValidation.Field.SALUTE_SECRET, it) }
            root.findViewById<TextInputLayout>(R.id.gigaChatIdLayout)
                ?.let { put(FieldValidation.Field.GIGACHAT_ID, it) }
            root.findViewById<TextInputLayout>(R.id.gigaChatSecretLayout)
                ?.let { put(FieldValidation.Field.GIGACHAT_SECRET, it) }
            root.findViewById<TextInputLayout>(R.id.yandexApiKeyLayout)
                ?.let { put(FieldValidation.Field.YANDEX_API_KEY, it) }
        }
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
     * window is genuinely wider, so it stays centered instead of spanning a
     * large tablet. The layout keeps `layout_width="match_parent"`: a fixed dp
     * width is NOT clamped by a ScrollView, which centers an oversized child and
     * lets it spill off both edges. Re-applied on every recreation, which covers
     * rotation and window resizes.
     */
    private fun capColumnWidthOnWideScreens() {
        val column = findViewById<View>(R.id.settingsDetailColumn)
        val dm = resources.displayMetrics
        val maxWidthPx = (SETTINGS_COLUMN_MAX_WIDTH_DP * dm.density).toInt()
        if (dm.widthPixels > maxWidthPx) {
            column.layoutParams = column.layoutParams.apply { width = maxWidthPx }
        }
    }

    companion object {
        /** The [SettingsCategory.name] this detail screen shows. */
        const val EXTRA_CATEGORY = "com.jarvis.assistant.settings.EXTRA_CATEGORY"

        /** Build the Intent that opens [category]'s detail screen. */
        fun intent(context: Context, category: SettingsCategory): Intent =
            Intent(context, SettingsDetailActivity::class.java).putExtra(EXTRA_CATEGORY, category.name)

        /** Custom `.ppn` picker request code (preserved verbatim from the old Activity). */
        const val PPN_REQUEST = 1002

        /** Playback-capture consent request code (preserved verbatim). */
        const val CAPTURE_REQUEST = 1003

        /** Reading column cap, identical to the list host and the old Activity. */
        const val SETTINGS_COLUMN_MAX_WIDTH_DP = 760

        /**
         * Bounded wait for the graph bootstrap (~1 min worst case on
         * Kirin-class devices). 45 s keeps the await shorter than the user's
         * patience while comfortably covering a normal build.
         */
        const val GRAPH_READY_TIMEOUT_MS = 45_000L
    }
}
