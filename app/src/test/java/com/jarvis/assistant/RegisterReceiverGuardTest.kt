package com.jarvis.assistant

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static guard for the API-34+ dynamic-receiver flag requirement.
 *
 * `AGENTS.md` claims the Android 14+ guards are handled in code (typed FGS,
 * `SCHEDULE_EXACT_ALARM`, receiver flags, POST_NOTIFICATIONS). Three of those
 * are visible to the JVM suite through their own pure policies; the receiver
 * flag has no such seam — it is a single argument to
 * `ContextCompat.registerReceiver(...)`, and dropping it does NOT fail to
 * compile or to run: on API 34+ an unflagged registration is delivered by any
 * app on the device, and nothing surfaces until someone abuses it. The app was
 * already shipping exactly that: the power receiver at
 * `JarvisForegroundService.registerPowerReceiver()` was registered bare.
 *
 * The rule this test encodes is narrow and honest: every `registerReceiver(`
 * call in main sources must either declare an explicit export flag, or be the
 * `registerReceiver(null, filter)` sticky-broadcast RETRIEVAL (which registers
 * no receiver at all, so there is nothing to export). Both legal forms pass;
 * anything else fails with the offending call printed.
 */
class RegisterReceiverGuardTest {

    private companion object {
        const val CALL = "registerReceiver("
    }

    /** The app module's main source tree, resolved from whatever the test cwd is. */
    private fun sourceDir(): File {
        val relative = "src/main/java/com/jarvis/assistant"
        val candidates = listOf(File(relative), File("app/$relative"))
        candidates.firstOrNull { it.isDirectory }?.let { return it }
        var walk: File? = File(".").absoluteFile
        repeat(4) {
            val current = walk
            if (current != null) {
                val candidate = File(current, "app/$relative")
                if (candidate.isDirectory) return candidate
                walk = current.parentFile
            }
        }
        assertTrue(
            "main source dir not found: $relative (wd=${File(".").absoluteFile})",
            candidates[0].isDirectory,
        )
        return candidates[0]
    }

    /** The argument list of the call whose `(` sits at [open], parens balanced. */
    private fun argumentsOf(source: String, open: Int): String {
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
        }
        assertTrue("unbalanced parentheses in a registerReceiver call at $open", false)
        return ""
    }

    private fun isFlagged(args: String): Boolean =
        args.contains("RECEIVER_NOT_EXPORTED") || args.contains("RECEIVER_EXPORTED")

    private fun isStickyRetrieval(args: String): Boolean = args.trimStart().startsWith("null")

    /** True when `registerReceiver` starts an identifier, i.e. `unregisterReceiver`. */
    private fun isPartOfLongerIdentifier(source: String, at: Int): Boolean {
        val preceding = source.getOrNull(at - 1) ?: return false
        return preceding.isLetterOrDigit() || preceding == '_'
    }

    /**
     * Every unflagged registration in [file], with the offending call text.
     *
     * `unregisterReceiver(it)` CONTAINS `registerReceiver(` — an unregistration
     * carries no export flag and is not a registration, so those are skipped.
     */
    private fun offendingCalls(file: File): List<String> {
        val source = file.readText()
        val offenders = mutableListOf<String>()
        var from = 0
        var at = source.indexOf(CALL, from)
        while (at >= 0) {
            from = at + 1
            val isRegistration = !isPartOfLongerIdentifier(source, at)
            val args = argumentsOf(source, at + CALL.length - 1)
            if (isRegistration && !isFlagged(args) && !isStickyRetrieval(args)) {
                offenders += "${file.name}: $CALL${args.trim()})"
            }
            at = source.indexOf(CALL, from)
        }
        return offenders
    }

    @Test
    fun `every dynamic receiver registration declares its export flag`() {
        val offenders = sourceDir()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { offendingCalls(it) }
            .toList()
        assertTrue(
            "an unflagged dynamic registration is delivered by any app on API 34+ " +
                "(target 36); use ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED):\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
