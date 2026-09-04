package com.jarvis.assistant

import com.jarvis.assistant.audio.BpeTokenizer
import com.jarvis.assistant.audio.SherpaKeywords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FIXPLAN C: the pure-Kotlin keyword tokenizer against the REAL bundled
 * bpe.model. Expected segmentations are ground truth produced by
 * sentencepiece (the same toolchain that created the original
 * `▁JA R VI S` keywords.txt line — see FIXPLAN.md §C for the validation
 * protocol: 77 probe words, zero mismatches).
 */
class BpeTokenizerTest {

    private fun modelFile(): File {
        // Unit tests run with the module dir (app/) as CWD; the second path
        // covers running from the repo root (IDE configurations).
        val candidates = listOf(
            File("src/main/assets/sherpa_kws/bpe.model"),
            File("app/src/main/assets/sherpa_kws/bpe.model"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("bpe.model not found — run the suite from the app module")
    }

    private fun tokenizer(): BpeTokenizer =
        BpeTokenizer.fromModelFile(modelFile()) ?: error("bundled bpe.model failed to parse")

    @Test
    fun `parses the bundled model proto into a usable vocab`() {
        val tok = tokenizer()
        // The vocab must contain the pieces the shipped keywords.txt relies on.
        assertNotNull(tok.tokenizeWord("JARVIS"))
        assertNotNull(tok.tokenizeWord("STOP"))
    }

    @Test
    fun `jarvis encodes exactly like the shipped keywords line`() {
        val tok = tokenizer()
        assertEquals("▁JA R VI S", tok.tokenizeKeywordPhrase("Jarvis"))
        assertEquals("▁JA R VI S", tok.tokenizeKeywordPhrase("JARVIS"))
        assertEquals("▁JA R VI S", tok.tokenizeKeywordPhrase("  jarvis  "))
    }

    @Test
    fun `stop and computer match sentencepiece ground truth`() {
        val tok = tokenizer()
        assertEquals("▁ST O P", tok.tokenizeKeywordPhrase("stop"))
        assertEquals("▁COMP U TER", tok.tokenizeKeywordPhrase("COMPUTER"))
        assertEquals("▁HE Y ▁ST O P", tok.tokenizeKeywordPhrase("hey stop"))
    }

    @Test
    fun `digits punctuation and accents are rejected - no dead keywords`() {
        val tok = tokenizer()
        assertNull(tok.tokenizeKeywordPhrase("computer123"))
        assertNull(tok.tokenizeKeywordPhrase("stop!"))
        assertNull(tok.tokenizeKeywordPhrase("café"))
        assertNull(tok.tokenizeKeywordPhrase("стоп")) // the MODEL is English-BPE
        assertNull(tok.tokenizeKeywordPhrase(""))
        assertNull(tok.tokenizeKeywordPhrase("   "))
    }

    @Test
    fun `multi-word phrases reset the word boundary marker per word`() {
        val tok = tokenizer()
        val line = tok.tokenizeKeywordPhrase("HEY STOP")!!
        assertTrue("expected the ▁ marker twice, got: $line", line.count { it == '▁' } == 2)
    }
}

/** FIXPLAN C: the generated keywords-file format must match the shipped assets. */
class SherpaKeywordsTest {

    @Test
    fun `generated content matches the bundled asset lines byte for byte`() {
        val asset = sequenceOf(
            File("src/main/assets/sherpa_kws/keywords.txt"),
            File("app/src/main/assets/sherpa_kws/keywords.txt"),
        ).firstOrNull { it.isFile } ?: return // skip quietly when run from an odd CWD
        val shipped = asset.readText().lines().filter { it.isNotBlank() }
        val generated = SherpaKeywords.toKeywordsFileContent(
            listOf(SherpaKeywords.wake(), SherpaKeywords.stop()),
        ).lines().filter { it.isNotBlank() }
        assertEquals(shipped, generated)
    }

    @Test
    fun `constants match the shipped asset files`() {
        val dir = sequenceOf(
            File("src/main/assets/sherpa_kws"),
            File("app/src/main/assets/sherpa_kws"),
        ).firstOrNull { it.isDirectory } ?: return
        val keywords = dir.resolve("keywords.txt").readText()
        val stopOnly = dir.resolve("keywords_stop.txt").readText()
        assertTrue(keywords.contains(SherpaKeywords.WAKE_TOKEN_LINE))
        assertTrue(keywords.contains(SherpaKeywords.STOP_TOKEN_LINE))
        assertTrue(stopOnly.contains(SherpaKeywords.STOP_TOKEN_LINE))
        assertTrue(stopOnly.contains(SherpaKeywords.WAKE_TOKEN_LINE).not())
    }

    @Test
    fun `parser round-trips token lines through config suffixes`() {
        val content = SherpaKeywords.toKeywordsFileContent(
            listOf(SherpaKeywords.wake(), SherpaKeywords.stop()),
        )
        assertEquals(
            listOf(SherpaKeywords.WAKE_TOKEN_LINE, SherpaKeywords.STOP_TOKEN_LINE),
            SherpaKeywords.parseKeywordsFileContent(content),
        )
    }
}
