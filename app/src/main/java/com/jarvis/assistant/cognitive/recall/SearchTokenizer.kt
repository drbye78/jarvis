package com.jarvis.assistant.cognitive.recall

/**
 * COGNITIVE_PLAN Phase 1 (§5/§7): deterministic Russian-aware text
 * normalization for the lexical memory index.
 *
 * Why this exists: SQLite's bundled FTS tokenizers are unreliable for
 * Russian morphology — `simple` is case-sensitive for non-ASCII, `unicode61`
 * folds case but does nothing about «Тарковского» vs «Тарковский». The plan's
 * answer is to make BOTH sides of the match deterministic and testable:
 *
 * - the indexed content is WRITTEN pre-tokenized ([indexText] output goes
 *   into `user_facts.searchText`, the single FTS-indexed column);
 * - every query is pre-tokenized into a prefix-MATCH expression
 *   ([matchQuery]) so «тарков» finds «тарковского» without a stemmer
 *   dependency;
 * - a light suffix strip ([stem]) aligns frequent Russian endings on both
 *   sides; the prefix star absorbs everything the strip does not.
 *
 * Pure Kotlin, no Android imports, fully deterministic: identical inputs
 * always produce identical outputs (snapshot-tested).
 */
object SearchTokenizer {

    /** Drop tokens shorter than this — single/double-char noise (и, в, на). */
    private const val MIN_TOKEN_LEN = 3

    /**
     * Light Russian singularization: longest match first. Kept deliberately
     * SMALL and conservative — this is noise reduction for the index, not a
     * morphological analyzer (the LLM is the NLU; the index only has to be
     * self-consistent between indexText and matchQuery).
     */
    private val SUFFIXES = listOf(
        "ами", "ями", "ого", "его", "ому", "ему", "ыми", "ими",
        "ах", "ях", "ов", "ев", "ой", "ей", "ый", "ий", "ая", "яя",
        "ое", "ее", "ые", "ие", "ам", "ям", "ы", "и", "а", "я",
        "о", "е", "у", "ю", "ь",
    )

    /** Minimum stem length after a suffix strip — never reduce below this. */
    private const val MIN_STEM_LEN = 3

    /**
     * Normalize for comparison/storage: lowercase (Locale.ROOT — the device
     * locale must never decide fact identity), ё→е, drop everything that is
     * not a letter/digit/space, collapse whitespace.
     */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        var lastWasSpace = true // leading spaces are never emitted
        for (ch in text.lowercase(java.util.Locale.ROOT)) {
            val c = when (ch) {
                'ё' -> 'е'
                else -> ch
            }
            if (c.isLetterOrDigit()) {
                sb.append(c)
                lastWasSpace = false
            } else if (!lastWasSpace) {
                sb.append(' ')
                lastWasSpace = true
            }
        }
        return sb.toString().trim()
    }

    /** Normalize + suffix-strip each token. Order-stable, deduplicated. */
    fun tokens(text: String, minLength: Int = MIN_TOKEN_LEN): List<String> {
        val seen = LinkedHashSet<String>()
        for (raw in normalize(text).split(' ')) {
            if (raw.length < minLength) continue
            seen.add(stem(raw))
        }
        return seen.toList()
    }

    /**
     * Pre-tokenized text for the FTS index column: space-joined stems, no
     * punctuation, no case. Written once per fact; never re-derived at query
     * time from the raw value (the two MUST stay in sync via this same
     * function — enforced by the DAO write path).
     */
    fun indexText(vararg parts: String): String =
        parts.flatMap { tokens(it) }.joinToString(" ")

    /**
     * FTS4 MATCH expression for [text]: prefix-starred stems joined with OR
     * (recall-oriented — ranking decides relevance, the index only has to
     * surface candidates), capped at [maxTerms] (bounded worst-case query
     * cost on a long rambling utterance). Returns null when nothing
     * searchable remains — callers skip the FTS round-trip entirely.
     */
    fun matchQuery(text: String, maxTerms: Int = 12): String? {
        val terms = tokens(text).take(maxTerms)
        if (terms.isEmpty()) return null
        return terms.joinToString(" OR ") { "$it*" }
    }

    /**
     * Lexical overlap between two free-text strings on stemmed token sets:
     |A∩B| / min(|A|,|B|) — asymmetric containment, 1.0 when one side
     * is fully covered by the other (paraphrase detection, plan §6.3).
     * 0.0 when either side has no tokens.
     */
    fun overlap(a: String, b: String): Float {
        val ta = tokens(a).toSet()
        val tb = tokens(b).toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0f
        return ta.intersect(tb).size.toFloat() / minOf(ta.size, tb.size).toFloat()
    }

    /** Strip the longest known suffix if the remainder stays long enough. */
    internal fun stem(word: String): String {
        for (suffix in SUFFIXES) {
            if (word.length - suffix.length >= MIN_STEM_LEN && word.endsWith(suffix)) {
                return word.dropLast(suffix.length)
            }
        }
        return word
    }
}
