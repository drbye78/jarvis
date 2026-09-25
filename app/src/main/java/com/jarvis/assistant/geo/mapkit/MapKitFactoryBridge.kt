package com.jarvis.assistant.geo.mapkit

import android.content.Context
import com.yandex.mapkit.MapKitFactory

/**
 * Test seam over MapKit's static entry points.
 *
 * `MapKitFactory` is a Java class whose `initialize` loads native libraries and
 * whose `getInstance()` is a static native call — neither can be exercised from
 * a JVM unit test. [MapKitInitializer] carries the ordering/once-per-process
 * policy that IS worth testing, so the native surface is narrowed to this
 * interface and faked in tests.
 *
 * This file (plus [MapKitInitializer], which never imports MapKit directly) is
 * the only place allowed to touch `com.yandex.*` in this lane.
 */
interface MapKitFactoryBridge {
    fun setLocale(locale: String)

    fun setApiKey(key: String)

    fun initialize(context: Context)

    fun onStart()
}

/**
 * Production bridge — delegates to the real [MapKitFactory].
 *
 * Every method here wraps a `static synchronized` MapKit call; the SDK is
 * safe to drive once [MapKitInitializer] has enforced the ordering and
 * once-per-process rules.
 */
class DefaultMapKitFactoryBridge : MapKitFactoryBridge {

    override fun setLocale(locale: String) {
        MapKitFactory.setLocale(locale)
    }

    override fun setApiKey(key: String) {
        MapKitFactory.setApiKey(key)
    }

    override fun initialize(context: Context) {
        MapKitFactory.initialize(context)
    }

    override fun onStart() {
        MapKitFactory.getInstance().onStart()
    }
}
