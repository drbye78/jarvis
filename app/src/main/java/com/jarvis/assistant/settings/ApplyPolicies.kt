package com.jarvis.assistant.settings

import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.SecretVault

/**
 * The single source of truth for "when does this setting apply?" (settings
 * redesign foundation). Replaces the four scattered hint strings the old
 * screen used.
 *
 * KEYS: the frozen control→destination table names a row by the [AppPrefs] /
 * `CredentialsStore` PROPERTY name (`providerType`, `mapKitApiKey`, …), while
 * the underlying storage key is the constant value (`provider_type`,
 * `mapkit_api_key`). [of] accepts EITHER: it normalizes both by lowercasing
 * and dropping underscores, so `of("providerType")` and
 * `of(AppPrefs.KEY_PROVIDER)` resolve identically. That keeps call sites free
 * to pass whichever they hold without a second alias table.
 *
 * The storage keys are referenced through their constants (never raw literals)
 * so a key rename cannot silently detach a policy.
 *
 * Anything not listed defaults to [ApplyPolicy.LIVE] — the conservative
 * "applies on next use" answer for the many reactive prefs, and an honest
 * default rather than a crash for an unknown key.
 */
object ApplyPolicies {

    private val byKey: Map<String, ApplyPolicy> = mapOf(
        // SEALED AT AppGraph CONSTRUCTION → the graph must be rebuilt, which
        // happens on the next service start.
        normalize(AppPrefs.KEY_PROVIDER) to ApplyPolicy.SERVICE_RESTART,
        normalize(AppPrefs.KEY_GIGACHAT_MODEL) to ApplyPolicy.SERVICE_RESTART,
        normalize(AppPrefs.KEY_YANDEX_MODEL) to ApplyPolicy.SERVICE_RESTART,
        normalize(AppPrefs.KEY_YANDEX_FOLDER_ID) to ApplyPolicy.SERVICE_RESTART,
        normalize(AppPrefs.KEY_OPENAI_URL) to ApplyPolicy.SERVICE_RESTART,
        normalize(AppPrefs.KEY_OPENAI_MODEL) to ApplyPolicy.SERVICE_RESTART,
        normalize(AppPrefs.KEY_SPEECH_BACKEND) to ApplyPolicy.SERVICE_RESTART,
        normalize(AppPrefs.KEY_AEC_MODE) to ApplyPolicy.SERVICE_RESTART,
        // The [OI]-compatible key is baked into the client at graph construction.
        normalize(SecretVault.KEY_OPENAI_API_KEY) to ApplyPolicy.SERVICE_RESTART,
        // MapKit may set its API key only ONCE PER PROCESS → a service restart
        // is not enough (the AppGraph, and therefore the initializer, is
        // rebuilt inside the same process).
        normalize(SecretVault.KEY_MAPKIT_API_KEY) to ApplyPolicy.APP_RESTART,
    )

    /** The policy for [prefKey] (property name OR storage key); unknown ⇒ [ApplyPolicy.LIVE]. */
    fun of(prefKey: String): ApplyPolicy = byKey[normalize(prefKey)] ?: ApplyPolicy.LIVE

    /** Property-name and storage-key forms collapse to one shape for lookup. */
    private fun normalize(key: String): String = key.lowercase().replace("_", "")
}
