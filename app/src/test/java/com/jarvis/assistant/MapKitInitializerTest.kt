package com.jarvis.assistant

import android.content.Context
import android.content.ContextWrapper
import com.jarvis.assistant.geo.mapkit.MapKitFactoryBridge
import com.jarvis.assistant.geo.mapkit.MapKitInitResult
import com.jarvis.assistant.geo.mapkit.MapKitInitializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [MapKitInitializer]'s once-per-process policy, which is the part that
 * would otherwise only fail on-device (MapKit cannot be re-keyed, and a second
 * `setApiKey` after `initialize` can crash).
 *
 * The project has no Robolectric; the unit-test "mockable" android.jar keeps
 * `ContextWrapper`'s constructor real (it only calls `Context.<init>()`), so a
 * Context can be instantiated without a framework runtime. The fake bridge
 * never calls a method on it.
 */
class MapKitInitializerTest {

    private val context: Context = ContextWrapper(null)

    private fun initializer(bridge: MapKitFactoryBridge) =
        // Unconfined mirrors the app's engineBuildDispatcher test seam: it
        // makes the IO hop synchronous so call-order assertions are exact.
        MapKitInitializer(bridge, mainDispatcher = Dispatchers.Unconfined)

    @Test
    fun `blank key returns NoKey and leaves the bridge untouched`() = runTest {
        val bridge = RecordingBridge()

        val result = initializer(bridge).ensureInitialized(context, "   ")

        assertEquals(MapKitInitResult.NoKey, result)
        assertTrue("a blank key must never touch the native bridge", bridge.calls.isEmpty())
    }

    @Test
    fun `first call with a key initializes exactly once in order`() = runTest {
        val bridge = RecordingBridge()

        val result = initializer(bridge).ensureInitialized(context, "uuid-key")

        assertEquals(MapKitInitResult.Ready, result)
        // onStart() is the documented remedy for LATE init (our lazy Service
        // path) — it must come after initialize(), exactly once.
        assertEquals(
            listOf("setLocale:ru_RU", "setApiKey:uuid-key", "initialize", "onStart"),
            bridge.calls,
        )
    }

    @Test
    fun `second call with the same key touches the bridge no further`() = runTest {
        val bridge = RecordingBridge()
        val init = initializer(bridge)

        assertEquals(MapKitInitResult.Ready, init.ensureInitialized(context, "uuid-key"))
        val afterFirst = bridge.calls.toList()

        assertEquals(MapKitInitResult.Ready, init.ensureInitialized(context, "uuid-key"))
        assertEquals(afterFirst, bridge.calls)
    }

    @Test
    fun `a different key reports KeyChanged and is never re-set`() = runTest {
        val bridge = RecordingBridge()
        val init = initializer(bridge)

        assertEquals(MapKitInitResult.Ready, init.ensureInitialized(context, "key-a"))
        val afterFirst = bridge.calls.toList()

        assertEquals(MapKitInitResult.KeyChanged, init.ensureInitialized(context, "key-b"))
        assertEquals("MapKit must not be re-keyed in-process", afterFirst, bridge.calls)
    }

    @Test
    fun `a bridge throwable becomes Failed and never escapes`() = runTest {
        val bridge = RecordingBridge(failInitializeTimes = 1)

        val result = initializer(bridge).ensureInitialized(context, "uuid-key")

        assertEquals(MapKitInitResult.Failed("boom"), result)
        assertEquals(
            listOf("setLocale:ru_RU", "setApiKey:uuid-key", "initialize"),
            bridge.calls,
        )
    }

    @Test
    fun `a failed init is not remembered so a retry can succeed`() = runTest {
        val bridge = RecordingBridge(failInitializeTimes = 1)
        val init = initializer(bridge)

        assertEquals(MapKitInitResult.Failed("boom"), init.ensureInitialized(context, "uuid-key"))

        assertEquals(MapKitInitResult.Ready, init.ensureInitialized(context, "uuid-key"))
        assertEquals(
            listOf(
                "setLocale:ru_RU",
                "setApiKey:uuid-key",
                "initialize",
                "setLocale:ru_RU",
                "setApiKey:uuid-key",
                "initialize",
                "onStart",
            ),
            bridge.calls,
        )
    }

    /** Records every bridge call so order and multiplicity are assertable. */
    private class RecordingBridge(
        private var failInitializeTimes: Int = 0,
    ) : MapKitFactoryBridge {
        val calls = mutableListOf<String>()

        override fun setLocale(locale: String) {
            calls += "setLocale:$locale"
        }

        override fun setApiKey(key: String) {
            calls += "setApiKey:$key"
        }

        override fun initialize(context: Context) {
            calls += "initialize"
            if (failInitializeTimes > 0) {
                failInitializeTimes--
                error("boom")
            }
        }

        override fun onStart() {
            calls += "onStart"
        }
    }
}
