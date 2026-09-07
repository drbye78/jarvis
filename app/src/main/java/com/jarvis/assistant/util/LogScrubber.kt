package com.jarvis.assistant.util

/**
 * Pattern-based redaction for release log files (REMEDIATION_PLAN P3.4,
 * defense-in-depth behind SpeechContentLoggingTest). App convention: any
 * content-bearing material (utterances, TTS text, LLM replies, tool
 * payloads, user-set labels) is logged at DEBUG only — [SpeechContentLoggingTest]
 * is the pinned precedent. [FileLoggingTree] persists every INFO+ line to
 * disk, so it runs this scrubber over the message and the attached stack
 * trace as a SECOND safety net: when a future code change accidentally
 * lifts a content-bearing line to INFO+, the persisted file still gets
 * redaction.
 *
 * Rule set — deliberately conservative (this is the backstop; the
 * DEBUG-only convention is the primary guard):
 * 1. "content key = value" carriers (`text=…`, `utterance: …`, `query = …`,
 *    `label=…`) → key kept, everything after the separator replaced by
 *    [REDACTED] to end of line (a value can contain spaces and punctuation).
 * 2. Any guillemet («…») or straight-double-quoted («"…"») span of 6+
 *    characters → [REDACTED]. Short quoted state names ('стоп', "IDLE")
 *    survive; long quoted spans in a status line are far more likely user
 *    content than state vocabulary. Single-quoted runs are deliberately NOT
 *    matched (apostrophes inside normal words must not trigger redaction).
 *
 * Matches are replaced in place, so stack frames and surrounding text
 * survive; the scrubber never throws (a malformed line degrades to
 * redaction, never to a crash in the log path).
 */
object LogScrubber {

    /** Replacement token substituted for every scrubbed span. */
    const val REDACTED = "<redacted>"

    /**
     * Vocabulary the codebase actually uses for content carriers (P3.4
     * audit). Case-insensitive; separator is `:` or `=` with optional
     * surrounding spaces.
     */
    private val CONTENT_KEY_ASSIGNMENT = Regex(
        "(?i)\\b(text|utterance|transcript|sentence|reply|answer|response|content|payload|query|prompt|label|script|search)\\b\\s*[:=]\\s*([^\\n]+)",
    )

    /** Guillemet or straight-double-quote span of 6+ characters. */
    private val QUOTED_SPAN = Regex("«[^»]{6,}»|\"[^\"]{6,}\"")

    fun scrub(message: String): String = message
        .replace(CONTENT_KEY_ASSIGNMENT) { m -> "${m.groupValues[1]}=${REDACTED}" }
        .replace(QUOTED_SPAN) { m ->
            // Keep BOTH delimiters of the matched span: the opening char may
            // be « while the match always ends with the matching closer
            // (» or ") — closing with value[0] would emit «…« and corrupt
            // the redacted line instead of sanitizing it.
            "${m.value.first()}$REDACTED${m.value.last()}"
        }
}
