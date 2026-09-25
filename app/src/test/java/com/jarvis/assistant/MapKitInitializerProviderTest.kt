package com.jarvis.assistant

import com.jarvis.assistant.geo.mapkit.MapKitInitializerProvider
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Guards the process-scoping of the MapKit initializer (GEO lane).
 *
 * `AppGraph` is rebuilt on every service start, so wiring a new
 * `MapKitInitializer` per graph would reset its once-per-process guard and
 * re-run `setApiKey`, which MapKit rejects ("already set") and may crash on.
 * The provider must therefore hand out ONE instance for the whole process —
 * this identity assertion is the regression guard against that rebuild bug.
 */
class MapKitInitializerProviderTest {

    @Before
    @After
    fun reset() {
        // Mirror the other provider tests (AlarmSchedulerProvider.clearForTests).
        MapKitInitializerProvider.clearForTests()
    }

    @Test
    fun `get returns the same instance every call`() {
        val first = MapKitInitializerProvider.get()
        val second = MapKitInitializerProvider.get()
        assertSame("a per-call initializer would re-run setApiKey on every graph rebuild", first, second)
    }
}
