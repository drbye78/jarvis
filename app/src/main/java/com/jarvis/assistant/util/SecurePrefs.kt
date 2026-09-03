package com.jarvis.assistant.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Encrypted storage for tokens, API keys and other secrets.
 *
 * A1 (startup crash loop): MasterKey / EncryptedSharedPreferences creation
 * is documented to throw when the Android Keystore master key is invalidated
 * (backup/restore, OEM keystore corruption). This object is hit from
 * JarvisApplication.onCreate via CredentialsStore.init — an uncaught throw
 * there kills the process at startup, and every following launch throws
 * again until the user clears app data. The standard mitigation is applied:
 * on creation failure, delete the undecryptable store, retry once (the user
 * re-enters credentials — the accepted trade-off vs. a permanent crash
 * loop), and only surface the failure if the retry also fails.
 */
object SecurePrefs {
    private val cache = ConcurrentHashMap<String, SharedPreferences>()

    fun get(context: Context): SharedPreferences {
        val appContext = context.applicationContext
        val key = appContext.packageName
        cache[key]?.let { return it }
        // D4: getOrPut evaluates the factory BEFORE putIfAbsent, so two
        // racing first-callers (main thread via CredentialsStore.init vs a
        // background graph build) both constructed a full encrypted store.
        // Double-checked synchronized creation keeps it to one.
        synchronized(this) {
            cache[key]?.let { return it }
            val created = createOrReset(appContext)
            cache[key] = created
            return created
        }
    }

    private fun create(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            "jarvis_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Create the encrypted store; on the classic Keystore-invalidation
     * failure, delete the corrupt file and retry once. If the retry also
     * fails the exception propagates — but the crash loop is broken for
     * the common corruption case, which a delete+recreate reliably fixes.
     */
    private fun createOrReset(context: Context): SharedPreferences =
        try {
            create(context)
        } catch (first: Exception) {
            try {
                context.deleteSharedPreferences("jarvis_secure")
            } catch (_: Exception) {
                // Best-effort cleanup; the retry below is the real fix.
            }
            create(context).also {
                // The previously stored secrets are gone — surface it loudly
                // so the failure is diagnosable in the rotating file log.
                println(
                    "SecurePrefs: encrypted store was unreadable (${first.javaClass.simpleName}) " +
                        "and has been reset — saved credentials must be re-entered",
                )
            }
        }
}
