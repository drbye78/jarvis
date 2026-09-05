package com.jarvis.assistant.cognitive.recall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the deterministic Russian-aware search tokenizer
 * (COGNITIVE_PLAN §5: "deterministic and testable, no native tokenizer
 * gamble").
 */
class SearchTokenizerTest {

    @Test
    fun `normalize lowercases folds yo and strips punctuation`() {
        assertEquals("меня зовут алексей", SearchTokenizer.normalize("Меня зовут Алексей!"))
        assertEquals("ел елки", SearchTokenizer.normalize("ёл ёлки"))
        assertEquals("тарковского 1979", SearchTokenizer.normalize("Тарковского, 1979."))
    }

    @Test
    fun `tokens drop short words and apply light stemming`() {
        // «и»/«в» are dropped (min length 3); «фильмы»→«фильм», «люблю»→«любл».
        assertEquals(listOf("любл", "фильм", "тарковск"), SearchTokenizer.tokens("Люблю фильмы Тарковского"))
    }

    @Test
    fun `indexText is deterministic across calls`() {
        val a = SearchTokenizer.indexText("user", "фильмы Тарковского", "PREFERENCE")
        val b = SearchTokenizer.indexText("user", "фильмы Тарковского", "PREFERENCE")
        assertEquals(a, b)
        assertEquals("user фильм тарковск preference", a)
    }

    @Test
    fun `matchQuery prefixes stems joins with OR and caps term count`() {
        val q = SearchTokenizer.matchQuery("Как зовут мою жену?")
        assertEquals("как* OR зовут* OR мою* OR жен*", q)

        val long = (1..30).joinToString(" ") { "слово$it" }
        assertEquals(12, SearchTokenizer.matchQuery(long)!!.split(" OR ").size)
    }

    @Test
    fun `matchQuery returns null for empty or noise-only text`() {
        assertNull(SearchTokenizer.matchQuery(""))
        assertNull(SearchTokenizer.matchQuery("... , !"))
        assertNull(SearchTokenizer.matchQuery("он и к я"))
    }

    @Test
    fun `match and index are consistent for the same source text`() {
        // The index/query pairing contract: a MATCH built from a text must
        // hit an index built from the same text (stemming is shared).
        val source = "люблю фильмы Тарковского и джаз семидесятых"
        val index = SearchTokenizer.indexText("user", source)
        val indexTokens = index.split(' ')
        val query = SearchTokenizer.matchQuery(source)!!
        assertTrue(query.split(" OR ").all { term ->
            val bare = term.removeSuffix("*")
            indexTokens.any { it.startsWith(bare) }
        })
    }

    @Test
    fun `topic mention finds indexed facts despite differing word forms`() {
        // The actual recall scenario: query mentions «Тарковского», the
        // stored fact says «фильмы Тарковского» — the starred stem matches.
        val index = SearchTokenizer.indexText("user", "люблю фильмы Тарковского")
        val query = SearchTokenizer.matchQuery("что я говорил про Тарковского?")!!
        val queryTerms = query.split(" OR ").map { it.removeSuffix("*") }
        assertTrue(queryTerms.any { bare -> index.split(' ').any { it.startsWith(bare) } })
    }

    @Test
    fun `overlap is asymmetric containment`() {
        // «обожаю Тарковского» shares only the Тарковский stem with
        // «люблю фильмы Тарковского» → 1 of min(2,3).
        assertEquals(0.5f, SearchTokenizer.overlap("обожаю Тарковского", "люблю фильмы Тарковского"), 1e-6f)
        assertEquals(1f, SearchTokenizer.overlap("фильмы Тарковского", "Тарковского"), 1e-6f)
        assertEquals(0f, SearchTokenizer.overlap("погода на завтра", "люблю Тарковского"), 1e-6f)
        assertEquals(0f, SearchTokenizer.overlap("", "что-то"), 1e-6f)
    }

    @Test
    fun `stem never produces a stem shorter than three chars`() {
        assertFalse(SearchTokenizer.stem("жена").length < 3)
        // «мир» has no known suffix — unchanged.
        assertEquals("мир", SearchTokenizer.stem("мир"))
    }
}
