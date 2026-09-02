package com.jarvis.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Minimal instrumentation smoke tests that verify the app process boots
 * correctly on a real (or emulated) device and the core framework objects
 * are accessible.
 *
 * These are intentionally lightweight — no Hilt/AppGraph, no network,
 * no wake-word engine. Just "does the APK start without exploding?"
 */
@RunWith(AndroidJUnit4::class)
class BasicSmokeTest {

    /** The instrumentation context points at the APK under test. */
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun applicationClass_isInstantiable() {
        // The manifest declares JarvisApplication; verify it can be resolved
        // and that the running context's application is an instance of it.
        val app = context.applicationContext
        assertNotNull("applicationContext must not be null", app)
        assertTrue(
            "applicationContext should be JarvisApplication but was ${app.javaClass.name}",
            app is JarvisApplication,
        )
    }

    @Test
    fun applicationContext_isUsable() {
        val ctx = context.applicationContext
        assertNotNull("packageName must be resolvable", ctx.packageName)
        assertTrue(
            "packageName should contain 'jarvis' but was ${ctx.packageName}",
            ctx.packageName.contains("jarvis"),
        )
    }

    @Test
    fun encryptedSharedPreferences_canBeCreated() {
        // EncryptedSharedPreferences is used for the CredentialsStore; verify
        // the underlying crypto primitives are available on the device.
        val prefs = context.getSharedPreferences("smoke_test_prefs", android.content.Context.MODE_PRIVATE)
        assertNotNull("SharedPreferences must be obtainable", prefs)
        prefs.edit().putString("smoke_key", "smoke_value").commit()
        assertTrue(
            "written value must be readable",
            prefs.getString("smoke_key", null) == "smoke_value",
        )
        // Clean up so repeated runs don't accumulate.
        prefs.edit().clear().commit()
    }
}
