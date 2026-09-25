package com.jarvis.assistant.settings

/**
 * One persisted setting and where the redesign puts it.
 *
 * [key] is the [com.jarvis.assistant.util.AppPrefs] / `CredentialsStore`
 * property name (the frozen control→destination table names rows that way), so
 * [SettingsInventoryTest] can align this registry with the real accessors
 * mechanically.
 */
data class SettingEntry(
    val key: String,
    val category: SettingsCategory,
    val essential: Boolean,
    val policy: ApplyPolicy,
)

/**
 * The ANTI-DROP registry (settings redesign foundation).
 *
 * The strangler rewrite is free to move every control; what it must never do
 * is silently LOSE one. This list names every persisted setting exactly once
 * and [SettingsInventoryTest] reflects over `AppPrefs` + `CredentialsStore` to
 * prove the registry still covers all of them — a dropped setting fails the
 * build instead of shipping as an unreachable pref.
 *
 * [nonSettingsKeys] is the explicit allow-list of persisted members that are
 * state, not user-facing settings (`onboarded`, `userStopped`); anything not in
 * either collection is a failure.
 *
 * [policy] is derived from [ApplyPolicies] rather than restated, so the two
 * files cannot drift.
 */
object SettingsInventory {

    /** Every real setting, keyed by its accessor property name. */
    val entries: List<SettingEntry> = listOf(
        // --- BRAIN (LLM provider) ---
        entry("providerType", SettingsCategory.BRAIN, essential = true),
        entry("gigaChatModel", SettingsCategory.BRAIN, essential = true),
        entry("yandexModel", SettingsCategory.BRAIN, essential = true),
        entry("yandexFolderId", SettingsCategory.BRAIN, essential = false),
        entry("openAiBaseUrl", SettingsCategory.BRAIN, essential = false),
        entry("openAiModel", SettingsCategory.BRAIN, essential = false),

        // --- SPEECH (backends + voice) ---
        entry("speechBackend", SettingsCategory.SPEECH, essential = true),
        entry("ttsVoice", SettingsCategory.SPEECH, essential = true),
        entry("yandexTtsVoice", SettingsCategory.SPEECH, essential = true),
        entry("yandexTtsRole", SettingsCategory.SPEECH, essential = false),

        // --- LISTENING (wake word + echo cancellation) ---
        entry("voiceStopEnabled", SettingsCategory.LISTENING, essential = true),
        entry("followUpEnabled", SettingsCategory.LISTENING, essential = true),
        entry("followUpWindowMs", SettingsCategory.LISTENING, essential = false),
        entry("wakeWordEngine", SettingsCategory.LISTENING, essential = false),
        entry("wakeWordModel", SettingsCategory.LISTENING, essential = false),
        entry("customWakeWordPath", SettingsCategory.LISTENING, essential = false),
        entry("sherpaCustomKeyword", SettingsCategory.LISTENING, essential = false),
        entry("wakeSensitivity", SettingsCategory.LISTENING, essential = false),
        entry("aecMode", SettingsCategory.LISTENING, essential = false),

        // --- WEATHER_MAPS ---
        entry("weatherLocation", SettingsCategory.WEATHER_MAPS, essential = true),
        entry("mapKitApiKey", SettingsCategory.WEATHER_MAPS, essential = false),

        // --- MEMORY ---
        entry("memoryEnabled", SettingsCategory.MEMORY, essential = true),
        entry("memoryAutoExtract", SettingsCategory.MEMORY, essential = false),
        entry("memoryCloudEnabled", SettingsCategory.MEMORY, essential = false),
        entry("memorySensitiveVisible", SettingsCategory.MEMORY, essential = false),
        entry("memoryEmbedder", SettingsCategory.MEMORY, essential = false),

        // --- PROACTIVITY ---
        entry("behaviorEnabled", SettingsCategory.PROACTIVITY, essential = true),
        entry("behaviorQuietStart", SettingsCategory.PROACTIVITY, essential = false),
        entry("behaviorQuietEnd", SettingsCategory.PROACTIVITY, essential = false),
        entry("behaviorDailyQuota", SettingsCategory.PROACTIVITY, essential = false),

        // --- MUSIC ---
        entry("preferredMusicPlayer", SettingsCategory.MUSIC, essential = true),

        // --- ACCOUNTS (keys live on ACCOUNTS; service-scoped ones carry the policy) ---
        entry("openAiApiKey", SettingsCategory.ACCOUNTS, essential = false),
        entry("picovoiceKey", SettingsCategory.ACCOUNTS, essential = false),
        entry("saluteClientId", SettingsCategory.ACCOUNTS, essential = false),
        entry("saluteClientSecret", SettingsCategory.ACCOUNTS, essential = false),
        entry("yandexApiKey", SettingsCategory.ACCOUNTS, essential = false),
        entry("gigaChatClientId", SettingsCategory.ACCOUNTS, essential = false),
        entry("gigaChatClientSecret", SettingsCategory.ACCOUNTS, essential = false),
    )

    /** Persisted members that are lifecycle state, not user-facing settings. */
    val nonSettingsKeys: Set<String> = setOf("onboarded", "userStopped")
}

/** One registry row; policy comes from [ApplyPolicies] so the two never drift. */
private fun entry(key: String, category: SettingsCategory, essential: Boolean): SettingEntry =
    SettingEntry(key = key, category = category, essential = essential, policy = ApplyPolicies.of(key))
