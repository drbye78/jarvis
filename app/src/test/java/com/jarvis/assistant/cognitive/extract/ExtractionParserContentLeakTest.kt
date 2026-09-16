package com.jarvis.assistant.cognitive.extract

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

/**
 * P1-C (audit MEDIUM): extraction failures must never carry utterance/fact
 * content into the log lane. kotlinx JSON exception messages quote the raw
 * fragment they tripped on — the old code embedded `e.message` in the
 * ParseError detail and passed the throwable to Timber.w, and FileLoggingTree
 * persists WARN+ to disk. These tests pin the scrubbed contract: the class
 * NAME is the only thing that crosses, the content never does.
 */
class ExtractionParserContentLeakTest {

    private class CapturingTree : Timber.Tree() {
        val lines = mutableListOf<Triple<Int, String, String?>>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            synchronized(lines) {
                lines.add(Triple(priority, message, t?.message))
            }
        }
    }

    private val tree = CapturingTree()

    @After
    fun tearDown() {
        Timber.uprootAll()
    }

    private companion object {
        const val SENTINEL = "СЕКРЕТНЫЙ-ПАРОЛЬ-от-почты"
        val BATCH = listOf(1L to "напомни про встречу")
    }

    @Test
    fun `malformed JSON reports only the exception class - never the quoted fragment`() {
        val parser = ExtractionParser()
        // Truncated array WITH a closing brace so the { … } window survives
        // extraction and the kotlinx parse is the step that fails: the old
        // code embedded e.message here, and JsonDecodingException quotes the
        // offending window — which is exactly where the sentinel sits.
        val response = """{"facts": [{"value": "$SENTINEL", "evidence": "x"}"""
        val result = parser.parse(response, BATCH)
        assertTrue("expected a ParseError", result is ExtractionParser.Result.ParseError)
        val detail = (result as ExtractionParser.Result.ParseError).detail
        assertTrue(
            "detail must be class-named, content-free: $detail",
            detail.startsWith("invalid JSON (") && !detail.contains(SENTINEL),
        )
    }

    @Test
    fun `a non-array facts field logs a content-free WARN and quarantines`() {
        Timber.uprootAll()
        Timber.plant(tree)
        val parser = ExtractionParser()
        val response = """{"facts": "$SENTINEL"}"""
        val result = parser.parse(response, BATCH)
        assertTrue(result is ExtractionParser.Result.ParseError)
        val detail = (result as ExtractionParser.Result.ParseError).detail
        assertEquals("missing facts array", detail)

        val warns = tree.lines.filter { it.first >= 5 }
        assertEquals("exactly one WARN", 1, warns.size)
        val (priority, message, throwableMessage) = warns.single()
        assertEquals(5, priority) // Timber.w
        // Old code passed the throwable: kotlinx names the offending element
        // ("Element JsonPrimitive(...) is not an array") — the sentinel must
        // appear in NEITHER the formatted message NOR an attached throwable.
        assertTrue(
            "WARN message carries only the exception class: $message",
            message.contains("not an array") && !message.contains(SENTINEL),
        )
        assertEquals(
            "no throwable may ride along (its message embeds content)",
            null,
            throwableMessage,
        )
    }

    @Test
    fun `the scrubbed ParseError detail still distinguishes failure shapes`() {
        val parser = ExtractionParser()
        // No JSON at all.
        val noJson = parser.parse("извини, не понял", BATCH)
        assertEquals(
            ExtractionParser.Result.ParseError("no JSON object in response"),
            noJson,
        )
        // Malformed-but-braced: class name only, e.g. "invalid JSON (JsonDecodingException)".
        val malformed = parser.parse("""{"facts": [ }""", BATCH) as ExtractionParser.Result.ParseError
        assertTrue(malformed.detail.matches(Regex("invalid JSON \\(\\w+\\)")))
        // The scrubbed detail is content-free: no braces/quotes from the raw body survive.
        assertTrue(!malformed.detail.contains('{') && !malformed.detail.contains('}'))
    }

    @Test
    fun `well-formed responses are unaffected by the scrubbing`() {
        val parser = ExtractionParser()
        val ok = parser.parse(
            """{"facts": [{"messageId": 1, "value": "встреча", "evidence": "про встречу", "predicate": "schedule", "confidence": 0.9}]}""",
            BATCH,
        )
        assertTrue(ok is ExtractionParser.Result.Ok)
        assertEquals(1, (ok as ExtractionParser.Result.Ok).facts.size)
    }
}
