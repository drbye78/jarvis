package com.jarvis.assistant.settings

/**
 * When a Settings change actually takes effect.
 *
 * The old screen explained this four different ways in ad-hoc hint strings
 * («применится после перезапуска», «перезапустите приложение», …) with no
 * single source of truth; a controller now derives its banner from the policy
 * in [ApplyPolicies] instead of re-wording it per card.
 *
 * - [LIVE] — applied on the very next use; the running graph already reads the
 *   value reactively (next turn / next spoken sentence). No banner.
 * - [SERVICE_RESTART] — the value is SEALED at `AppGraph` construction
 *   (LLM provider type/model, speech backend, AEC mode all own channels, auth
 *   or an `AudioRecord` built at graph build), and the graph is rebuilt on the
 *   next service start. The user must restart the service.
 * - [APP_RESTART] — the value can be applied only once per PROCESS. The MapKit
 *   key is the sole member: `MapKitFactory.setApiKey` may be called once per
 *   process, so a service restart is NOT enough — the whole app must restart.
 *
 * There is deliberately NO async/later policy: [PendingChanges] drives a
 * restart BANNER, so recording a fire-and-forget action (e.g. the vector build
 * or the extraction backfill) as a pending change would tell the user to
 * restart for something a restart cannot affect.
 */
enum class ApplyPolicy {
    LIVE,
    SERVICE_RESTART,
    APP_RESTART,
}
