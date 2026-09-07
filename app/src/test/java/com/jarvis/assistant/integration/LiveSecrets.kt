package com.jarvis.assistant.integration

import org.junit.Assume
import java.io.File
import java.util.Properties

/**
 * P2.1: loader for LOCAL-ONLY live-service credentials (REMEDIATION_PLAN
 * Phase 2, owner decision #1). Used by the live smoke tests and the fixture
 * recorder; CI has no credentials, so everything that depends on this skips
 * via JUnit assumptions instead of failing.
 *
 * Search order (MIRRORS loadLiveSecrets in app/build.gradle.kts — keep the
 * two in sync):
 *  1. an explicit path from the `jarvis.secrets.file` system property, then
 *     `local.secrets.properties` found by walking up from `user.dir`
 *     (Gradle/IDE test JVMs start in the `app/` module dir, so the repo root
 *     is found one level up);
 *  2. `JARVIS_*` environment variables (JARVIS_SALUTE_CLIENT_ID, ...);
 *  3. when a key exists in both places, the properties file wins; a key
 *     counts only when non-blank.
 *
 * PRIVACY: values are never logged and never leave this JVM. The [Secrets]
 * data class overrides toString() with a redacted presence-only shape, so an
 * assertion failure or a stack trace can never leak a credential value.
 */
object LiveSecrets {

    /**
     * Same scope literals [com.jarvis.assistant.llm.TokenManager] uses (its
     * copies are private — kept in sync here; a mismatch would send the wrong
     * scope to the OAuth endpoint and fail honestly with HTTP 401).
     */
    const val SCOPE_GIGACHAT = "GIGACHAT_API_PERS"
    const val SCOPE_SALUTE = "SALUTE_SPEECH_PERS"

    /** Explicit-file override for unusual launchers (optional). */
    const val SYSTEM_PROP_FILE = "jarvis.secrets.file"

    /**
     * Redacted credentials container: presence is inspectable, values are not
     * printable.
     */
    data class Secrets(
        val saluteClientId: String?,
        val saluteClientSecret: String?,
        val gigachatClientId: String?,
        val gigachatClientSecret: String?,
    ) {
        override fun toString(): String {
            val present = buildList {
                if (!saluteClientId.isNullOrBlank() && !saluteClientSecret.isNullOrBlank()) add("salute")
                if (!gigachatClientId.isNullOrBlank() && !gigachatClientSecret.isNullOrBlank()) add("gigachat")
            }
            return "LiveSecrets.Secrets(present=$present)" // never the values
        }
    }

    val secrets: Secrets by lazy { load() }

    val hasSalute: Boolean
        get() = !secrets.saluteClientId.isNullOrBlank() && !secrets.saluteClientSecret.isNullOrBlank()

    val hasGigaChat: Boolean
        get() = !secrets.gigachatClientId.isNullOrBlank() && !secrets.gigachatClientSecret.isNullOrBlank()

    val hasAny: Boolean get() = hasSalute || hasGigaChat

    /** JUnit4 assumption gates: a missing key SKIPS the live test, never fails CI. */
    fun assumeSalute(reason: String) =
        Assume.assumeTrue(assumeMessage(reason, "salute"), hasSalute)

    fun assumeGigaChat(reason: String) =
        Assume.assumeTrue(assumeMessage(reason, "gigachat"), hasGigaChat)

    private fun assumeMessage(reason: String, service: String) =
        "$reason skipped: jarvis.$service.* credentials not present " +
            "(provide local.secrets.properties — see local.secrets.properties.example — " +
            "or JARVIS_${service.uppercase()}_* env vars)"

    private fun load(): Secrets {
        val props = Properties()
        candidateFiles().firstOrNull { it.isFile }?.let { file ->
            file.inputStream().use { props.load(it) }
        }

        fun resolve(propKey: String, envName: String): String? =
            props.getProperty(propKey)?.trim()?.takeIf { it.isNotEmpty() }
                ?: System.getenv(envName)?.trim()?.takeIf { it.isNotEmpty() }

        return Secrets(
            saluteClientId = resolve("jarvis.salute.clientId", "JARVIS_SALUTE_CLIENT_ID"),
            saluteClientSecret = resolve("jarvis.salute.clientSecret", "JARVIS_SALUTE_CLIENT_SECRET"),
            gigachatClientId = resolve("jarvis.gigachat.clientId", "JARVIS_GIGACHAT_CLIENT_ID"),
            gigachatClientSecret = resolve("jarvis.gigachat.clientSecret", "JARVIS_GIGACHAT_CLIENT_SECRET"),
        )
    }

    private fun candidateFiles(): List<File> = buildList {
        System.getProperty(SYSTEM_PROP_FILE)?.let { add(File(it)) }
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            add(File(dir, "local.secrets.properties"))
            dir = dir.parentFile
        }
    }
}
