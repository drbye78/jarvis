package com.jarvis.assistant.llm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.assistant.util.CredentialsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * DEVICE-ONLY smoke coverage for the REAL cloud credential path. Not run in the
 * CI gate (which only compiles this source set); it is skipped unless the device
 * has been provisioned with credentials — so it never contains or requires a
 * secret in the repo.
 *
 * Provision (app-external files dir; readable only by this app):
 * ```
 * printf '%s\n' "$SALUTE_SPEECH_API_CLIENT_ID" "$SALUTE_SPEECH_API_KEY" \
 *                "$GIGACHAT_API_CLIENT_ID" "$GIGACHAT_API_KEY" > /tmp/creds.txt
 * adb push /tmp/creds.txt /sdcard/Android/data/com.jarvis.assistant/files/creds.txt
 * rm /tmp/creds.txt
 * ```
 * Remove the pushed file afterwards to revoke it.
 *
 * What this proves that the JVM suite cannot:
 *  - [CredentialsStore] round-trips through the AndroidKeyStore-backed
 *    [com.jarvis.assistant.util.KeystoreVault] on real hardware;
 *  - the Минцифры CA trust fallback (`withSberTrust()`) actually completes TLS
 *    to `ngw.devices.sberbank.ru:9443`, which the stock Android trust store
 *    rejects — a real-device-only risk;
 *  - the provisioned Client ID / Secret pairs are accepted by the live Sber
 *    OAuth endpoint for BOTH mandatory scopes.
 */
@RunWith(AndroidJUnit4::class)
class CloudCredentialSmokeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun provisioned(): List<String>? {
        val file = File(context.getExternalFilesDir(null), CREDS_FILE)
        if (!file.isFile) return null
        val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        return if (lines.size >= 4) lines else null
    }

    @Test
    fun keystoreVault_roundTripsProvisionedCredentials() {
        val creds = provisioned()
        assumeTrue("no $CREDS_FILE provisioned on device", creds != null)
        val (saluteId, saluteSecret, gigaId, gigaSecret) = creds!!

        val store = CredentialsStore.init(context)
        store.saluteClientId = saluteId
        store.saluteClientSecret = saluteSecret
        store.gigaChatClientId = gigaId
        store.gigaChatClientSecret = gigaSecret

        // Read back through the vault (a fresh handle over the same store).
        val reread = CredentialsStore.init(context)
        assertEquals("salute id must round-trip the Keystore vault", saluteId, reread.saluteClientId)
        assertEquals("salute secret must round-trip", saluteSecret, reread.saluteClientSecret)
        assertEquals("gigachat id must round-trip", gigaId, reread.gigaChatClientId)
        assertEquals("gigachat secret must round-trip", gigaSecret, reread.gigaChatClientSecret)
        assertTrue("all four mandatory keys must be present", reread.hasMandatoryApiKeys())
    }

    @Test
    fun liveOAuth_acceptsProvisionedCredentialsForBothScopes() {
        val creds = provisioned()
        assumeTrue("no $CREDS_FILE provisioned on device", creds != null)
        val (saluteId, saluteSecret, gigaId, gigaSecret) = creds!!

        val validator = OAuthCredentialValidator()
        val salute = runBlocking { validator.checkSalute(saluteId, saluteSecret) }
        val giga = runBlocking { validator.checkGigaChat(gigaId, gigaSecret) }
        println("SALUTE_OAUTH=$salute")
        println("GIGACHAT_OAUTH=$giga")

        assertTrue(
            "SaluteSpeech pair must be accepted by the live OAuth endpoint, got $salute",
            salute is CredentialCheck.Valid,
        )
        assertTrue(
            "GigaChat pair must be accepted by the live OAuth endpoint, got $giga",
            giga is CredentialCheck.Valid,
        )
    }

    private companion object {
        const val CREDS_FILE = "creds.txt"
    }
}
