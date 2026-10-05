package com.jarvis.assistant.home

import kotlinx.serialization.Serializable

/**
 * One persisted user authorization: "device X may do capability C via verb V".
 *
 * The provider/nativeId/capability/verb are stored as raw strings so the grant
 * survives a config export/import and stays decoupled from the enum
 * definitions. Renaming an enum constant invalidates matching grants (they stop
 * matching) — that fails CLOSED, which is the intended direction.
 */
@Serializable
data class HomeGrant(
    val provider: String,
    val nativeId: String,
    val capability: String,
    val verb: String,
) {
    companion object {
        /** The grant that would authorize [action]. */
        fun of(action: HomeAction): HomeGrant = HomeGrant(
            provider = action.key.provider.id,
            nativeId = action.key.nativeId,
            capability = action.capability.name,
            verb = action.verb.name,
        )
    }
}

/**
 * The in-memory grant set. [allows] matches the EXACT
 * `(provider, nativeId) + capability + verb` tuple: no wildcard, no
 * capability-subsumption. A missing/extra field is a denial.
 */
class HomeGrantStore(grants: List<HomeGrant> = emptyList()) {

    private val granted: Set<HomeGrant> = grants.toSet()

    /** The configured grants, deterministic order preserved. */
    fun all(): List<HomeGrant> = granted.toList()

    fun allows(action: HomeAction): Boolean = HomeGrant.of(action) in granted
}
