package com.jarvis.assistant

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Initialization-order guard for [com.jarvis.assistant.di.AppGraph].
 *
 * `AppGraph` builds real collaborators at construction time and several of them
 * capture lambdas that read OTHER properties of the same object lazily. Kotlin
 * initializes properties in DECLARATION order, so a lambda that is invoked
 * during an earlier-declared property's construction can observe a
 * later-declared property's backing field uninitialized.
 *
 * The concrete failure this pins (a custom wake word silently never worked):
 * `wakeWordDetector` starts building its engine on its own dispatcher from its
 * constructor. With a CUSTOM keyword that build calls back into
 * `buildSherpaEngine` -> `sherpaModelStore`. While `sherpaModelStore` was
 * declared BELOW the detector as a `by lazy`, the callback could run before the
 * lazy delegate was assigned and threw
 * `NullPointerException: Lazy.getValue() on a null object reference`, which the
 * detector surfaced as "wake-word engine build failed — wake word disabled".
 * Nothing else failed: the DEFAULT (bundled) path returns before touching the
 * store, so the bug only appeared once a user configured a custom keyword.
 *
 * The fix is ordering, and ordering is invisible to the compiler and to every
 * behavioural test — so it is pinned structurally here.
 */
class AppGraphInitOrderTest {

    private fun appGraphSource(): File {
        // Unit tests run with the module dir as the working directory; walk up
        // defensively so this also works from the repo root (same approach as
        // StatusContrastTest).
        val relative = "app/src/main/java/com/jarvis/assistant/di/AppGraph.kt"
        val candidates = listOf(File(relative), File("app/../$relative"))
        candidates.firstOrNull { it.isFile }?.let { return it }
        var walk: File? = File(".").absoluteFile
        repeat(4) {
            if (walk != null) {
                val candidate = File(walk, relative)
                if (candidate.isFile) return candidate
                walk = walk!!.parentFile
            }
        }
        return candidates[0]
    }

    private fun declarationLine(text: String, marker: String): Int {
        val index = text.indexOf(marker)
        assertTrue("AppGraph is missing the declaration `$marker`", index >= 0)
        return text.take(index).count { it == '\n' }
    }

    @Test
    fun `sherpaModelStore is declared before the wake-word detector`() {
        val text = appGraphSource().readText()
        val store = declarationLine(text, "private val sherpaModelStore")
        val detector = declarationLine(text, "val wakeWordDetector: WakeWordDetector")
        assertTrue(
            "sherpaModelStore must be declared BEFORE wakeWordDetector: the detector " +
                "builds its engine from its constructor and, for a custom keyword, that " +
                "build reads sherpaModelStore. Declaring it later reintroduces the " +
                "`Lazy.getValue() on a null object reference` NPE that silently disabled " +
                "custom wake words (store line=$store, detector line=$detector).",
            store < detector,
        )
    }

    @Test
    fun `sherpaModelStore is eager so no lazy delegate can be observed unassigned`() {
        val text = appGraphSource().readText()
        assertTrue(
            "sherpaModelStore must not be a `by lazy` property: the detector's engine-build " +
                "callback can run on another thread during AppGraph construction, and an " +
                "unassigned lazy delegate is exactly what threw the NPE.",
            !text.contains("private val sherpaModelStore by lazy"),
        )
    }
}
